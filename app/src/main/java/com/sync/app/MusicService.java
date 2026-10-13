package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
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


    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Notification action buttons forward their intents to MainActivity via broadcast
            // We just need to keep the service running; MainActivity handles the actual command
            // via the existing __mediaCmd JS bridge.
            final String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        // Show the foreground notification immediately so we survive background
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
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
    public void updateNotification(String title, String artist, boolean playing,
                                   MediaSessionCompat.Token token) {
        this.title   = (title  != null && !title.isEmpty())  ? title  : "SYNC";
        this.artist  = (artist != null && !artist.isEmpty()) ? artist : "";
        this.playing = playing;
        this.sessionToken = token;

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification());
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
            bgPlayer.setDataSource(streamUrl);
            bgPlayer.setOnPreparedListener(mp -> {
                int seekMs = (int) (positionSec * 1000);
                if (seekMs > 0) mp.seekTo(seekMs);
                mp.start();
                bgOffsetSec = positionSec;
                bgStartMs   = System.currentTimeMillis() - seekMs;
                android.util.Log.d("MusicService",
                        "BG native audio started at " + positionSec + "s");
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
                .setStyle(style)
                .addAction(android.R.drawable.ic_media_previous, "이전", piPrev)
                .addAction(playIcon, playLabel, piPlay)
                .addAction(android.R.drawable.ic_media_next,     "다음", piNext);

        return b.build();
    }

    private PendingIntent actionPI(String action, int requestCode, int flags) {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(action);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, requestCode, i, flags);
    }
}
