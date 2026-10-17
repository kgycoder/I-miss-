package com.sync.app;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SYNC";
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private LocalMediaStore localMediaStore;
    private MediaSessionHelper mediaSessionHelper;

    // ── Background playback ──────────────────────────────────────────────────
    /** Tracks whether JS reported the player is currently playing. */
    private volatile boolean isPlaying = false;
    /** Last known track title/artist/videoId/position for notification + bg audio. */
    private String lastTitle    = "SYNC";
    private String lastArtist   = "";
    private String lastVideoId  = "";
    private double lastPosition = 0.0;
    private double lastDuration = 0.0;
    private long lastArtAttemptMs = 0;
    /**
     * Cache of pre-fetched YouTube audio stream URLs, keyed by videoId.
     * URLs are valid for ~6 hours. Populated async when a track starts.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> streamUrlCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** CPU wake lock: keeps the CPU running while the screen is off. */
    private PowerManager.WakeLock wakeLock;
    /** Reference to the bound MusicService. */
    private MusicService musicService;
    private boolean serviceStarted = false;
    /** 백그라운드에서도 "보이는 창"으로 인식되도록 가시성을 유지시키는 WebView (activity_main.xml). */
    private BgWebView bgWebView;
    private long lastStateAtMs = 0;
    /** onPause ~ onResume 사이(화면 꺼짐/홈/다른 앱) true. 락·가시성 유지는 이 구간에서만 필요하다. */
    private boolean inBackground = false;
    private long wakeLockAtMs = 0;
    private boolean serviceBindTried = false;
    private final java.util.Set<String> prefetchInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.concurrent.ConcurrentHashMap<String, Long> prefetchFailAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 알림/미디어 세션용 고화질 앨범아트 (lastArtVideoId 곡의 것) */
    private android.graphics.Bitmap lastArt = null;
    private String lastArtVideoId = "";
    private String lastArtRequestedId = "";
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 곡 전환 순간 등 잠깐의 playing=false 로 백그라운드 재생이 풀리지 않도록 두는 유예 시간 */
    private static final long BG_GRACE_MS = 20_000L;
    private final Runnable bgReleaseRunnable = () -> {
        if (!isPlaying) {
            releaseWakeLock();
            if (bgWebView != null) bgWebView.setKeepVisible(false);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            musicService = binder.getService();
            // 앱을 닫은 뒤 이어듣던 네이티브 재생이 남아 있는데 UI 가 새로 재생을 시작했다면 이중 재생 방지
            if (isPlaying && musicService.isBgAudioActive()) musicService.stopBgAudio();
            // Push the current state immediately so the notification is accurate
            refreshServiceNotification();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicService = null;
        }
    };
    // ────────────────────────────────────────────────────────────────────────

    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(true)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);


    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        if (webView instanceof BgWebView) bgWebView = (BgWebView) webView;
        // Liquid Glass: WebView(frontend)를 Compose backdrop 소스로 옮기고 네이티브 글래스 레이어를 얹는다.
        // 이후 WebView 설정/브릿지/로딩 로직은 기존 그대로 동작한다.
        com.sync.app.glass.GlassHost.install(this, webView);
        localMediaStore = new LocalMediaStore(getFilesDir());

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                        new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/res/",
                        new WebViewAssetLoader.ResourcesPathHandler(this))
                .build();

        executor.submit(() -> {
            try {
                LocalMediaDownloader.ensureInitialized(getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "yt-dlp pre-init failed (will retry on download)", e);
            }
        });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setDefaultTextEncodingName("UTF-8");
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36");

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(Color.parseColor("#08080D"));

        // Keep WebView renderer at high priority even when not visible.
        // Without this, Android may throttle the renderer process in the
        // background and indirectly cause page-visibility state changes.
        webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false  // do NOT waive priority when the WebView is not visible
        );

        mediaSessionHelper = new MediaSessionHelper(this, cmd ->
                runOnUiThread(() -> dispatchMediaCommand(cmd)));

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());

        // ── 백그라운드 재생 ──
        installBackgroundVisibilityScript();
        MusicService.commandSink = cmd -> runOnUiThread(() -> dispatchMediaCommand(cmd));
        requestNotificationPermissionIfNeeded();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path != null && path.startsWith("/local/")) {
                        String videoId = path.substring("/local/".length());
                        if (!videoId.isEmpty() && !videoId.contains("..")) {
                            File file = localMediaStore.findMediaFile(videoId);
                            if (file != null) {
                                try {
                                    return LocalMediaStore.openWithRange(file, request);
                                } catch (IOException e) {
                                    Log.e(TAG, "local media range failed", e);
                                }
                            }
                        }
                    }
                }

                WebResourceResponse response =
                        assetLoader.shouldInterceptRequest(uri);
                if (response == null) return null;

                // AssetLoader 응답의 MIME만 가져오고
                // encoding을 UTF-8로 명시한 2인수 생성자 사용 (NPE 없음)
                String mime = response.getMimeType();
                if (mime == null) mime = "text/plain";
                // charset이 이미 붙어 있으면 제거 후 재지정
                if (mime.contains(";")) mime = mime.substring(0, mime.indexOf(";")).trim();

                String encoding = (mime.startsWith("text/") || mime.contains("javascript")
                        || mime.contains("json") || mime.contains("xml"))
                        ? "UTF-8" : null;
                return new WebResourceResponse(mime, encoding, response.getData());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return false;
            }

            /**
             * Called when the WebView renderer process is killed by Android
             * (e.g., due to memory pressure). Our native MediaPlayer in
             * MusicService continues to play audio unaffected.
             *
             * We reload the page so the UI recovers. On re-load, onResume()
             * will seek the YouTube IFrame to the current native position.
             */
            @Override
            public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "WebView renderer killed — reloading page. " +
                        "Native audio continues in MusicService.");
                startNativeFallback(false);
                try {
                    // The WebView is no longer usable; reload restores it
                    view.loadUrl(
                        "https://appassets.androidplatform.net/assets/www/index.html");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to reload after renderer death", e);
                }
                return true; // we handled it — don't crash the host activity
            }
        });

        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/www/index.html");
    }

    public class AndroidBridge {
        @JavascriptInterface
        public boolean hasLocalMedia(String videoId) {
            return localMediaStore.findMediaFile(videoId) != null;
        }

        @JavascriptInterface
        public void postMessage(String json) {
            try {
                JSONObject msg = new JSONObject(json);
                String type = msg.optString("type");
                switch (type) {
                    case "search":
                        executor.submit(() -> doSearch(msg)); break;
                    case "suggest":
                        executor.submit(() -> doSuggest(msg)); break;
                    case "fetchLyrics":
                        executor.submit(() -> doFetchLyrics(msg)); break;
                    case "downloadVideo":
                        executor.submit(() -> doDownloadVideo(msg)); break;
                    case "mediaState":
                        runOnUiThread(() -> {
                            if (mediaSessionHelper != null) mediaSessionHelper.update(msg);
                            // Update background playback state from JS report
                            boolean nowPlaying = msg.optBoolean("playing", false);
                            lastTitle    = msg.optString("title",    lastTitle);
                            lastArtist   = msg.optString("artist",   lastArtist);
                            lastPosition = msg.optDouble("position", lastPosition);
                            lastDuration = msg.optDouble("duration", lastDuration);
                            String vid   = msg.optString("videoId",  "");
                            if (!vid.isEmpty() && !vid.equals(lastVideoId)) {
                                // New track — evict old URL and start pre-fetching
                                lastVideoId = vid;
                                streamUrlCache.clear();      // 이전 곡 URL 은 필요 없음 (메모리/캐시 정리)
                                prefetchFailAt.clear();
                                prefetchStreamUrl(vid);
                                loadAlbumArt(vid, msg.optString("thumb", ""));
                            } else if (!vid.isEmpty() && !streamUrlCache.containsKey(vid)) {
                                prefetchStreamUrl(vid); // retry if previous fetch failed
                            }
                            // 앨범아트 로드가 실패했던 곡은 다음 상태 갱신 때 재시도 (곡당 1회 진행 중 요청만 허용)
                            if (!vid.isEmpty() && !vid.equals(lastArtVideoId) && !vid.equals(lastArtRequestedId)
                                    && android.os.SystemClock.elapsedRealtime() - lastArtAttemptMs > 30_000L) {
                                loadAlbumArt(vid, msg.optString("thumb", ""));
                            }
                            isPlaying  = nowPlaying;
                            lastStateAtMs = android.os.SystemClock.elapsedRealtime();
                            refreshServiceNotification();
                            if (nowPlaying) {
                                mainHandler.removeCallbacks(bgReleaseRunnable);
                                // 재생 중에는 WebView 를 항상 "보이는 상태"로 유지 → 백그라운드에서도 YouTube 가 멈추지 않음
                                if (bgWebView != null) bgWebView.setKeepVisible(true);
                                // 화면이 켜져 있는 동안엔 WakeLock/WifiLock 이 필요 없다 (발열·배터리 절약)
                                if (inBackground) acquireWakeLock();
                                if (!serviceStarted) startMusicService();
                                // 앱 종료 후 이어듣던 네이티브 재생이 남아 있다면 정리 (이중 재생 방지)
                                if (musicService != null) {
                                    if (musicService.isBgAudioActive()) musicService.stopBgAudio();
                                } else if (!serviceBindTried) {
                                    serviceBindTried = true;   // 매 상태 메시지마다 bind 를 반복하지 않는다
                                    try {
                                        bindService(new Intent(MainActivity.this, MusicService.class),
                                                serviceConnection, 0);
                                    } catch (Exception ignored) {}
                                }
                            } else {
                                // 곡 전환 중 잠깐의 정지 상태에서 락/가시성이 풀리지 않도록 유예 후 해제
                                mainHandler.removeCallbacks(bgReleaseRunnable);
                                mainHandler.postDelayed(bgReleaseRunnable, BG_GRACE_MS);
                            }
                        });
                        break;
                    case "orientation":
                        String orient = msg.optString("value", "sensor");
                        runOnUiThread(() -> setOrientation(orient)); break;
                    default: break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "postMessage parse error", e);
            }
        }
    }

    private void sendToJs(JSONObject payload) {
        String b64 = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        // atob()는 Latin-1만 처리 → TextDecoder로 UTF-8 명시 디코딩
        String js =
            "(function(){" +
            "  var b=atob('" + b64 + "');" +
            "  var bytes=new Uint8Array(b.length);" +
            "  for(var i=0;i<b.length;i++) bytes[i]=b.charCodeAt(i);" +
            "  var s=new TextDecoder('utf-8').decode(bytes);" +
            "  window.__sync && window.__sync(s);" +
            "})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void setOrientation(String mode) {
        if ("landscape".equals(mode)) {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUI();
        } else {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            showSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        // 상태바 숨김 + 화면 회전이 겹치는 시점에 WebView가 옛 창 크기를
        // 그대로 들고 있는 경우가 있어, 한 프레임 뒤에 인셋 재적용을
        // 강제로 요청해 WebView의 100vh/100dvh 계산이 새 창 크기를
        // 정확히 반영하도록 한다.
        getWindow().getDecorView().post(() ->
                getWindow().getDecorView().requestApplyInsets());
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null)
                c.show(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private void doSearch(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
            String URL = "https://www.youtube.com/youtubei/v1/search?key="
                       + KEY + "&prettyPrint=false";

            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20240101.00.00");
            client.put("hl", "ko");
            client.put("gl", "KR");
            JSONObject context = new JSONObject();
            context.put("client", client);
            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("query", query);
            // EgIQAQ== = 영상 검색 필터 (URL인코딩 제거)
            body.put("params", "EgIQAQ==");

            Request req = new Request.Builder()
                    .url(URL)
                    .post(RequestBody.create(
                            body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20240101.00.00")
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/")
                    .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IOException("HTTP " + resp.code());
                JSONArray tracks = parseSearchResults(readUtf8(resp));
                JSONObject result = new JSONObject();
                result.put("type", "searchResult");
                result.put("id", id);
                result.put("success", true);
                result.put("tracks", tracks);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "searchResult");
                err.put("id", id);
                err.put("success", false);
                err.put("error", e.getMessage());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private JSONArray parseSearchResults(String json) throws JSONException {
        JSONArray list = new JSONArray();
        JSONObject doc = new JSONObject(json);
        if (!doc.has("contents")) return list;

        JSONArray sections;
        try {
            sections = doc
                    .getJSONObject("contents")
                    .getJSONObject("twoColumnSearchResultsRenderer")
                    .getJSONObject("primaryContents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
        } catch (JSONException e) { return list; }

        for (int s = 0; s < sections.length() && list.length() < 20; s++) {
            JSONObject sec = sections.getJSONObject(s);
            if (!sec.has("itemSectionRenderer")) continue;
            JSONArray items = sec.getJSONObject("itemSectionRenderer")
                               .getJSONArray("contents");

            for (int k = 0; k < items.length() && list.length() < 20; k++) {
                JSONObject item = items.getJSONObject(k);
                if (!item.has("videoRenderer")) continue;
                JSONObject vr = item.getJSONObject("videoRenderer");
                String vid = vr.optString("videoId", "");
                if (vid.isEmpty()) continue;

                String title = extractText(vr, "title");
                String ch = extractText(vr, "ownerText");
                if (ch.isEmpty()) ch = extractText(vr, "shortBylineText");

                String durStr = "";
                try {
                    if (vr.has("lengthText"))
                        durStr = vr.getJSONObject("lengthText").optString("simpleText", "");
                } catch (JSONException ignored2) {}

                int dur = parseDur(durStr);
                // 과거에는 자체 키워드 휴리스틱(isMusicVideo)으로 결과를
                // 한 번 더 걸러냈지만, 이는 실제 유튜브에는 존재하는
                // 음악 영상이 SYNC 검색에는 나오지 않는 원인이었다.
                // params=EgIQAQ== 로 이미 "동영상" 타입으로 필터링된,
                // 즉 실제 유튜브 검색(동영상 필터)과 동일한 응답이므로
                // 유튜브가 반환한 순서/구성 그대로 전달한다.

                JSONObject t = new JSONObject();
                t.put("id", vid);
                t.put("title", title);
                t.put("channel", ch);
                t.put("dur", dur);
                t.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
                list.put(t);
            }
        }
        return list;
    }

    private String extractText(JSONObject vr, String key) {
        try {
            if (!vr.has(key)) return "";
            JSONObject obj = vr.getJSONObject(key);
            if (obj.has("runs"))
                return obj.getJSONArray("runs").getJSONObject(0).optString("text", "");
            return obj.optString("simpleText", "");
        } catch (JSONException e) { return ""; }
    }

    private int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600
                    + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60
                    + Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private void doSuggest(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String url = "https://suggestqueries.google.com/complete/search"
                    + "?client=firefox&ds=yt&q="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&hl=ko";
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 Firefox/124.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) throw new IOException("empty");
                String json = readUtf8(resp);
                if (json.startsWith("window."))
                    json = json.replaceFirst("^[^(]+\\(", "")
                               .replaceFirst("\\)\\s*$", "");
                JSONArray arr  = new JSONArray(json);
                JSONArray sugs = new JSONArray();
                if (arr.length() > 1) {
                    JSONArray inner = arr.getJSONArray(1);
                    for (int i = 0; i < inner.length() && sugs.length() < 8; i++) {
                        Object o = inner.get(i);
                        String sv = (o instanceof JSONArray)
                                ? ((JSONArray) o).optString(0, "") : o.toString();
                        if (!sv.isEmpty()) sugs.put(sv);
                    }
                }
                JSONObject result = new JSONObject();
                result.put("type", "suggestResult");
                result.put("id", id);
                result.put("success", true);
                result.put("suggestions", sugs);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "suggestResult");
                err.put("id", id);
                err.put("success", false);
                err.put("suggestions", new JSONArray());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private void doDownloadVideo(JSONObject msg) {
        String videoId = msg.optString("videoId");
        String id = msg.optString("id", "0");
        try {
            if (videoId.isEmpty()) throw new IOException("videoId 없음");

            File existing = localMediaStore.findMediaFile(videoId);
            if (existing != null) {
                JSONObject result = new JSONObject();
                result.put("type", "downloadResult");
                result.put("id", id);
                result.put("success", true);
                result.put("videoId", videoId);
                result.put("fileName", existing.getName());
                result.put("size", existing.length());
                sendToJs(result);
                return;
            }

            LocalMediaDownloader.download(
                    getApplicationContext(),
                    localMediaStore,
                    videoId,
                    percent -> {
                        try {
                            JSONObject prog = new JSONObject();
                            prog.put("type", "downloadProgress");
                            prog.put("videoId", videoId);
                            prog.put("percent", percent);
                            sendToJs(prog);
                        } catch (JSONException ignored) {}
                    });

            File saved = localMediaStore.findMediaFile(videoId);
            if (saved == null) throw new IOException("저장 파일 없음");

            JSONObject result = new JSONObject();
            result.put("type", "downloadResult");
            result.put("id", id);
            result.put("success", true);
            result.put("videoId", videoId);
            result.put("fileName", saved.getName());
            result.put("size", saved.length());
            sendToJs(result);
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + videoId, e);
            try {
                JSONObject err = new JSONObject();
                err.put("type", "downloadResult");
                err.put("id", id);
                err.put("success", false);
                err.put("videoId", videoId);
                err.put("error", e.getMessage() != null ? e.getMessage() : "다운로드 실패");
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private void doFetchLyrics(JSONObject msg) {
        String rawTitle = msg.optString("title");
        String channel  = msg.optString("channel");
        double dur      = msg.optDouble("duration", 0);
        String id       = msg.optString("id", "0");

        JSONArray lines = tryLrclib(rawTitle, channel, dur);
        if (lines == null) lines = tryNetEase(rawTitle, channel, dur);

        try {
            JSONObject result = new JSONObject();
            result.put("type", "lyricsResult");
            result.put("id", id);
            if (lines != null) {
                result.put("success", true);
                result.put("lines", lines);
            } else {
                result.put("success", false);
                result.put("lines", new JSONArray());
            }
            sendToJs(result);
        } catch (JSONException ignored) {}
    }

    // ════════════════════════════════════════════════════
    //  1차: lrclib.net — PC 버전 C# 동일 알고리즘 포팅
    // ════════════════════════════════════════════════════
    private JSONArray tryLrclib(String rawTitle, String channel, double ytDur) {
        try {
            String ct = cleanTitle(rawTitle);
            String ca = cleanArtist(channel);

            // ── 검색 쿼리 변형 목록 (PC 버전과 동일) ──
            String stripped = stripBrackets(ct);
            List<String> queries = new ArrayList<>();
            queries.add(ct + " " + ca);
            queries.add(ct);
            if (!stripped.equals(ct)) queries.add(stripped);
            if (!ca.isEmpty()) queries.add(ca + " " + ct);
            if (!ca.isEmpty() && !stripped.equals(ct)) queries.add(stripped + " " + ca);

            // synced 결과가 나올 때까지 순서대로 시도
            JSONArray results = new JSONArray();
            for (String q : queries) {
                JSONArray r = searchLrclib(q);
                if (hasSyncedResults(r)) { results = r; break; }
                // synced 없어도 결과가 있으면 후보로 보관
                if (results.length() == 0 && r.length() > 0) results = r;
            }

            if (results.length() == 0) return null;

            // ── 후보 채점 (PC 버전 동일 가중치) ──
            String bestLrc   = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);

                // syncedLyrics 우선, 없으면 plainLyrics도 시도
                String lrcText = item.optString("syncedLyrics", "");
                if (lrcText.isEmpty()) lrcText = item.optString("plainLyrics", "");
                if (lrcText.isEmpty()) continue;

                double lrcDur = getLrcLastTimestamp(lrcText);
                if (lrcDur <= 0) lrcDur = item.optDouble("duration", 0);

                double score = 0;
                if (ytDur > 0 && lrcDur > 0) {
                    double diff = Math.abs(lrcDur - ytDur);
                    if      (diff <= 3)  score += 50;
                    else if (diff <= 10) score += 35;
                    else if (diff <= 30) score += 15;
                    else if (diff <= 60) score += 5;
                    else                 score -= 25;
                }
                score += titleSim(ct, item.optString("trackName",  "")) * 30;
                score += titleSim(ca, item.optString("artistName", "")) * 20;
                // syncedLyrics 있으면 보너스
                if (!item.optString("syncedLyrics", "").isEmpty()) score += 10;

                if (score > bestScore) { bestScore = score; bestLrc = lrcText; }
            }
            return bestLrc != null ? parseLrc(bestLrc) : null;
        } catch (Exception e) { return null; }
    }

    private JSONArray searchLrclib(String query) {
        try {
            String url = "https://lrclib.net/api/search?q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "SYNCApp/1.0 (https://github.com/sync)")
                    .addHeader("Accept", "application/json")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return new JSONArray();
                Object parsed = new org.json.JSONTokener(readUtf8(resp)).nextValue();
                return parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
            }
        } catch (Exception e) { return new JSONArray(); }
    }

    private boolean hasSyncedResults(JSONArray arr) {
        if (arr == null) return false;
        try {
            for (int i = 0; i < arr.length(); i++)
                if (!arr.getJSONObject(i).optString("syncedLyrics", "").isEmpty())
                    return true;
        } catch (JSONException ignored) {}
        return false;
    }

    // ════════════════════════════════════════════════════
    //  2차: NetEase Cloud Music — 점수 하한 완화
    // ════════════════════════════════════════════════════
    private JSONArray tryNetEase(String rawTitle, String channel, double ytDur) {
        try {
            String ct = cleanTitle(rawTitle);
            String ca = cleanArtist(channel);

            // PC 버전과 동일한 쿼리 변형
            String stripped = stripBrackets(ct);
            List<String> queryList = new ArrayList<>();
            queryList.add(ct + " " + ca);
            queryList.add(ct);
            if (!stripped.equals(ct)) queryList.add(stripped);
            if (!ca.isEmpty()) queryList.add(ca + " " + ct);

            List<long[]>  ids    = new ArrayList<>();
            List<Double>  scores = new ArrayList<>();

            for (String q : queryList) {
                List<long[]>  tmpIds    = new ArrayList<>();
                List<Double>  tmpScores = new ArrayList<>();
                searchNetEase(q, ct, ca, ytDur, tmpIds, tmpScores);
                if (!tmpIds.isEmpty()) { ids = tmpIds; scores = tmpScores; break; }
            }
            if (ids.isEmpty()) return null;

            // 점수 내림차순 정렬
            Integer[] idx = new Integer[ids.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            final List<Double> fs = scores;
            Arrays.sort(idx, (a, b) -> Double.compare(fs.get(b), fs.get(a)));

            // 점수 하한 20으로 완화 (PC 버전 C#은 40이었으나 Android는 더 넉넉하게)
            for (int i = 0; i < Math.min(5, idx.length); i++) {
                if (fs.get(idx[i]) < 20) break;
                JSONArray lines = fetchNetEaseLrc(ids.get(idx[i])[0]);
                if (lines != null && lines.length() > 0) return lines;
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private void searchNetEase(String query, String ct, String ca,
            double ytDur, List<long[]> ids, List<Double> scores) {
        try {
            String url = "https://music.163.com/api/search/get?s="
                    + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&type=1&limit=10";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return;
                JSONObject doc = new JSONObject(readUtf8(resp));
                if (!doc.has("result")) return;
                JSONArray songs = doc.getJSONObject("result").optJSONArray("songs");
                if (songs == null) return;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    long   sid      = song.optLong("id");
                    String st       = song.optString("name", "");
                    double duration = song.optDouble("duration", 0) / 1000.0;
                    StringBuilder ab = new StringBuilder();
                    JSONArray art = song.optJSONArray("artists");
                    if (art != null)
                        for (int j = 0; j < art.length(); j++)
                            ab.append(art.getJSONObject(j).optString("name",""))
                              .append(" ");
                    double score = titleSim(ct, st) * 40
                                 + titleSim(ca, ab.toString().trim()) * 25;
                    if (ytDur > 0 && duration > 0) {
                        double diff = Math.abs(duration - ytDur);
                        if      (diff <= 3)  score += 30;
                        else if (diff <= 10) score += 18;
                        else if (diff <= 30) score += 8;
                        else                 score -= 15;
                    }
                    ids.add(new long[]{sid});
                    scores.add(score);
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONArray fetchNetEaseLrc(long songId) {
        try {
            String url = "https://music.163.com/api/song/lyric?id="
                    + songId + "&lv=1&kv=1&tv=-1";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return null;
                JSONObject doc = new JSONObject(readUtf8(resp));
                String lrc = null;
                if (doc.has("klyric"))
                    lrc = doc.getJSONObject("klyric").optString("lyric", null);
                if (lrc == null || lrc.isEmpty())
                    if (doc.has("lrc"))
                        lrc = doc.getJSONObject("lrc").optString("lyric", null);
                return (lrc != null && !lrc.isEmpty()) ? parseLrc(lrc) : null;
            }
        } catch (Exception e) { return null; }
    }

    // ══════════════════════════════════════════════════
    //  LRC 파서 — [mm:ss.xx] / [mm:ss.xxx] 모두 지원
    // ══════════════════════════════════════════════════
    private static final Pattern CREDIT_RX = Pattern.compile(
            "^\\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带" +
            "|OP|SP|厂牌|发行|监制|制作|ISRC|专辑|歌手)\\s*[：:].{0,80}$");

    // 2~3자리 소수 모두 허용: [mm:ss.xx] 또는 [mm:ss.xxx]
    private static final Pattern TS_RX = Pattern.compile(
            "\\[(\\d+):(\\d{2})[.:](\\d{2,3})\\]");

    private JSONArray parseLrc(String lrc) throws JSONException {
        List<double[]> times = new ArrayList<>();
        List<String>   texts = new ArrayList<>();

        for (String line : lrc.split("\n")) {
            String trimmed = line.trim();
            // 타임스탬프 전체 제거 후 텍스트 추출
            String textPart = trimmed
                    .replaceAll("\\[\\d+:\\d{2}[.:]\\d{2,3}\\]", "").trim();
            if (textPart.isEmpty() || CREDIT_RX.matcher(textPart).matches())
                continue;

            // 한 줄에 타임스탬프 여러 개 처리
            // 예: [00:12.34][00:45.67]가사텍스트
            Matcher scanner = TS_RX.matcher(trimmed);
            while (scanner.find()) {
                String msStr = scanner.group(3);
                // 2자리 = 1/100초, 3자리 = 1/1000초
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(scanner.group(1)) * 60.0
                         + Integer.parseInt(scanner.group(2)) + ms;
                times.add(new double[]{t});
                texts.add(textPart);
            }
        }

        // 시간순 정렬
        Integer[] idx = new Integer[times.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) ->
                Double.compare(times.get(a)[0], times.get(b)[0]));

        JSONArray result = new JSONArray();
        for (int i = 0; i < idx.length; i++) {
            int    ci    = idx[i];
            double start = times.get(ci)[0];
            double end   = (i + 1 < idx.length)
                    ? times.get(idx[i + 1])[0] : start + 5.0;
            if (end - start < 0.1) end = start + 0.5; // 최소 간격 보장
            JSONObject obj = new JSONObject();
            obj.put("start", start);
            obj.put("end",   end);
            obj.put("text",  texts.get(ci));
            result.put(obj);
        }
        return result;
    }

    // ══════════════════════════════════════════════════
    //  헬퍼
    // ══════════════════════════════════════════════════
    private double titleSim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        a = a.toLowerCase(); b = b.toLowerCase();
        if (a.equals(b)) return 1.0;
        if (b.contains(a) || a.contains(b)) return 0.85;
        String[] wa = a.split("[^\\w]+"), wb = b.split("[^\\w]+");
        Set<String> sa = new HashSet<>(), sb2 = new HashSet<>();
        for (String w : wa) if (w.length() > 1) sa.add(w);
        for (String w : wb) if (w.length() > 1) sb2.add(w);
        if (sa.isEmpty() || sb2.isEmpty()) return 0;
        long inter = sa.stream().filter(sb2::contains).count();
        return (double) inter / Math.max(sa.size(), sb2.size());
    }

    private String stripBrackets(String t) {
        return t.replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private double getLrcLastTimestamp(String lrc) {
        double last = 0;
        for (String line : lrc.split("\n")) {
            Matcher m = TS_RX.matcher(line.trim());
            while (m.find()) {
                String msStr = m.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(m.group(1)) * 60.0
                         + Integer.parseInt(m.group(2)) + ms;
                if (t > last) last = t;
            }
        }
        return last;
    }

    /** 제목 정제 — 영문 태그만 제거 (한글 제목 파괴 방지) */
    private String cleanTitle(String t) {
        final String TAG_RX =
            "(?i)official\\s*(?:music\\s*)?(?:video|audio|mv|lyric(?:s)?|visualizer)?" +
            "|\\bm/?v\\b" +
            "|\\bmusic\\s*video\\b" +
            "|\\baudio(?:\\s*only)?\\b" +
            "|\\blyrics?(?:\\s*(?:video|ver(?:sion)?))?\\b" +
            "|\\bvisualizer\\b" +
            "|\\blive(?:\\s+(?:performance|version|session))?\\b" +
            "|\\bperformance(?:\\s+video)?\\b" +
            "|\\b(?:hd|4k|1080p|720p)\\b" +
            "|\\bremaster(?:ed)?(?:\\s+version)?\\b" +
            "|\\bre-?upload\\b" +
            "|\\beng(?:lish)?\\s*(?:ver\\.?|version|sub(?:title)?s?)?\\b" +
            "|\\bkor(?:ean)?\\s*(?:ver\\.?|version)?\\b" +
            "|\\bjp(?:n)?\\s*(?:ver\\.?|version)?\\b";

        t = t.replaceAll("\\(\\s*(?:" + TAG_RX + ")[^)]*\\)", "").trim();
        t = t.replaceAll("\\[\\s*(?:" + TAG_RX + ")[^\\]]*\\]", "").trim();
        t = t.replaceAll("(?i)\\s*[-|]\\s*(?:" + TAG_RX + ")\\s*$", "").trim();
        t = t.replaceAll("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+.+$", "").trim();
        t = t.replaceAll("[\\u2013\\u2014]+", "-").trim();
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    private String cleanArtist(String c) {
        c = c.replaceAll("(?i)\\s*[-–]\\s*Topic\\s*$", "").trim();
        c = c.replaceAll("(?i)VEVO$", "").trim();
        c = c.replaceAll(
                "(?i)\\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\\s*$",
                "").trim();
        return c.replaceAll("\\s{2,}", " ").trim();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ── CRITICAL: NEVER call webView.onPause() ───────────────────────────
        // webView.onPause() suspends ALL JavaScript execution, which kills the
        // YouTube IFrame player immediately. We keep JS running at all times.
        //
        // 백그라운드 재생 구성:
        //  1) BgWebView.setKeepVisible(true) : WebView 가 창이 계속 보인다고 인식 → YouTube iframe 이
        //     document.visibilityState 'hidden' 을 감지해 스스로 멈추는 현상 차단
        //  2) Foreground Service(+알림, MediaSession) : 프로세스 유지
        //  3) WakeLock / WifiLock : 화면이 꺼져도 CPU·네트워크 유지
        inBackground = true;
        webView.evaluateJavascript("window.__syncInBg=true;", null);
        // 화면용 작업(캔버스 루프/애니메이션/짧은 주기 타이머)을 멈춰 백그라운드 CPU·GPU 사용을 없앤다
        webView.evaluateJavascript("window.__syncBgMode&&window.__syncBgMode(true);", null);
        if (isPlaying && bgWebView != null) bgWebView.setKeepVisible(true);
        if (isPlaying || !lastVideoId.isEmpty()) {
            // 일시정지 상태여도 알림을 남겨 두어 알림/이어폰에서 다시 재생할 수 있게 한다.
            startMusicService();
        }
        if (isPlaying) {
            acquireWakeLock();
            // 네이티브 MediaPlayer 보조 재생은 WebView 렌더러가 죽었을 때(onRenderProcessGone)나
            // 앱 종료 시(onDestroy)에만 쓴다. 여기서 같이 켜면 소리가 이중으로 난다.
            // 단, 그 경우를 위해 스트림 URL 은 미리 받아 둔다.
            if (!lastVideoId.isEmpty()) prefetchStreamUrl(lastVideoId);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // Tell JS we're back in foreground so auto-resume stops triggering
        inBackground = false;
        webView.evaluateJavascript(
                "window.__syncInBg=false;window.__userBgPause=false;", null);
        webView.evaluateJavascript("window.__syncBgMode&&window.__syncBgMode(false);", null);
        // Sync WebView player to native player position before stopping native
        if (musicService != null && musicService.isBgAudioActive()) {
            double bgPos = musicService.getBgPosition();
            musicService.stopBgAudio();
            if (bgPos > 0) {
                final double p = bgPos;
                // Seek YouTube IFrame player to where native audio was
                webView.evaluateJavascript(
                    "if(window.S&&S.ytPlayer&&S.ytReady){" +
                    "S.ytPlayer.seekTo(" + p + ",true);" +
                    "if(S.playing)S.ytPlayer.playVideo();}" , null);
            }
        } else if (musicService != null) {
            musicService.stopBgAudio();
        }
        releaseWakeLock();
        stopMusicServiceForeground();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        // Handle notification action buttons — set __userBgPause BEFORE calling
        // __mediaCmd so the onYtSt(PAUSED) handler knows the pause was user-initiated
        final String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case MusicService.ACTION_PLAY:
                webView.evaluateJavascript("window.__userBgPause=false;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('play')", null);
                break;
            case MusicService.ACTION_PAUSE:
                webView.evaluateJavascript("window.__userBgPause=true;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('pause')", null);
                break;
            case MusicService.ACTION_NEXT:
                webView.evaluateJavascript("window.__userBgPause=false;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('next')", null);
                break;
            case MusicService.ACTION_PREV:
                webView.evaluateJavascript("window.__userBgPause=false;", null);
                webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('prev')", null);
                break;
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        MusicService.commandSink = null;
        mainHandler.removeCallbacksAndMessages(null);
        // 앱을 닫는(스와이프/뒤로가기) 시점에 재생 중이면 네이티브 플레이어로 이어서 재생
        final boolean handoff = isPlaying && !isChangingConfigurations() && startNativeFallback(true);
        if (mediaSessionHelper != null) {
            mediaSessionHelper.release();
            mediaSessionHelper = null;
        }
        releaseWakeLock();
        try { unbindService(serviceConnection); } catch (Exception ignored) {}
        if (serviceStarted && !handoff) {
            stopService(new Intent(this, MusicService.class));
        }
        serviceStarted = false;
        executor.shutdown();
        webView.destroy();
    }

    // ── Background playback helpers ──────────────────────────────────────────

    private void startMusicService() {
        Intent intent = new Intent(this, MusicService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceStarted = true;
        } catch (Exception e) {
            // Android 12+: 백그라운드에서의 포그라운드 서비스 시작 제한 등
            Log.w(TAG, "startMusicService failed", e);
            return;
        }
        // Bind so we can call updateNotification() on it
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopMusicServiceForeground() {
        if (musicService != null) {
            // STOP_FOREGROUND_DETACH (=2) keeps the notification visible but removes
            // the foreground binding — available from API 24 onward (our minSdk is 26).
            // We use the raw int to avoid the API-33-only constant name.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                musicService.stopForeground(2 /* STOP_FOREGROUND_DETACH */);
            } else {
                //noinspection deprecation
                musicService.stopForeground(false);
            }
        }
    }

    /** 고화질 앨범아트를 백그라운드에서 받아 미디어 세션과 알림에 반영 */
    private void loadAlbumArt(final String vid, final String thumbUrl) {
        if (vid == null || vid.isEmpty()) return;
        lastArtRequestedId = vid;
        lastArtAttemptMs = android.os.SystemClock.elapsedRealtime();
        executor.submit(() -> {
            final android.graphics.Bitmap bmp = AlbumArtLoader.load(vid, thumbUrl);
            runOnUiThread(() -> {
                if (!vid.equals(lastVideoId)) return;   // 그 사이 다른 곡으로 바뀜
                if (bmp == null) { lastArtRequestedId = ""; return; }  // 실패 → 다음 갱신 때 재시도
                lastArt = bmp;
                lastArtVideoId = vid;
                if (mediaSessionHelper != null) {
                    mediaSessionHelper.setArt(vid, bmp);
                    // 현재 메타데이터를 아트와 함께 다시 설정
                    try {
                        org.json.JSONObject m = new org.json.JSONObject();
                        m.put("title", lastTitle);
                        m.put("artist", lastArtist);
                        m.put("position", lastPosition);
                        m.put("duration", lastDuration);
                        m.put("playing", isPlaying);
                        m.put("videoId", vid);
                        mediaSessionHelper.update(m);
                    } catch (Exception ignored) {}
                }
                refreshServiceNotification();
            });
        });
    }

    private void refreshServiceNotification() {
        if (musicService == null) return;
        musicService.setArt(lastVideoId.equals(lastArtVideoId) ? lastArt : null);
        musicService.updateNotification(
                lastTitle, lastArtist, isPlaying,
                mediaSessionHelper != null ? mediaSessionHelper.getSessionToken() : null);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SYNC::MusicWakeLock");
            wakeLock.setReferenceCounted(false);
        }
        // 타임아웃 갱신은 30분에 한 번이면 충분 (12시간 타임아웃) — 상태 메시지마다 다시 잡지 않는다
        final long now = android.os.SystemClock.elapsedRealtime();
        if (!wakeLock.isHeld() || now - wakeLockAtMs > 30 * 60 * 1000L) {
            wakeLock.acquire(12 * 60 * 60 * 1000L);
            wakeLockAtMs = now;
        }
        acquireWifiLock();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        wakeLockAtMs = 0;
        releaseWifiLock();
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                wifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SYNC::MusicWifiLock");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "wifi lock failed", e);
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    /** 알림/잠금화면/이어폰 명령을 WebView 로 전달 */
    private void dispatchMediaCommand(String cmd) {
        if (webView == null) return;
        // 일시정지 상태에서 재생/다음/이전이 오면 먼저 가시성을 복구해야 YouTube 가 재생을 받아들인다.
        if (!"pause".equals(cmd) && bgWebView != null) {
            mainHandler.removeCallbacks(bgReleaseRunnable);
            bgWebView.setKeepVisible(true);
            if (inBackground) acquireWakeLock();
        }
        // pause 는 사용자 의도임을 JS 에 알려 백그라운드 자동재개 로직이 되살리지 않게 한다.
        webView.evaluateJavascript(
                "window.__userBgPause=" + ("pause".equals(cmd) ? "true" : "false") + ";", null);
        webView.evaluateJavascript(
                "window.__mediaCmd&&window.__mediaCmd('" + cmd + "')", null);
    }

    /**
     * 네이티브 MediaPlayer 로 이어서 재생 (보조 수단).
     * @param keepServiceAlive true 면 액티비티 종료 후에도 서비스가 계속 재생하도록 알림을 갱신한다.
     * @return 시작에 성공했는지
     */
    private boolean startNativeFallback(boolean keepServiceAlive) {
        if (!isPlaying || lastVideoId.isEmpty() || musicService == null) return false;
        final String url = streamUrlCache.get(lastVideoId);
        if (url == null) return false;
        double pos = lastPosition;
        if (lastStateAtMs > 0) {
            pos += (android.os.SystemClock.elapsedRealtime() - lastStateAtMs) / 1000.0;
        }
        try {
            musicService.startBgAudio(url, pos);
            if (keepServiceAlive) {
                musicService.setArt(lastVideoId.equals(lastArtVideoId) ? lastArt : null);
                musicService.updateNotification(lastTitle, lastArtist, true, null);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "native fallback failed", e);
            return false;
        }
    }

    /**
     * YouTube iframe(cross-origin) 안에서도 document.hidden / visibilityState 가 항상 '보임'으로 보이도록
     * 문서 시작 시점에 스크립트를 주입한다. (BgWebView 의 가시성 유지와 이중 안전장치)
     * androidx.webkit 의 DOCUMENT_START_SCRIPT 를 지원하지 않는 환경에서는 조용히 건너뛴다.
     */
    private void installBackgroundVisibilityScript() {
        final String script =
                "(function(){try{" +
                "var d=document,def=function(k,v){try{Object.defineProperty(d,k,{configurable:true,get:function(){return v;}});}catch(e){}};" +
                "def('hidden',false);def('webkitHidden',false);" +
                "def('visibilityState','visible');def('webkitVisibilityState','visible');" +
                "var stop=function(e){e.stopImmediatePropagation();};" +
                "d.addEventListener('visibilitychange',stop,true);" +
                "d.addEventListener('webkitvisibilitychange',stop,true);" +
                "}catch(e){}})();";
        try {
            Set<String> origins = new HashSet<>(Arrays.asList(
                    "https://www.youtube.com",
                    "https://www.youtube-nocookie.com"));
            Class<?> compat = Class.forName("androidx.webkit.WebViewCompat");
            java.lang.reflect.Method m = compat.getMethod(
                    "addDocumentStartJavaScript", WebView.class, String.class, Set.class);
            m.invoke(null, webView, script, origins);
        } catch (Throwable t) {
            Log.i(TAG, "document-start visibility script not installed: " + t);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
    }


    // ── YouTube audio stream URL fetching ────────────────────────────────────
    // Uses YouTube's InnerTube API with the iOS client, which returns
    // pre-signed direct URLs (no signatureCipher decryption needed).
    // These URLs work directly with Android's MediaPlayer for ~6 hours.

    /**
     * Pre-fetch the audio stream URL for a videoId in the background.
     * Result is stored in streamUrlCache so onPause() can use it immediately.
     */
    private void prefetchStreamUrl(String videoId) {
        if (videoId == null || videoId.isEmpty()) return;
        if (streamUrlCache.containsKey(videoId)) return;
        // 이미 요청 중이면 중복 요청 금지, 실패했으면 30초 동안 재시도 금지
        // (상태 메시지가 초당 여러 번 오기 때문에 이 가드가 없으면 InnerTube 요청이 폭주한다)
        Long failedAt = prefetchFailAt.get(videoId);
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < 30_000L) return;
        if (!prefetchInFlight.add(videoId)) return;
        executor.submit(() -> {
            boolean ok = false;
            try {
                String url = fetchStreamUrlInnerTube(videoId);
                if (url != null && !url.isEmpty()) {
                    streamUrlCache.put(videoId, url);
                    ok = true;
                    Log.d(TAG, "Stream URL pre-fetched for: " + videoId);
                }
            } catch (Exception e) {
                Log.w(TAG, "Stream URL pre-fetch failed for: " + videoId, e);
            } finally {
                prefetchInFlight.remove(videoId);
                if (!ok) prefetchFailAt.put(videoId, android.os.SystemClock.elapsedRealtime());
                else prefetchFailAt.remove(videoId);
            }
        });
    }

    /**
     * Fetch a direct audio stream URL from YouTube's InnerTube API.
     *
     * Uses the iOS client (clientName=IOS) which returns pre-signed URLs
     * that do not require signature cipher decryption. Typical latency: ~300ms.
     *
     * @param  videoId YouTube video ID
     * @return Direct HTTPS audio URL, or null on failure
     */
    private String fetchStreamUrlInnerTube(String videoId) throws Exception {
        // Build InnerTube player request body
        JSONObject client = new JSONObject();
        client.put("clientName",    "IOS");
        client.put("clientVersion", "19.45.4");
        client.put("deviceModel",   "iPhone16,2");
        client.put("hl", "en");
        client.put("gl", "US");

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject body = new JSONObject();
        body.put("context",       context);
        body.put("videoId",       videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk",    true);

        String apiUrl = "https://www.youtube.com/youtubei/v1/player" +
                "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false";

        Request req = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(
                        body.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .addHeader("X-YouTube-Client-Name",    "5")
                .addHeader("X-YouTube-Client-Version", "19.45.4")
                .addHeader("User-Agent",
                        "com.google.ios.youtube/19.45.4 " +
                        "(iPhone; CPU iPhone OS 17_0 like Mac OS X)")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JSONObject doc = new JSONObject(readUtf8(resp));

            JSONObject sd = doc.optJSONObject("streamingData");
            if (sd == null) return null;

            // Prefer audio-only adaptive format (m4a → best MediaPlayer compat)
            JSONArray adaptive = sd.optJSONArray("adaptiveFormats");
            if (adaptive != null) {
                String bestUrl     = null;
                int    bestBitrate = 0;
                for (int i = 0; i < adaptive.length(); i++) {
                    JSONObject f    = adaptive.getJSONObject(i);
                    String     mime = f.optString("mimeType", "");
                    String     url  = f.optString("url", "");
                    if (url.isEmpty()) continue; // skip if signatureCipher needed
                    if (!mime.startsWith("audio/")) continue;
                    int bitrate = f.optInt("bitrate", 0);
                    // Prefer m4a (mp4a codec) over webm for MediaPlayer compatibility
                    boolean isM4a    = mime.startsWith("audio/mp4");
                    boolean bestIsM4a = bestUrl != null &&
                            (bestUrl.contains("audio/mp4") || bestBitrate > 128000);
                    if (bestUrl == null || (isM4a && !bestIsM4a) ||
                            (isM4a == bestIsM4a && bitrate > bestBitrate)) {
                        bestBitrate = bitrate;
                        bestUrl     = url;
                    }
                }
                if (bestUrl != null) return bestUrl;
            }

            // Fallback: muxed format (video+audio, larger but widely supported)
            JSONArray formats = sd.optJSONArray("formats");
            if (formats != null) {
                for (int i = 0; i < formats.length(); i++) {
                    String url = formats.getJSONObject(i).optString("url", "");
                    if (!url.isEmpty()) return url;
                }
            }
        }
        return null;
    }

    /**
     * OkHttp는 Content-Type에 charset이 없으면 Latin-1로 읽음.
     * 한글 깨짐 방지를 위해 항상 명시적 UTF-8로 바이트를 읽는다.
     */
    private String readUtf8(Response resp) throws IOException {
        byte[] bytes = resp.body().bytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }


    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "window.__onAndroidBack && window.__onAndroidBack()", null);
    }
}
