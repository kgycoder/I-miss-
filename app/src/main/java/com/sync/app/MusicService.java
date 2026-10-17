package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.HashMap;
import java.util.Map;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * Foreground Service that keeps the app process alive while music is playing in the background.
 *
 * Responsibilities:
 *  1. Call startForeground() with a MediaStyle notification → prevents system kill
 *  2. Keep the notification in sync when track/play state changes
 *  3. Expose IBinder so MainActivity can call update() / stopForeground()
 *
 * Audio is NOT produced here — it still comes from the WebView (YouTube IFrame API).
 * This service exists purely to satisfy Android's background process requirements.
 */
public class MusicService extends Service {

    static final String CHANNEL_ID  = "sync_music_playback";
    static final int    NOTIF_ID    = 1001;

    static final String ACTION_PLAY  = "com.sync.app.PLAY";
    static final String ACTION_PAUSE = "com.sync.app.PAUSE";
    static final String ACTION_NEXT  = "com.sync.app.NEXT";
    static final String ACTION_PREV  = "com.sync.app.PREV";
    static final String ACTION_STOP  = "com.sync.app.STOP";

    /** 알림/잠금화면 버튼 → 액티비티(WebView) 로 명령을 전달하는 통로. 액티비티가 살아 있을 때만 설정된다. */
    public interface CommandSink { void onCommand(String cmd); }
    static volatile CommandSink commandSink = null;

    // ── Binder for MainActivity ──────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }
    private final IBinder binder = new LocalBinder();

    // ── State ────────────────────────────────────────────────────────────────────────
    private String  title    = "SYNC";
    private String  artist   = "";
    private boolean playing  = false;
    private MediaSessionCompat.Token sessionToken = null;

