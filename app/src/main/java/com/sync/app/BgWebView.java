package com.sync.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/**
 * 백그라운드 재생용 WebView.
 *
 * YouTube IFrame 플레이어는 페이지가 "보이지 않는 상태"(document.visibilityState === 'hidden')가 되면
 * 스스로 일시정지한다. WebView 의 페이지 가시성은 Activity 창의 가시성(홈 버튼, 화면 꺼짐, 다른 앱 전환 시
 * GONE/INVISIBLE 로 바뀜)을 그대로 따라가므로, 앱이 백그라운드로 가는 순간 재생이 멈춘다.
 *
 * 재생 중에는 창/뷰 가시성 변경을 항상 VISIBLE 로 전달해서 WebView 가 "투명한 창이 계속 화면에 떠 있는 것"과
 * 동일하게 인식하도록 한다. (SYSTEM_ALERT_WINDOW 같은 별도 권한이 필요 없음)
 *
 * 주의: webView.onPause() / pauseTimers() 는 절대 호출하지 말 것 (JS 와 미디어가 모두 멈춘다).
 */
public class BgWebView extends WebView {

    private volatile boolean keepVisible = false;

    public BgWebView(Context context) {
        super(context);
    }

    public BgWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BgWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** true: 실제 가시성과 무관하게 항상 보이는 상태로 취급 / false: 원래 동작 */
    public void setKeepVisible(boolean keep) {
        if (keepVisible == keep) return;
        keepVisible = keep;
        super.onWindowVisibilityChanged(keep ? View.VISIBLE : getWindowVisibility());
    }

    public boolean isKeepVisible() {
        return keepVisible;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(keepVisible ? View.VISIBLE : visibility);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, keepVisible ? View.VISIBLE : visibility);
    }
}
