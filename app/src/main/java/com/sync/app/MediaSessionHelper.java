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

    public void update(JSONObject msg) {
        String title = msg.optString("title", "");
        String artist = msg.optString("artist", "");
        long positionMs = (long) (msg.optDouble("position", 0) * 1000);
        long durationMs = (long) (msg.optDouble("duration", 0) * 1000);
        boolean playing = msg.optBoolean("playing", false);

        MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0, durationMs));
        // 현재 곡의 아트일 때만 첨부 (곡이 바뀐 직후 이전 곡 아트가 남지 않도록)
        final String vid = msg.optString("videoId", "");
        if (art != null && !art.isRecycled() && vid.equals(artVideoId)) {
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
              .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
              .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art);
        }
        session.setMetadata(md.build());

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;

        int state = playing
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;

        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, Math.max(0, positionMs), 1f)
                .build());
    }

    public MediaSessionCompat.Token getSessionToken() {
        return session.getSessionToken();
    }

    public void release() {
        session.setActive(false);
        session.release();
    }
}
