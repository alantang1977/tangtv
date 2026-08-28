package com.fongmi.android.tv.ui.web;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/**
 * 锁定「猫源设置中心在 App 内打开」的接线。
 *
 * <p>猫源通过 {@code /msg} 的 {@code openInternalWebview} 请求宿主开网页。原先落到
 * {@code ACTION_VIEW} 跳外部浏览器，同时详情页还会打开一个空白播放页。这两处都容易在重构里
 * 被改回去，所以用源码断言钉住。
 */
public class CatWebWiringTest {

    @Test
    public void catWebviewPrefersInAppActivity() throws IOException {
        String source = read("com/fongmi/android/tv/server/process/CatWebview.java");
        int open = source.indexOf("static void open(String url)");
        assertTrue("CatWebview 必须有 open", open >= 0);

        int inApp = source.indexOf("CatWebActivity.intent(", open);
        assertTrue("必须优先用内嵌 CatWebActivity 打开", inApp > open);

        int actionView = source.indexOf("Intent.ACTION_VIEW");
        assertTrue("ACTION_VIEW 只该出现在兜底路径里，不能是主路径",
                actionView > inApp || actionView < 0);
        assertTrue("兜底必须在独立的 external 方法里，且提示用户已离开 App",
                source.indexOf("private static void external(String url)") > 0
                        && source.indexOf("R.string.cat_web_external") > 0);
    }

    /**
     * 让位判定不得依赖时序信号。
     *
     * <p>详情结果会被缓存（命中时压根不调 spider，{@code /msg} 不会再发），且
     * {@code setDetail} 每次进入被投递两次（{@code singleTop} 加观察者重投）——
     * 「刚刚请求过内嵌页」这类标记在这两种情况下都会失配，表现为返回落到播放页、
     * 或再点一次直接进播放页。
     */
    @Test
    public void yieldDecisionDoesNotDependOnTiming() throws IOException {
        String action = read("com/fongmi/android/tv/api/CatAction.java");
        assertTrue("判定不得引入时间窗口", !action.contains("System.currentTimeMillis()"));
        assertTrue("判定不得消费一次性标记", !action.contains("consumeRecentRequest"));

        String webview = read("com/fongmi/android/tv/server/process/CatWebview.java");
        assertTrue("发起处也不该再打时序标记", !webview.contains("markRequested"));
    }

    /**
     * 开页不得排进主线程队列。
     *
     * <p>请求到达时详情页正在启动播放服务，主线程可能已排了好几秒（实测 27 秒），
     * {@code App.post} 会让设置页姗姗来迟，用户先盯着播放页。
     */
    @Test
    public void webviewLaunchesOffTheMainThreadQueue() throws IOException {
        String source = read("com/fongmi/android/tv/server/process/CatWebview.java");
        int open = source.indexOf("static void open(String url)");
        int start = source.indexOf("startActivity(", open);
        assertTrue("open 必须直接拉起", start > open);

        int post = source.indexOf("App.post(", open);
        assertTrue("拉起不得包在 App.post 里", post < 0 || post > start);
        assertTrue("用应用上下文就得带 NEW_TASK", source.indexOf("FLAG_ACTIVITY_NEW_TASK", open) > open);
    }

    /** 详情页要在开页请求到达时就退，不能等那份可能被堵住好几秒的 detail 结果。 */
    @Test
    public void detailYieldsOnRequestNotOnlyOnResult() throws IOException {
        String webview = read("com/fongmi/android/tv/server/process/CatWebview.java");
        assertTrue("开页后必须广播事件", webview.contains("CatWebEvent.post()"));
        // 退回系统浏览器时同样要让详情页退场，否则返回会撞上那个空白页
        int external = webview.indexOf("private static void external(String url)");
        assertTrue("兜底路径也要发事件",
                external >= 0 && webview.indexOf("CatWebEvent.post()", external) > external);

        String event = read("com/fongmi/android/tv/event/CatWebEvent.java");
        assertTrue("事件要带请求时刻，供详情页判断是不是自己触发的", event.contains("public boolean after(long"));

        for (String flavor : new String[]{"leanback", "mobile"}) {
            String source = readFlavor(flavor, "com/fongmi/android/tv/ui/activity/VideoActivity.java");
            int sub = source.indexOf("public void onCatWebEvent(");
            assertTrue(flavor + " 必须订阅开页事件", sub >= 0);
            assertTrue(flavor + " 必须用 detailStartTime 做归属判断，而不是固定时间窗",
                    source.indexOf("event.after(detailStartTime)", sub) > sub);
            assertTrue(flavor + " 命中要 finish", source.indexOf("finish();", sub) > sub);
        }
    }