    // ── Native MediaPlayer for background audio ─────────────────────────────
    // Used when the WebView renderer process is killed in the background.
    // This player runs in the Service process (which is kept alive by
    // startForeground) and therefore survives renderer death.
    private MediaPlayer bgPlayer    = null;
    private double      bgOffsetSec = 0;   // seek offset when player started
    private long        bgStartMs   = 0;   // System.currentTimeMillis() at start
    private WifiManager.WifiLock wifiLock = null;
    private Bitmap albumArt = null;   // 알림 큰 아이콘(고화질 앨범아트)
    // 마지막으로 notify() 한 내용 (같으면 다시 보내지 않음 — 알림 갱신은 비용이 크다)
    private String  shownTitle = null, shownArtist = null;
    private boolean shownPlaying = false;
    private Bitmap  shownArt = null;
    private MediaSessionCompat.Token shownToken = null;


    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService() 로 시작된 경우 5초 안에 startForeground() 를 호출해야 하므로 항상 먼저 호출한다.
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            android.util.Log.w("MusicService", "startForeground failed", e);
        }
        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopBgAudio();
            CommandSink s = commandSink;
            if (s != null && playing) s.onCommand("pause");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action))       handleCommand("play");
        else if (ACTION_PAUSE.equals(action)) handleCommand("pause");
        else if (ACTION_NEXT.equals(action))  handleCommand("next");
        else if (ACTION_PREV.equals(action))  handleCommand("prev");
        // 프로세스가 죽은 뒤 빈 서비스/유령 알림만 되살아나지 않도록 STICKY 를 쓰지 않는다.
        return START_NOT_STICKY;
    }

    /** 알림 버튼 처리: 액티비티가 있으면 WebView 로, 없으면(앱 종료 후 이어듣기) 네이티브 플레이어에 직접 적용. */
    private void handleCommand(String cmd) {
        CommandSink s = commandSink;
        if (s != null) { s.onCommand(cmd); return; }
        if (bgPlayer == null) return;
        try {
            if ("pause".equals(cmd) && bgPlayer.isPlaying()) {
                bgPlayer.pause();
                playing = false;
            } else if ("play".equals(cmd) && !bgPlayer.isPlaying()) {
                bgPlayer.start();
                playing = true;
            } else {
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
            shownTitle = null;   // 직접 갱신했으므로 다음 updateNotification() 은 반드시 반영
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) { return true; }  // allow rebind

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(1 /* STOP_FOREGROUND_REMOVE */);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
        stopBgAudio();
    }

    // ── Public API called by MainActivity ───────────────────────────────────

    /**
     * Update the notification with current playback state.
     * Called by MainActivity whenever a mediaState message arrives from JS.
     */
    /** 알림에 표시할 앨범아트 설정(null 이면 제거). 다음 updateNotification() 호출 때 반영된다. */
    public void setArt(Bitmap bitmap) {
        this.albumArt = bitmap;
    }

    public void updateNotification(String title, String artist, boolean playing,
                                   MediaSessionCompat.Token token) {
        this.title   = (title  != null && !title.isEmpty())  ? title  : "SYNC";
        this.artist  = (artist != null && !artist.isEmpty()) ? artist : "";
        this.playing = playing;
        this.sessionToken = token;

        // JS 가 주기적으로 보내는 동일한 상태로 알림을 계속 다시 만들지 않는다.
        if (shownTitle != null
                && this.title.equals(shownTitle) && this.artist.equals(shownArtist)
                && this.playing == shownPlaying && albumArt == shownArt && token == shownToken) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification());
            shownTitle = this.title; shownArtist = this.artist;
            shownPlaying = this.playing; shownArt = albumArt; shownToken = token;
        }
    }

    /**
     * Start native background audio from a direct stream URL.
     *
     * Called by MainActivity.onPause() when the YouTube stream URL is available.
     * Runs entirely in the Service process — survives WebView renderer death.
     *
     * @param streamUrl Direct HTTP/HTTPS audio URL (from InnerTube API)
     * @param positionSec Playhead position to seek to before playback starts
     */
    public void startBgAudio(final String streamUrl, final double positionSec) {
        stopBgAudio(); // release any previous player
        if (streamUrl == null || streamUrl.isEmpty()) return;
        try {
            bgPlayer = new MediaPlayer();
            bgPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent",
                    "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_0 like Mac OS X)");
            bgPlayer.setDataSource(getApplicationContext(), Uri.parse(streamUrl), headers);
            bgPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            acquireWifiLock();
            bgPlayer.setOnPreparedListener(mp -> {
                int seekMs = (int) (positionSec * 1000);
                if (seekMs > 0) mp.seekTo(seekMs);
                mp.start();
                bgOffsetSec = positionSec;
                bgStartMs   = System.currentTimeMillis() - seekMs;
                android.util.Log.d("MusicService",
                        "BG native audio started at " + positionSec + "s");
            });
            bgPlayer.setOnCompletionListener(mp -> {
                // 액티비티 없이 이어듣기 중이던 곡이 끝남 → 알림만 남기고 서비스 정리
                playing = false;
                stopBgAudio();
                if (commandSink == null) {
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
                        shownTitle = null;
                    } catch (Exception ignored) {}
                    stopForeground(2 /* STOP_FOREGROUND_DETACH */);
                    stopSelf();
                }
            });
            bgPlayer.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("MusicService",
                        "BG MediaPlayer error what=" + what + " extra=" + extra);
                stopBgAudio();
                return true;
            });
            bgPlayer.prepareAsync();
        } catch (Exception e) {
            android.util.Log.e("MusicService", "startBgAudio failed", e);
            stopBgAudio();
        }
    }

    /** Stop native background audio and release the MediaPlayer. */
    public void stopBgAudio() {
        if (bgPlayer != null) {
            try { bgPlayer.stop();    } catch (Exception ignored) {}
            try { bgPlayer.release(); } catch (Exception ignored) {}
            bgPlayer = null;
        }
        releaseWifiLock();
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm == null) return;
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SYNC::ServiceWifiLock");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    /**
     * Returns the current playback position (in seconds) of the native audio.
     * Returns -1 if not playing.
     */
    public double getBgPosition() {
        if (bgPlayer == null) return -1;
        try {
            if (!bgPlayer.isPlaying()) return -1;
            return bgPlayer.getCurrentPosition() / 1000.0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Returns true if native background audio is currently active. */
    public boolean isBgAudioActive() {
        if (bgPlayer == null) return false;
        try { return bgPlayer.isPlaying(); } catch (Exception e) { return false; }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "음악 재생",
                    NotificationManager.IMPORTANCE_LOW   // no sound/vibration
            );
            ch.setDescription("SYNC 백그라운드 음악 재생");
            ch.setShowBadge(false);
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        // ── Action intents (sent to MainActivity via PendingIntent) ──────────
        final int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent piPrev  = actionPI(ACTION_PREV,  0, piFlags);
        PendingIntent piPlay  = actionPI(playing ? ACTION_PAUSE : ACTION_PLAY, 1, piFlags);
        PendingIntent piNext  = actionPI(ACTION_NEXT,  2, piFlags);
        PendingIntent piStop  = actionPI(ACTION_STOP,  3, piFlags);

        // Tap notification → open MainActivity
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPI = PendingIntent.getActivity(this, 10, openIntent, piFlags);

        // ── Play/pause icon ──────────────────────────────────────────────────
        int playIcon = playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        String playLabel = playing ? "일시정지" : "재생";

        // ── MediaStyle ───────────────────────────────────────────────────────
        MediaStyle style = new MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);  // prev, play, next in compact
        if (sessionToken != null) {
            style.setMediaSession(sessionToken);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentPI)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)          // swipe-to-dismiss only when paused
                .setDeleteIntent(piStop)      // 일시정지 상태에서 알림을 밀어서 지우면 서비스도 종료
                .setStyle(style)
                .addAction(android.R.drawable.ic_media_previous, "이전", piPrev)
                .addAction(playIcon, playLabel, piPlay)
                .addAction(android.R.drawable.ic_media_next,     "다음", piNext);

        if (albumArt != null && !albumArt.isRecycled()) b.setLargeIcon(albumArt);

        return b.build();
    }

    /**
     * 알림 버튼은 액티비티를 앞으로 불러오지 않고 서비스로 전달한다.
     * (앱이 백그라운드여도 화면 전환 없이 재생/일시정지/이전/다음이 동작)
     */
    private PendingIntent actionPI(String action, int requestCode, int flags) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, requestCode, i, flags);
        }
        return PendingIntent.getService(this, requestCode, i, flags);
    }
}
