package com.sync.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 알림 / 잠금화면 / 미디어 출력창에 표시할 고화질 앨범아트를 가져온다.
 *
 * - 가장 큰 해상도부터 차례로 시도: maxresdefault(1280x720) → hq720 → sddefault → hqdefault
 *   (YouTube 는 없는 해상도를 120x90 회색 이미지로 돌려주기도 하므로 크기로 걸러낸다)
 * - 이미지에 포함된 검은 여백(레터/필러박스)을 제거
 * - 중앙 정사각형으로 잘라 최대 1024px 로 맞춘다 (알림 UI 는 정사각형으로 표시됨)
 *
 * 네트워크 작업이므로 반드시 백그라운드 스레드에서 호출할 것.
 */
final class AlbumArtLoader {
    private static final String TAG = "AlbumArtLoader";
    private static final int MAX_SIDE = 1024;
    private static final String[] YT_VARIANTS = {
            "maxresdefault.jpg", "hq720.jpg", "sddefault.jpg", "hqdefault.jpg"
    };

    private AlbumArtLoader() {}

    /** @param videoId YouTube 영상 ID (없으면 빈 문자열)  @param fallbackUrl 그 외 썸네일 http(s) URL (없으면 빈 문자열) */
    static Bitmap load(String videoId, String fallbackUrl) {
        try {
            if (videoId != null && !videoId.isEmpty()) {
                for (String v : YT_VARIANTS) {
                    Bitmap raw = download("https://i.ytimg.com/vi/" + videoId + "/" + v);
                    // 120x90 회색 플레이스홀더 / 지나치게 작은 이미지는 건너뜀
                    if (raw != null && raw.getWidth() > 160) return process(raw);
                    if (raw != null) raw.recycle();
                }
            }
            if (fallbackUrl != null && fallbackUrl.startsWith("http")) {
                Bitmap raw = download(fallbackUrl);
                if (raw != null) return process(raw);
            }
        } catch (Throwable t) {
            Log.w(TAG, "load failed", t);
        }
        return null;
    }

    private static Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                byte[] data = bos.toByteArray();
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 검은 여백 제거 → 중앙 정사각형 → 최대 MAX_SIDE */
    private static Bitmap process(Bitmap src) {
        final int w = src.getWidth(), h = src.getHeight();
        final int maxX = (int) (w * 0.4f), maxY = (int) (h * 0.4f);
        int t = 0, b = 0, l = 0, r = 0;
        while (t < maxY && rowDark(src, t)) t++;
        while (b < maxY && rowDark(src, h - 1 - b)) b++;
        while (l < maxX && colDark(src, l)) l++;
        while (r < maxX && colDark(src, w - 1 - r)) r++;

        int cx = 0, cy = 0, cw = w, ch = h;
        if (t >= 2 || b >= 2 || l >= 2 || r >= 2) {
            int nw = w - l - r, nh = h - t - b;
            if (nw >= w * 0.4f && nh >= h * 0.4f) { cx = l; cy = t; cw = nw; ch = nh; }
        }
        final int side = Math.min(cw, ch);
        cx += (cw - side) / 2;
        cy += (ch - side) / 2;

        Bitmap out = Bitmap.createBitmap(src, cx, cy, side, side);
        if (side > MAX_SIDE) {
            Bitmap scaled = Bitmap.createScaledBitmap(out, MAX_SIDE, MAX_SIDE, true);
            if (scaled != out && out != src) out.recycle();
            out = scaled;
        }
        if (out != src) src.recycle();
        return out;
    }

    private static boolean isDark(int px) {
        int rr = (px >> 16) & 0xFF, gg = (px >> 8) & 0xFF, bb = px & 0xFF;
        return (rr + gg + bb) / 3 < 22;
    }

    private static boolean rowDark(Bitmap bm, int y) {
        final int w = bm.getWidth();
        int dark = 0, total = 0;
        for (int x = 0; x < w; x += 4) { total++; if (isDark(bm.getPixel(x, y))) dark++; }
        return total > 0 && dark / (float) total > 0.97f;
    }

    private static boolean colDark(Bitmap bm, int x) {
        final int h = bm.getHeight();
        int dark = 0, total = 0;
        for (int y = 0; y < h; y += 4) { total++; if (isDark(bm.getPixel(x, y))) dark++; }
        return total > 0 && dark / (float) total > 0.97f;
    }
}
