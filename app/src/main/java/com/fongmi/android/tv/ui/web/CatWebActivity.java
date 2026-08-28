package com.fongmi.android.tv.ui.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.github.catvod.crawler.SpiderDebug;

/**
 * 猫源设置中心的内嵌浏览页。
 *
 * <p>猫源通过 {@code /msg} 的 {@code openInternalWebview} 请求宿主打开自己的配置站点
 * （CatPawOpen 的 {@code /website}）。原生宿主 CatVodApp 用 flutter_inappwebview 内嵌渲染，
 * 这里是等价实现——之前落到 {@code ACTION_VIEW} 跳外部浏览器，离开了 App。
 *
 * <p>与 {@link WebReaderActivity} 的关键差别：那个加载本地模板并挂 {@code AndroidReader}
 * JS 桥，因此必须阻断远程导航；这里正相反，要渲染远程页面，<b>所以绝不注册任何 JS 桥</b>——
 * addJavascriptInterface 是 WebView 级别的，一旦挂上就等于把 Java 方法交给页面脚本。
 */
public class CatWebActivity extends AppCompatActivity {

    /** 不带 TV- 前缀：SpiderDebug 自己会加，与 cat-msg / cat-source 保持一致。 */
    private static final String TAG = "cat-web";
    private static final String EXTRA_URL = "url";

    private WebView webView;
    private ProgressBar progress;
    private View loading;

    public static Intent intent(Context context, String url) {
        return new Intent(context, CatWebActivity.class).putExtra(EXTRA_URL, url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cat_web);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(url)) {
            finish();
            return;
        }

        // Android 13+ 手势返回与系统返回键都先让 WebView 回退，退到底再关页面
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        webView = findViewById(R.id.web_view);
        progress = findViewById(R.id.progress);
        loading = findViewById(R.id.loading);
        ((TextView) findViewById(R.id.loading_text)).setText(R.string.cat_web_opening);
        ((TextView) findViewById(R.id.address)).setText(url);

        configure();
        SpiderDebug.log(TAG, "open url=%s", url);
        webView.loadUrl(url);
    }

    /**
     * 设置页是 React 应用且从 CDN 取 React/axios，所以 JS、DOM storage 与联网都必需。
     * 不开文件访问：远程页面没有任何理由读本地文件。
     */
    private void configure() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // TV 上没有触摸，靠 D-pad 移动焦点；这两项让 WebView 参与焦点链
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setBackgroundColor(0xFF101216);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                if (progress == null) return;
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                SpiderDebug.log(TAG, "console [%s] %s (%s:%d)",
                        msg.messageLevel(), msg.message(), msg.sourceId(), msg.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(client());
    }

    private WebViewClient client() {
        return new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                SpiderDebug.log(TAG, "pageFinished url=%s", url);
                hideLoading();
                address(url);
            }

            /**
             * 页面内导航一律留在本页。设置站点会跳到自己的子路由，跳出去就等于回到「离开 App」那个毛病。
             * 非 http(s) 的 scheme（intent://、market:// 等）直接丢掉，不去唤起外部应用。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String target = request.getUrl() == null ? "" : request.getUrl().toString();
                if (target.startsWith("http://") || target.startsWith("https://")) return false;
                SpiderDebug.log(TAG, "blockScheme url=%s", target);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                SpiderDebug.log(TAG, "resourceError url=%s code=%d desc=%s main=%b",
                        request.getUrl(), error.getErrorCode(), error.getDescription(), request.isForMainFrame());
                // 只有主文档失败才值得打扰用户：CDN 里少一个资源不该弹提示
                if (!request.isForMainFrame()) return;
                hideLoading();
                com.fongmi.android.tv.utils.Notify.show(getString(R.string.cat_web_failed, error.getDescription()));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 不吞掉的话整个进程会被系统连坐杀掉
                SpiderDebug.log(TAG, "renderProcessGone crashed=%b", detail.didCrash());
                finish();
                return true;
            }
        };
    }

    private void address(String url) {
        TextView view = findViewById(R.id.address);
        if (view != null && !TextUtils.isEmpty(url)) view.setText(url);
    }

    private void hideLoading() {
        if (loading == null || loading.getVisibility() != View.VISIBLE) return;
        loading.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            loading.setVisibility(View.GONE);
            loading.setAlpha(1f);
        }).start();
    }

    /**
     * 兼容旧 API 的返回处理；新 API 走 {@code OnBackPressedDispatcher}。
     *
     * <p>不拦 {@code onKeyDown}：dispatcher 本来就会收到返回键，在 key-down 再调一次会让
     * 按住返回键的重复事件连续触发回退。参照 {@code WebReaderActivity} 的做法。
     */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                // 先摘出 view 树再 destroy，否则仍挂在父容器上销毁会告警/泄漏
                android.view.ViewParent parent = webView.getParent();
                if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }
}
