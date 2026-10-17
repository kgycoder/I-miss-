package com.sync.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import org.json.JSONObject;

/** 잠금 화면·이어폰 등 시스템 미디어 컨트롤 */
public class MediaSessionHelper {

    public interface CommandBridge {
        void onMediaCommand(String cmd);
    }

    private final MediaSessionCompat session;
    private final CommandBridge bridge;

    public MediaSessionHelper(Context context, CommandBridge bridge) {
        this.bridge = bridge;
        session = new MediaSessionCompat(context, "SYNC");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                bridge.onMediaCommand("play");
            }

            @Override
            public void onPause() {
                bridge.onMediaCommand("pause");
            }

            @Override
            public void onSkipToNext() {
                bridge.onMediaCommand("next");
            }

            @Override
            public void onSkipToPrevious() {
                bridge.onMediaCommand("prev");
            }
        });
        session.setActive(true);
    }

    private Bitmap art = null;
    private String artVideoId = "";

    /** 고화질 앨범아트 설정. update() 가 메타데이터를 다시 만들 때도 유지된다. null 이면 제거. */
    public void setArt(String videoId, Bitmap bitmap) {
        this.artVideoId = videoId != null ? videoId : "";
        this.art = bitmap;
    }

    // 마지막으로 세션에 반영한 값 (동일하면 setMetadata / setPlaybackState 를 다시 호출하지 않는다)
    private String  mdTitle = null, mdArtist = null, mdVideoId = null;
    private long    mdDuration = -1;
    private Bitmap  mdArt = null;
    private int     stState = -1;
    private long    stPosMs = 0, stAtMs = 0;

    public void update(JSONObject msg) {
        String title = msg.optString("title", "");
        String artist = msg.optString("artist", "");
        long positionMs = (long) (msg.optDouble("position", 0) * 1000);
        long durationMs = Math.max(0, (long) (msg.optDouble("duration", 0) * 1000));
        boolean playing = msg.optBoolean("playing", false);
        final String vid = msg.optString("videoId", "");

        // 현재 곡의 아트일 때만 첨부 (곡이 바뀐 직후 이전 곡 아트가 남지 않도록)
        final Bitmap useArt = (art != null && !art.isRecycled() && vid.equals(artVideoId)) ? art : null;

        // ── 메타데이터: 곡/제목/길이/아트가 바뀐 경우에만 갱신 (비트맵 재전송 비용 큼) ──
        final boolean mdChanged = !title.equals(mdTitle) || !artist.equals(mdArtist)
                || !vid.equals(mdVideoId) || durationMs != mdDuration || useArt != mdArt;
        if (mdChanged) {
            MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
            if (useArt != null) {
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, useArt)
                  .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, useArt)
                  .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, useArt);
            }
            session.setMetadata(md.build());
            mdTitle = title; mdArtist = artist; mdVideoId = vid; mdDuration = durationMs; mdArt = useArt;
        }

        // ── 재생 상태: 재생/일시정지가 바뀌었거나, 위치가 예상(1배속 진행)과 크게 어긋난 경우(시킹)에만 갱신 ──
        final int state = playing
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;
        final long nowMs = android.os.SystemClock.elapsedRealtime();
        final long expected = (stState == PlaybackStateCompat.STATE_PLAYING)
                ? stPosMs + (nowMs - stAtMs) : stPosMs;
        final boolean stChanged = state != stState || Math.abs(positionMs - expected) > 2000;
        if (stChanged) {
            long actions = PlaybackStateCompat.ACTION_PLAY
                    | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
            session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, Math.max(0, positionMs), 1f)
                    .build());
            stState = state; stPosMs = Math.max(0, positionMs); stAtMs = nowMs;
        }
    }

    public MediaSessionCompat.Token getSessionToken() {
        return session.getSessionToken();
    }

    public void release() {
        session.setActive(false);
        session.release();
    }
}