    /**
     * 每个会进详情的入口都要让位。
     *
     * <p>首页点击在 TMDB 详情模式下走的是 TmdbDetailActivity，不是 VideoActivity——
     * 只改后者会让「返回落到详情页」在这条路上原样复现。这里按「谁调 detailContent 谁就得让位」
     * 来兜住所有入口。
     */
    @Test
    public void everyDetailEntryYields() throws IOException {
        String[] entries = {
                "main:com/fongmi/android/tv/ui/activity/TmdbDetailActivity.java",
                "leanback:com/fongmi/android/tv/ui/activity/VideoActivity.java",
                "mobile:com/fongmi/android/tv/ui/activity/VideoActivity.java",
        };
        for (String entry : entries) {
            String[] parts = entry.split(":", 2);
            String source = readFlavor(parts[0], parts[1]);
            assertTrue(parts[1] + "（" + parts[0] + "）必须订阅开页事件，否则从内嵌页返回会落回它",
                    source.contains("public void onCatWebEvent("));
            assertTrue(parts[1] + "（" + parts[0] + "）还要有结果兜底判定",
                    source.contains("CatAction.shouldYieldDetail("));
            // 兜底判定必须带上本次取详情的起始时刻，否则会误伤「真片源但详情为空」
            assertTrue(parts[1] + "（" + parts[0] + "）兜底判定必须传入 detail 起始时刻",
                    source.contains("shouldYieldDetail(getKey(), detailStartTime, result)")
                            || source.contains("shouldYieldDetail(key, loadStart, result)"));
        }
    }

    /** 缓存住「什么都没有」的详情会跳过 spider，副作用（请求开网页）就再也不发生。 */
    @Test
    public void blankDetailIsNotCached() throws IOException {
        String source = read("com/fongmi/android/tv/api/SiteApi.java");
        int store = source.indexOf("VodDetailCache.putContent(sourceKey, id, content)");
        assertTrue("SiteApi 必须有详情缓存写入", store >= 0);

        int guard = source.lastIndexOf("!CatAction.blank(result.getVod())", store);
        assertTrue("写缓存前必须排除「什么都没有」的详情", guard > 0 && guard < store);
    }

    @Test
    public void activityRegistersNoJavascriptBridge() throws IOException {
        String source = read("com/fongmi/android/tv/ui/web/CatWebActivity.java");
        // 查调用形式而非裸词：类注释里正解释着「为什么不挂 JS 桥」
        assertTrue("这个页面渲染远程页面，绝不能挂 JS 桥——addJavascriptInterface 是 WebView 级别的",
                !source.contains(".addJavascriptInterface("));
        assertTrue("JS 必须开：设置页是 React 应用", source.contains("setJavaScriptEnabled(true)"));
        assertTrue("远程页面没有理由读本地文件", source.contains("setAllowFileAccess(false)"));
    }

    @Test
    public void activityKeepsNavigationInside() throws IOException {
        String source = read("com/fongmi/android/tv/ui/web/CatWebActivity.java");
        int override = source.indexOf("shouldOverrideUrlLoading");
        assertTrue("必须接管页面内导航", override >= 0);
        assertTrue("http(s) 一律留在本页", source.indexOf("return false", override) > override);
        assertTrue("非 http(s) 的 scheme 要丢掉，不去唤起外部应用",
                source.indexOf("blockScheme", override) > override);
    }

    @Test
    public void activityBackGoesBackBeforeClosing() throws IOException {
        String source = read("com/fongmi/android/tv/ui/web/CatWebActivity.java");
        int back = source.indexOf("handleOnBackPressed");
        assertTrue("必须处理返回", back >= 0);
        assertTrue("先让 WebView 回退，退到底才关页面",
                source.indexOf("canGoBack()", back) > back && source.indexOf("goBack()", back) > back);
        // 拦 onKeyDown 会让「按住返回键」的重复事件连续触发回退
        assertTrue("不得在 onKeyDown 里再调一次 dispatcher",
                !source.contains("getOnBackPressedDispatcher().onBackPressed()"));
    }

    @Test
    public void bothFlavorsYieldDetailToWebview() throws IOException {
        for (String flavor : new String[]{"leanback", "mobile"}) {
            String source = readFlavor(flavor, "com/fongmi/android/tv/ui/activity/VideoActivity.java");
            int yield = source.indexOf("CatAction.shouldYieldDetail(getKey(), detailStartTime, result)");
            assertTrue(flavor + " 的 setDetail 必须先判断是否该让位给内嵌网页", yield >= 0);

            int setEmpty = source.indexOf("if (result.getList().isEmpty()) setEmpty(", yield);
            assertTrue(flavor + " 的让位判断必须在 setEmpty/setDetail 分派之前", setEmpty > yield);
            assertTrue(flavor + " 命中时要直接 finish 并 return，不能继续往下走",
                    source.indexOf("finish();", yield) > yield
                            && source.indexOf("finish();", yield) < setEmpty);

            // 等播放服务会让判定依赖的时间关联窗口过期，重投时反而露出空白页
            int pending = source.indexOf("mPendingDetail = result", yield - 600 < 0 ? 0 : yield - 600);
            if (pending >= 0) {
                assertTrue(flavor + " 的让位判断必须排在「等播放服务」的早退之前", yield < pending);
            }
        }
    }

    private static String read(String relative) throws IOException {
        return text(mainJava().resolve(path(relative)));
    }

    private static String readFlavor(String flavor, String relative) throws IOException {
        return text(srcRoot().resolve(Paths.get(flavor, "java")).resolve(path(relative)));
    }

    private static Path path(String relative) {
        return Paths.get(relative.replace('/', java.io.File.separatorChar));
    }

    private static String text(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path mainJava() {
        return srcRoot().resolve(Paths.get("main", "java"));
    }

    private static Path srcRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(Paths.get("app", "src"));
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("app/src not found from " + Paths.get("").toAbsolutePath());
    }
}
