package com.shzu.schedule;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.http.SslError;

import com.shzu.schedule.util.CryptoHelper;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 石河子大学课表 App
 *
 * 流程: CAS统一认证登录(含短信MFA二次验证) → 教务系统 → 探测课表入口 → 解析 → 展示
 * Cookie持久化 + 学号密码记忆 → 免频繁登录
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ScheduleFlow";

    // ====== 常量 ======
    private static final String CAS_LOGIN_URL =
        "https://authserver.shzu.edu.cn/authserver/login?service=https%3A%2F%2Fjwgl.shzu.edu.cn%2Fsso.jsp";
    private static final String JWGL_BASE = "https://jwgl.shzu.edu.cn";
    // 教务系统常见课表URL（仅作探测失败后的兜底猜测）
    private static final String[] SCHEDULE_URLS = {
        "/jsxsd/xskb/xskb_list.do",
        "/jsxsd/xskb/xskb_grsd.do",
        "/xskb/xskb_list.do",
        "/xsdjdkbd/xskb.do"
    };
    private static final int MAX_PARSE_RETRIES = 2;

    // ====== 开屏 ======
    /** 完整展示时长（logo 推近在同一段时间里完成） */
    private static final long SPLASH_HOLD_MS = 1800;
    /** 淡出时长 */
    private static final long SPLASH_FADE_MS = 500;
    /** 动画兜底收尾：比 停留+淡出 略晚一点，正常路径由 withEndAction 先收 */
    private static final long SPLASH_BACKSTOP_MS = SPLASH_HOLD_MS + SPLASH_FADE_MS + 300;
    /** 系统关闭动画时的静态停留时长 */
    private static final long SPLASH_NO_ANIM_MS = 700;
    /** 硬上限：即使首帧始终没来、动画也没跑，也必须在此时收起开屏层 */
    private static final long SPLASH_HARD_LIMIT_MS = 6000;

    /**
     * 设置图标：Material Design 官方 settings（filled）路径，24dp 网格。
     * 不用「⚙」字符（部分 ROM 字体缺字形），也不用线条版齿轮（22px 下齿太多显脏）。
     */
    private static final String GEAR_SVG =
        "<svg width='24' height='24' viewBox='0 0 24 24' fill='currentColor'>"
        + "<path d='M19.14 12.94c.04-.31.06-.62.06-.94s-.02-.63-.07-.94l2.03-1.58a.5.5 0 0 0"
        + " .12-.61l-1.92-3.32a.5.5 0 0 0-.59-.22l-2.39.96a7.1 7.1 0 0 0-1.62-.94l-.36-2.54"
        + "a.48.48 0 0 0-.48-.41h-3.84a.48.48 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94"
        + "l-2.39-.96a.5.5 0 0 0-.59.22L2.74 8.87a.5.5 0 0 0 .12.61l2.03 1.58c-.05.31-.09.63"
        + "-.09.94s.02.63.07.94l-2.03 1.58a.5.5 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39"
        + "-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36"
        + "-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.5.5 0 0 0"
        + "-.12-.61l-2.01-1.58zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z'/></svg>";

    // ====== UI ======
    private WebView webView;
    private android.widget.ImageView bgImageView;
    /** 顶部渐变遮罩：只在有背景图时显示，给状态栏图标和标题一点对比度 */
    private View topScrim;
    /** 底部手势条区域遮罩：页面盖住课表时把手势条后面的照片换成页面色调 */
    private View bottomCover;
    private View loadingOverlay;
    private View errorOverlay;
    private View splashOverlay;
    /** 开屏是否已起跑 / 是否已收起：两者都用于幂等，防止重复调度或重复收尾 */
    private boolean splashStarted = false;
    private boolean splashHidden = false;
    /** 构造时间戳，仅用于日志里衡量「首帧延迟」与「开屏停留时长」 */
    private long onCreateAt = System.currentTimeMillis();
    private TextView loadingText;
    private Button btnRetry;

    /**
     * 已应用的背景状态缓存。
     *
     * onResume() 每次从后台切回来都会调用 applyAppBackground()，而解码一张
     * 全尺寸背景图 + 重设根布局底色是主线程上的重活。切回前台时系统会先画
     * 窗口背景（windowBackground），若此时主线程正忙于解码，用户就会先看到
     * 一帧白底再变成课表 —— 即"切应用回来闪白"。
     *
     * 用这两个字段记住"当前已经应用了什么"，没变化就直接返回，既省掉解码也
     * 避免多余的 setBackgroundColor（每次调用都会触发一次重绘）。
     */
    private String appliedBgName = null;
    private boolean appliedDark = false;
    private boolean appliedOnce = false;

    // ====== 凭据 ======
    private SharedPreferences prefs;
    private String savedUser = "";
    private String savedPass = "";
    // 首次手动登录暂存凭据，到达教务系统后才落盘
    private String pendingUser = null;
    private String pendingPass = null;

    // ====== 登录流程 ======
    private boolean isAutoLogin = false;
    private long lastMfaToast = 0;

    // ====== 课表导航状态机 ======
    private boolean onSchedulePage = false;   // 当前已进入课表页(或猜测URL)
    private boolean cameFromLink = false;     // 通过页面链接进入课表页(区别于猜测)
    private int guessIdx = 0;                 // 下一个待尝试的猜测URL索引
    private int parseRetries = 0;             // 课表解析重试计数
    private int emptyScans = 0;              // 课表链接空扫描计数
    private int navToken = 0;                // 导航代际(取消过期回调)
    private boolean showingDisplay = false;   // 正在显示生成的课表HTML
    /**
     * 当前是否停留在自绘课表页（持久状态，与 showingDisplay 不同）。
     *
     * showingDisplay 只用于"跳过本次 onPageFinished 的解析"，页面加载完就立刻
     * 清掉了；而返回键需要知道"用户此刻是不是还在课表页"，所以必须单独记一份。
     * 切周/改布局都会重新 loadDataWithBaseURL 往 WebView 历史里压记录，
     * 只有靠这个标记才能避免返回键变成"回到上一周"。
     */
    private boolean onGeneratedSchedule = false;
    private boolean parseResultReceived = false; // parseSchedule JS是否已回调结果
    private ScheduleStore store;               // 课表本地存储
    private String latestParsedScheduleJson = "";
    private static final int REQ_EXPORT_CAPTURE = 7314;
    /** 标记「本次安装已做过旧数据扫描」，避免每次启动重复提示 */
    private static final String KEY_INSTALL_SCANNED = "install_legacy_scan_done";

    // ====== 自动更新 ======
    private UpdateManager updateManager;
    /** 最近一次从服务端拿到的更新信息（含公共内容），供下载与页面渲染复用 */
    private UpdateManager.Info lastUpdateInfo;
    private boolean updateChecking = false;
    private boolean updateDownloading = false;
    /** 页面点了「立即更新」但还没检查过：检查出结果后自动续上下载 */
    private boolean pendingAutoDownload = false;
    /** 保证本次进程只调度一次启动更新流程（startApp 会被重试按钮反复调用） */
    private boolean updateFlowStarted = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ====== 生命周期 ======

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 强制中文环境，确保 CAS 服务器返回中文页面
        Locale.setDefault(Locale.CHINA);
        Configuration config = getResources().getConfiguration();
        config.setLocale(Locale.CHINA);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        bgImageView = findViewById(R.id.bgImage);
        topScrim = findViewById(R.id.topScrim);
        bottomCover = findViewById(R.id.bottomCover);
        installInsetsListener();
        // WebView 透明：露出下层自定义背景图，切周重载时不会闪白
        webView.setBackgroundColor(0x00000000);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        loadingText = findViewById(R.id.loadingText);
        btnRetry = findViewById(R.id.btnRetry);
        splashOverlay = findViewById(R.id.splashOverlay);
        // 上次开着常驻通知就重新拉起服务（必须早于首屏 HTML 生成，
        // 否则设置页会按旧状态画出开关）
        restorePersistentNotify();

        prefs = getSharedPreferences("shzu_schedule", MODE_PRIVATE);
        savedUser = prefs.getString("username", "");
        // 密码走 Keystore 加密存储；兼容旧版明文并自动迁移
        savedPass = CryptoHelper.decrypt(prefs.getString("password_enc", ""));
        if (savedPass.isEmpty()) {
            String legacy = prefs.getString("password", "");
            if (!legacy.isEmpty()) {
                savedPass = legacy;
                prefs.edit().putString("password_enc", CryptoHelper.encrypt(legacy))
                    .remove("password").apply();
            }
        }

        btnRetry.setOnClickListener(v -> startApp());

        setupWebView();
        updateManager = new UpdateManager(this);
        // 开屏动画可关闭（设置→通用设置）：关闭后启动不再播动画，直接进入课表。
        //
        // 注意：**不能在这里直接起跑动画**。onCreate 之后同一根线程上还有
        // ScheduleStore 解析、generateWeekHTML 拼整页、创建隐藏 WebView 等重活，
        // 首帧可能被推迟 1~2 秒；若动画此刻已经在计时，第一帧画出来时动画早就
        // 播完、开屏层也已被移除 —— 用户看到的全程只有白屏，没有 logo。
        // 因此只登记「首帧之后再起跑」，见 armSplashAfterFirstFrame()。
        if (new ScheduleStore(this).isSplashAnimEnabled()) {
            armSplashAfterFirstFrame();
        } else {
            splashHidden = true;
            splashOverlay.setVisibility(View.GONE);
        }
        // 全新安装首次启动：先扫描旧版本残留数据，由用户决定是否清理，再进入启动流程
        if (!checkLegacyDataOnFirstRun()) startApp();
    }

    /**
     * 等窗口画出第一帧之后才开始开屏动画。
     *
     * 用一次性 OnPreDrawListener 拿「首帧即将提交」的时机，再 post 一次，
     * 让这一帧真正上屏；从那一刻开始计时，动画才不会被启动期的重活吃掉。
     *
     * 另外挂一个 6 秒硬兜底：万一某些 ROM 根本不触发 preDraw（比如窗口一直不可见），
     * 也必须保证开屏层最终会被收起，不能把用户挡在门外。
     */
    private void armSplashAfterFirstFrame() {
        handler.postDelayed(this::hideSplash, SPLASH_HARD_LIMIT_MS);
        try {
            final View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor == null) {
                playSplashAnimation();
                return;
            }
            decor.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        // 一次性：摘掉监听，避免每帧回调
                        android.view.ViewTreeObserver vto = decor.getViewTreeObserver();
                        if (vto.isAlive()) vto.removeOnPreDrawListener(this);
                        Log.d(TAG, "splash: first frame ready after "
                                + (System.currentTimeMillis() - onCreateAt) + "ms");
                        // post 到这一帧提交之后，保证「首帧 = 完整开屏」先被画出来
                        decor.post(MainActivity.this::playSplashAnimation);
                        return true;
                    }
                });
        } catch (Exception e) {
            Log.w(TAG, "arm splash failed, fallback to direct play", e);
            playSplashAnimation();
        }
    }

    /**
     * 开屏动画：整幅定帧 → logo 轻微推近 → 整体淡出。
     *
     * 为什么不再是原来的「logo 从 0 放大淡入 + 标题滑入」：
     * 那种做法要求动画在首帧之前就开始计时，元素必须先被置成透明；一旦首帧被
     * 启动期的重活（解析存储、拼整页 HTML、创建隐藏 WebView）推迟，第一帧就是
     * 一块白板 —— 这正是「不管开关都只有白屏、没有 logo」的成因。
     * 现在首帧就是完整画面（XML 里 alpha 均为 1），动画只做不会产生空帧的变化。
     *
     * 三处稳健化：
     * 1. 由 armSplashAfterFirstFrame() 在首帧之后触发，计时不含启动耗时；
     * 2. 系统关掉动画（animator duration scale = 0）时改成「静态停留 + 直接收」，
     *    不依赖 animator 是否真的会跑；
     * 3. 收尾统一走幂等的 hideSplash()，并额外挂 Handler 兜底，动画被取消也不会卡住。
     */
    private void playSplashAnimation() {
        if (splashStarted || splashHidden) return;
        splashStarted = true;
        Log.d(TAG, "splash: start at " + (System.currentTimeMillis() - onCreateAt)
                + "ms, animatorsEnabled=" + animatorsEnabled()
                + ", durationScale=" + animatorScale());
        try {
            android.widget.ImageView logo = findViewById(R.id.splashLogo);
            if (logo == null || splashOverlay == null) {
                hideSplash();
                return;
            }

            // 系统关闭动画：不赌 animator，静态展示一小段再收起
            //（否则会「一闪而过」，看起来跟没开一样）
            if (!animatorsEnabled()) {
                Log.d(TAG, "splash: animators disabled, static hold");
                handler.postDelayed(this::hideSplash, SPLASH_NO_ANIM_MS);
                return;
            }

            // 兜底：无论动画是否被系统取消，到点一定收尾（hideSplash 幂等）
            handler.postDelayed(this::hideSplash, SPLASH_BACKSTOP_MS);

            float px = (logo.getWidth() > 0 ? logo.getWidth() : logo.getMeasuredWidth()) / 2f;
            float py = (logo.getHeight() > 0 ? logo.getHeight() : logo.getMeasuredHeight()) / 2f;
            if (px > 0 && py > 0) {
                logo.setPivotX(px);
                logo.setPivotY(py);
            }
            logo.animate().scaleX(1.06f).scaleY(1.06f)
                .setDuration(SPLASH_HOLD_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

            splashOverlay.animate().alpha(0f)
                .setDuration(SPLASH_FADE_MS)
                .setStartDelay(SPLASH_HOLD_MS)
                .withEndAction(this::hideSplash)
                .start();
        } catch (Exception e) {
            Log.e(TAG, "splash animation error", e);
            hideSplash();
        }
    }

    /** 收起开屏层（幂等）：动画结束、Handler 兜底、硬超时都汇总到这里 */
    private void hideSplash() {
        if (splashHidden) return;
        splashHidden = true;
        try {
            if (splashOverlay != null) {
                splashOverlay.animate().cancel();
                splashOverlay.setVisibility(View.GONE);
            }
            Log.d(TAG, "splash: hidden at " + (System.currentTimeMillis() - onCreateAt) + "ms");
        } catch (Exception e) {
            Log.w(TAG, "hideSplash failed", e);
        }
    }

    /** 系统是否允许播放动画（开发者选项「动画时长缩放」被关、省电模式都可能返回 false） */
    private boolean animatorsEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return android.animation.ValueAnimator.areAnimatorsEnabled();
            } catch (Exception e) {
                return true;
            }
        }
        return true;
    }

    /** 动画时长缩放系数，仅用于日志诊断 */
    private float animatorScale() {
        try {
            return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Exception e) {
            return -1f;
        }
    }

    private static void addZipEntry(java.util.zip.ZipOutputStream zip, String name, String content) throws java.io.IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write((content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 与 onPause 的挂起配对：恢复 WebView 与它的定时器
        try {
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
        } catch (Exception ignored) {
        }
        applyAppBackground();
        // 同步桌面小组件：用户可能切了周/改了布局/刚同步完课表
        NextCourseWidgetProvider.refreshAll(this);
    }

    /**
     * 主动请求把「下一节课」小组件添加到桌面。
     *
     * 国产 ROM 的组件库经常不列出第三方组件（ColorOS 实测如此），
     * 用 requestPinAppWidget 直接弹系统确认框可以绕开组件库；
     * OPPO/小米/华为均支持，vivo 需接入原子组件平台才生效。
     */
    private void requestAddWidget() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Toast.makeText(this, "系统版本过低，请长按桌面手动添加小组件", Toast.LENGTH_LONG).show();
                return;
            }
            android.appwidget.AppWidgetManager mgr =
                android.appwidget.AppWidgetManager.getInstance(this);
            if (!mgr.isRequestPinAppWidgetSupported()) {
                Toast.makeText(this,
                    "当前桌面不支持一键添加，请长按桌面空白处，在卡片/小组件里搜索「石大课表」",
                    Toast.LENGTH_LONG).show();
                return;
            }
            android.content.ComponentName cn =
                new android.content.ComponentName(this, NextCourseWidgetProvider.class);
            boolean ok = mgr.requestPinAppWidget(cn, null, null);
            Log.d(TAG, "requestPinAppWidget -> " + ok);
            if (!ok) {
                Toast.makeText(this,
                    "未能发起添加，请长按桌面空白处，在卡片/小组件里搜索「石大课表」",
                    Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "requestAddWidget error", e);
            Toast.makeText(this,
                "添加失败，请长按桌面空白处，在卡片/小组件里搜索「石大课表」",
                Toast.LENGTH_LONG).show();
        }
    }

    /** 把主题底色与自定义背景图应用到 Activity 层（WebView 透明，切周不闪） */
    private void applyAppBackground() {
        try {
            ScheduleStore s = store != null ? store : new ScheduleStore(this);
            final boolean dark = "dark".equals(s.getTheme());
            final String bgName = s.getBgImage();

            // 背景与主题都没变 → 什么都不做。
            // 这是消除"切回前台闪白"的关键：避免在系统刚恢复窗口的那几帧里
            // 做解码与重绘。首次进来（appliedOnce=false）必须真正应用一次。
            if (appliedOnce && bgName.equals(appliedBgName) && dark == appliedDark) return;

            final View root = (View) webView.getParent();
            final int bgColor = dark ? 0xFF0F1016 : 0xFFFFFFFF;
            if (root != null && (!appliedOnce || dark != appliedDark)) {
                root.setBackgroundColor(bgColor);
            }
            // 同步窗口背景。切后台再切回时，系统在恢复我们的视图树之前会先画
            // 窗口背景这一帧；若它始终是主题里写死的白色，深色主题下切回来就会
            // 先闪一下白。in-app 主题切换不走系统 night mode，所以必须在这里
            // 手动同步（values-night 只覆盖跟随系统的情况）。
            // 状态栏与窗口背景同步：in-app 主题切换不走系统 night mode，
            // 主题 XML 里只能给静态默认值，深色主题下必须在这里手动改，否则顶部会留一条亮色带。
            if (!appliedOnce || dark != appliedDark) {
                try {
                    getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(bgColor));
                } catch (Exception ignored) {
                }
                applyStatusBar(dark);
            }

            java.io.File f = bgName.isEmpty() ? null : new java.io.File(getFilesDir(), bgName);
            if (f != null && f.exists()) {
                // 异步解码：BitmapFactory.decodeFile 是耗时操作，放在 onResume 的
                // 主线程上会直接拖住第一帧，表现为白屏一闪
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final android.graphics.Bitmap bmp =
                            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                        if (bmp == null) return;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                // 解码期间用户可能又换了背景，丢弃过期结果
                                String now = store != null ? store.getBgImage() : "";
                                if (!bgName.equals(now)) return;
                                bgImageView.setImageBitmap(bmp);
                                bgImageView.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                }, "bg-decode").start();
            } else {
                bgImageView.setImageDrawable(null);
                bgImageView.setVisibility(View.GONE);
            }

            // 背景图存在时才沉浸：状态栏和手势条后面也画图，不需要就没有必要改窗口结构
            boolean hasBgFile = f != null && f.exists();
            applyEdgeToEdge(hasBgFile, dark);
            // 顶部渐变：照片压在状态栏后面时，时间和信号往往读不出来，
            // 深色主题压一层黑、浅色主题压一层白，越往下越透明。
            if (topScrim != null) {
                if (hasBgFile) {
                    android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            dark ? new int[]{0x8C000000, 0x00000000}
                                 : new int[]{0x73FFFFFF, 0x00FFFFFF});
                    topScrim.setBackground(g);
                    topScrim.setVisibility(View.VISIBLE);
                } else {
                    topScrim.setVisibility(View.GONE);
                }
            }
            // 底部遮罩：颜色 = 页面玻璃的等效实色（0.82 白 / 0.72 深灰），
            // 叠在照片上与标签栏的玻璃观感一致；只在有背景图时存在
            if (bottomCover != null) {
                if (hasBgFile) {
                    bottomCover.setBackgroundColor(dark ? 0xB808080A : 0xD1FFFFFF);
                    bottomCover.setVisibility(View.VISIBLE);
                } else {
                    bottomCover.setVisibility(View.GONE);
                }
            }

            appliedBgName = bgName;
            appliedDark = dark;
            appliedOnce = true;
        } catch (Exception e) {
            Log.e(TAG, "applyAppBackground error", e);
        }
    }

    // ====== 通知栏常驻课程 ======

    private static final int REQ_NOTIFY_PERM = 2002;

    /** Android 13+ 通知需要运行时授权，13 以下默认有 */
    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 开关入口。askPermission=true 时会弹系统授权框，授权回调里再真正落地。
     *
     * 之所以不直接写配置就启动服务：没有通知权限时前台服务虽然能跑，
     * 但通知不会显示，用户会看到"开关是开的、通知栏里什么都没有"。
     */
    private void applyPersistentNotify(boolean enabled, boolean askPermission) {
        try {
            if (store == null) store = new ScheduleStore(this);
            if (enabled && !canPostNotifications()) {
                if (askPermission) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY_PERM);
                    return;
                }
                store.setNotifyPersist(false);
                Toast.makeText(this, "没有通知权限，无法常驻通知", Toast.LENGTH_SHORT).show();
                syncNotifySwitch();
                return;
            }
            store.setNotifyPersist(enabled);
            if (enabled) {
                startNotifyService();
            } else {
                stopNotifyService();
            }
            syncNotifySwitch();
        } catch (Exception e) {
            Log.w(TAG, "applyPersistentNotify failed", e);
        }
    }

    private void startNotifyService() {
        try {
            Intent i = new Intent(this, CourseNotifyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "startNotifyService failed", e);
        }
    }

    private void stopNotifyService() {
        try {
            stopService(new Intent(this, CourseNotifyService.class));
        } catch (Exception e) {
            Log.w(TAG, "stopNotifyService failed", e);
        }
    }

    /** 把开关的实际状态回写给设置页（页面上可能已经乐观翻转过了） */
    private void syncNotifySwitch() {
        if (webView == null) return;
        boolean on = store != null && store.isNotifyPersist();
        try {
            webView.evaluateJavascript("window.setNotifySwitch&&setNotifySwitch(" + (on ? "true" : "false") + ")", null);
        } catch (Exception ignored) {
        }
    }

    /**
     * 启动时恢复常驻通知。
     *
     * 进程被系统回收后通知会随进程消失，回到应用时得重新拉起；
     * 若用户在系统设置里撤掉了通知权限，这里顺手把开关关掉，
     * 避免出现"开关显示开着、通知栏却是空的"。
     */
    private void restorePersistentNotify() {
        try {
            if (store == null) store = new ScheduleStore(this);
            if (!store.isNotifyPersist()) return;
            if (!canPostNotifications()) {
                store.setNotifyPersist(false);
                return;
            }
            startNotifyService();
        } catch (Exception e) {
            Log.w(TAG, "restorePersistentNotify failed", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFY_PERM) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted) {
            applyPersistentNotify(true, false);
        } else {
            applyPersistentNotify(false, false);
            Toast.makeText(this, "没有通知权限，无法常驻通知", Toast.LENGTH_SHORT).show();
        }
    }

    // ====== 背景图铺到系统栏后面（沉浸式） ======

    /**
     * 监听系统栏高度。只有边缘到边缘时才把 insets 变成 WebView 的上下 margin，
     * 其余情况一律 return 原样，避免影响原有的安全区行为。
     */
    private void installInsetsListener() {
        try {
            final View dv = getWindow().getDecorView();
            dv.setOnApplyWindowInsetsListener((v, insets) -> {
                if (edgeToEdge) {
                    int top;
                    int bottom;
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets bars =
                                insets.getInsets(android.view.WindowInsets.Type.systemBars());
                        // 边缘到边缘之后系统不再因为键盘自动缩小窗口，
                        // 这里把键盘高度也算进下边距，否则输入框会被键盘直接盖住。
                        android.graphics.Insets ime =
                                insets.getInsets(android.view.WindowInsets.Type.ime());
                        top = bars.top;
                        bottom = Math.max(bars.bottom, ime.bottom);
                    } else {
                        // API 30 以下 getSystemWindowInsetBottom 本身就包含键盘高度
                        top = insets.getSystemWindowInsetTop();
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                    setWebSafeInsets(top, bottom);
                }
                return insets;
            });
            dv.requestApplyInsets();
        } catch (Exception e) {
            Log.w(TAG, "installInsetsListener failed", e);
        }
    }

    /**
     * 把系统栏高度转成 WebView 的外边距。
     *
     * 为什么用 margin 而不是给 WebView 加 padding：padding 不会裁剪内容，
     * WebView 滚动时页面会从 padding 区域里穿出来；margin 是把 WebView 整个
     * 缩小到安全区，页面 100vh 也跟着变，布局不会被拉伸。
     */
    private void setWebSafeInsets(int top, int bottom) {
        if (top == appliedInsetTop && bottom == appliedInsetBottom) return;
        appliedInsetTop = top;
        appliedInsetBottom = bottom;
        // 底部遮罩高度跟随导航条 inset：正好盖住 WebView 让出来的那段照片
        if (bottomCover != null) {
            ViewGroup.LayoutParams clp = bottomCover.getLayoutParams();
            clp.height = bottom;
            bottomCover.setLayoutParams(clp);
        }
        if (webView == null) return;
        try {
            ViewGroup.LayoutParams lp = webView.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.topMargin = top;
                m.bottomMargin = bottom;
                webView.setLayoutParams(m);
            }
        } catch (Exception e) {
            Log.w(TAG, "setWebSafeInsets failed", e);
        }
    }

    /**
     * 开关沉浸式。开：状态栏/导航条透明，窗口延伸到它们后面，背景图铺满整屏；
     * 关：恢复主题色状态栏与安全区布局（没有背景图时不需要露出什么）。
     */
    private void applyEdgeToEdge(boolean on, boolean dark) {
        try {
            android.view.Window w = getWindow();
            View dv = w.getDecorView();
            if (on) {
                if (Build.VERSION.SDK_INT >= 30) {
                    w.setDecorFitsSystemWindows(false);
                } else {
                    int f = dv.getSystemUiVisibility()
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                    dv.setSystemUiVisibility(f);
                }
                w.setStatusBarColor(0x00000000);
                w.setNavigationBarColor(0x00000000);
                if (Build.VERSION.SDK_INT >= 29) {
                    // 默认的系统会在这两条上垫一层半透明遮罩，背景图会被压灰
                    w.setNavigationBarContrastEnforced(false);
                }
            } else {
                if (Build.VERSION.SDK_INT >= 30) {
                    w.setDecorFitsSystemWindows(true);
                } else {
                    int f = dv.getSystemUiVisibility()
                            & ~(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                    dv.setSystemUiVisibility(f);
                }
                setWebSafeInsets(0, 0);
                applyStatusBar(dark);
                w.setNavigationBarColor(dark ? 0xFF000000 : 0xFFFFFFFF);
                if (Build.VERSION.SDK_INT >= 29) {
                    w.setNavigationBarContrastEnforced(true);
                }
            }
            edgeToEdge = on;
            // 切换后系统栏尺寸要重新算一遍（尤其从有背景图切到无背景图时）
            dv.requestApplyInsets();
        } catch (Exception e) {
            Log.w(TAG, "applyEdgeToEdge failed", e);
        }
    }

    // ====== 自定义图片背景（相册选图） ======

    /**
     * 状态栏配色：跟随页面底色，不再用品牌紫（原本是一条与页面割裂的色带）。
     * 浅色主题白底 + 深色图标，深色主题深底 + 浅色图标。
     */
    private void applyStatusBar(boolean dark) {
        try {
            android.view.Window w = getWindow();
            // 沉浸式下状态栏要透明（露出背景图），这里只负责图标明暗
            w.setStatusBarColor(edgeToEdge ? 0x00000000 : (dark ? 0xFF0F1016 : 0xFFFFFFFF));
            View dv = w.getDecorView();
            int flags = dv.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            dv.setSystemUiVisibility(flags);
        } catch (Exception e) {
            Log.w(TAG, "applyStatusBar failed", e);
        }
    }

    private static final int REQ_PICK_BG = 2001;

    private void pickBackgroundImage() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK_BG);
        } catch (Exception e) {
            Log.e(TAG, "pick bg failed", e);
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_BG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            saveBackgroundImage(data.getData());
        }
        if (requestCode == REQ_EXPORT_CAPTURE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            exportScheduleCapture(data.getData());
        }
    }

    private void exportScheduleCapture(android.net.Uri uri) {
        try (java.io.OutputStream raw = getContentResolver().openOutputStream(uri);
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raw)) {
            if (store == null) store = new ScheduleStore(this);
            addZipEntry(zip, "parsed-schedule.json", buildDiagnosticParsedJson());
            java.io.File html = new java.io.File(getFilesDir(), "latest_schedule_capture.html");
            if (html.exists()) {
                zip.putNextEntry(new java.util.zip.ZipEntry("jwgl-page.html"));
                try (java.io.FileInputStream in = new java.io.FileInputStream(html)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
            addZipEntry(zip, "stored-courses.json", store.getAllCourses().toString(2));
            Toast.makeText(this, "诊断数据已保存", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "export schedule capture failed", e);
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 诊断包中的解析结果。若本次会话没有真正执行解析（例如启动时直接读本地缓存渲染），
     * latestParsedScheduleJson 为空，此时用已存课表兜底，
     * 否则导出的诊断包会缺少最关键的那份数据（此前已出现过 0 字节的情况）。
     */
    private String buildDiagnosticParsedJson() {
        String parsed = latestParsedScheduleJson;
        if (parsed != null && !parsed.trim().isEmpty()) return parsed;
        try {
            JSONObject o = new JSONObject();
            o.put("courses", store != null ? store.getAllCourses() : new JSONArray());
            o.put("note", "session-parsed-empty: fallback to stored courses");
            o.put("totalWeeks", store != null ? store.getTotalWeeks() : 0);
            o.put("week1Monday", store != null ? store.getWeek1Monday() : 0);
            o.put("fallbackAt", System.currentTimeMillis());
            return o.toString(2);
        } catch (Exception e) {
            Log.w(TAG, "build diagnostic parsed json failed", e);
            return "";
        }
    }

    /**
     * 全新安装后的首次启动：扫描上一版本遗留的本地数据。
     * 换签名后覆盖安装会被系统拒绝，正常流程下用户必须先卸载；
     * 这里兜底处理「卸载不彻底 / 由备份恢复 / 应用克隆」等残留情况，
     * 避免旧的、可能不完整的课表继续被渲染，表现为漏课。
     *
     * @return true 表示已弹出确认框，由回调决定后续启动流程
     */
    private boolean checkLegacyDataOnFirstRun() {
        if (prefs == null || prefs.getBoolean(KEY_INSTALL_SCANNED, false)) return false;
        prefs.edit().putBoolean(KEY_INSTALL_SCANNED, true).apply();

        ScheduleStore probe = new ScheduleStore(this);
        boolean hasCourses = probe.hasData();
        boolean hasCredential = !prefs.getString("username", "").isEmpty()
                || !prefs.getString("password_enc", "").isEmpty()
                || !prefs.getString("password", "").isEmpty();
        boolean hasBg = hasLegacyBackground();
        boolean hasCapture = new java.io.File(getFilesDir(), "latest_schedule_capture.html").exists();
        if (!hasCourses && !hasCredential && !hasBg && !hasCapture) return false;

        StringBuilder detail = new StringBuilder();
        if (hasCourses) detail.append("· 旧课表数据\n");
        if (hasCredential) detail.append("· 已保存的账号密码\n");
        if (hasBg) detail.append("· 自定义背景图\n");
        if (hasCapture) detail.append("· 上次抓取的页面缓存\n");

        new AlertDialog.Builder(this)
                .setTitle("检测到旧版本残留数据")
                .setMessage("本次为全新安装，但发现上一版本留下的数据：\n\n"
                        + detail
                        + "\n旧课表可能不完整（表现为某些课程不显示、某些周缺课）。"
                        + "建议清理后重新登录获取最新课表。")
                .setPositiveButton("清理并重新获取", (d, w) -> {
                    purgeLegacyData();
                    startApp();
                })
                .setNegativeButton("保留现有数据", (d, w) -> startApp())
                .setCancelable(false)
                .show();
        return true;
    }

    /** 是否存在历史自定义背景图（文件名形如 user_bg_<时间戳>.jpg） */
    private boolean hasLegacyBackground() {
        java.io.File[] files = getFilesDir().listFiles();
        if (files == null) return false;
        for (java.io.File f : files) {
            if (f.getName().startsWith("user_bg_")) return true;
        }
        return false;
    }

    /** 清理旧版本残留：课表 / 外观 / 账号密码 / 背景图 / 抓取缓存 */
    private void purgeLegacyData() {
        try {
            new ScheduleStore(this).clearAll();
            prefs.edit().remove("username").remove("password_enc").remove("password").apply();
            savedUser = "";
            savedPass = "";

            java.io.File[] files = getFilesDir().listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    String n = f.getName();
                    if (n.startsWith("user_bg_") || "latest_schedule_capture.html".equals(n)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }

            // 让后续 startApp() 重新建 store，避免内存里还留着旧课表
            store = null;
            NextCourseWidgetProvider.refreshAll(this);
            Toast.makeText(this, "旧数据已清理，请重新登录", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "purge legacy data failed", e);
            Toast.makeText(this, "清理失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 自动更新 ====================

    /** 启动更新流程：只调度一次，两个动作都延迟到课表首屏之后执行 */
    private void startUpdateFlowOnce() {
        if (updateFlowStarted) return;
        updateFlowStarted = true;
        // 安装完成后的「更新内容」弹窗：等开屏/课表稳定后再弹
        handler.postDelayed(this::maybeShowPostUpdateLog, 1600);
        // 静默自动检查：24 小时节流，失败静默，不阻塞启动
        handler.postDelayed(this::maybeAutoCheckUpdate, 3200);
    }

    /** 本地 versionName */
    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 往课表页注入 JS（WebView 未就绪时静默忽略） */
    private void evalJs(final String js) {
        if (webView == null || js == null) return;
        handler.post(() -> {
            try {
                if (webView != null) webView.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    /** 生成 JS 字符串字面量（含 </script> 防护） */
    private static String jsString(String s) {
        if (s == null) s = "";
        return JSONObject.quote(s
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026"));
    }

    /**
     * 安装完成后的「更新内容」弹窗。
     *
     * 判定：检测到新版本时先把 changelog 暂存进 prefs；用户装完重启后，
     * 当前版本号与暂存的版本号一致 → 弹出该版本更新内容，然后清掉暂存，
     * 保证只弹一次。装到一半退出、或装了别的渠道包都不弹。
     */
    private void maybeShowPostUpdateLog() {
        if (store == null) return;
        final String cur = currentVersionName();
        if (cur.equals(store.getLogShownVersion())) return;

        final String pendingVer = store.getPendingUpdateVersion();
        final String log = store.getPendingUpdateLog();
        if (log.isEmpty()) {
            // 没有走本渠道更新（首次安装/其他来源）：只记录已见版本，不弹
            store.setLogShownVersion(cur);
            return;
        }
        if (!pendingVer.isEmpty() && !pendingVer.equals(cur)) {
            // 下载了但没安装，或装了别的版本：先不弹，等真正装上
            return;
        }
        store.setLogShownVersion(cur);
        store.clearPendingUpdateLog();
        // 新版本已经跑起来，临时安装包没有保留价值
        if (updateManager != null) updateManager.clearDownloadedApk();
        showPostUpdateLogDialog(cur, log);
    }

    /** 更新内容弹窗（安装完成后弹出） */
    private void showPostUpdateLogDialog(final String version, final String log) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle("已更新到 v" + version)
                    .setMessage(log)
                    .setPositiveButton("知道了", null)
                    .setCancelable(true)
                    .show();
        } catch (Exception e) {
            Log.w(TAG, "show post update log failed", e);
        }
    }

    /** 检查更新的三种场景 */
    private static final int UPD_AUTO = 0;    // 每次进入软件：静默检查，有更新才弹窗
    private static final int UPD_PAGE = 1;    // 每次进入「检查更新」页：静默刷新页面
    private static final int UPD_MANUAL = 2;  // 用户点「检查更新」：有提示

    /** 每次进入软件都检查一次更新（不再做 24 小时节流） */
    private void maybeAutoCheckUpdate() {
        checkUpdate(UPD_AUTO);
    }

    /**
     * 统一的更新检查。
     *
     * 触发时机有三个：每次启动（UPD_AUTO）、每次进入「检查更新」页（UPD_PAGE）、
     * 用户手动点检查（UPD_MANUAL）。三者只有「要不要打扰用户」不同，
     * 请求与结果处理完全一致，避免三份逻辑各写一遍而行为漂移。
     */
    private void checkUpdate(final int mode) {
        if (updateManager == null) return;
        if (updateChecking) {
            // 已经有一次检查在进行：进行中的那次会把结果推回页面，不必再发一次
            if (mode == UPD_MANUAL) {
                Toast.makeText(this, "正在检查，请稍候", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        updateChecking = true;
        if (mode != UPD_AUTO) evalJs("setUpdateLoading(true)");
        final String cur = currentVersionName();
        new Thread(() -> {
            final UpdateManager.Info info = updateManager.checkForUpdate(cur);
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateChecking = false;
                if (mode != UPD_AUTO) evalJs("setUpdateLoading(false)");
                if (info == null || info.httpCode == UpdateManager.ERR_NETWORK) {
                    // 断网/超时：不写时间戳，下次进入还会再试
                    Log.d(TAG, "update check failed: network unavailable");
                    if (mode == UPD_MANUAL) {
                        Toast.makeText(this, "检查失败：" + UpdateManager.httpErrorMessage(
                                info == null ? UpdateManager.ERR_NETWORK : info.httpCode),
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                store.setUpdateLastCheck(System.currentTimeMillis());
                if (!info.ok()) {
                    // 401 属于配置问题（密钥失效），只记日志不打扰用户
                    Log.w(TAG, "update check: HTTP " + info.httpCode + " " + info.error);
                    if (mode == UPD_MANUAL) {
                        String tip = info.isConfigError()
                                ? "更新服务密钥失效，请安装最新版本"
                                : "检查失败：" + UpdateManager.httpErrorMessage(info.httpCode);
                        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                // 公共内容（公告）随版本接口一起回来，有变化才落盘
                if (info.content != null && !info.content.equals(store.getUpdateLastContent())) {
                    store.setUpdateLastContent(info.content);
                }
                lastUpdateInfo = info;
                Log.d(TAG, "update check done: mode=" + mode + " latest=" + info.latestVersion
                        + " available=" + info.updateAvailable);
                // 页面正好开着就把最新数据推上去
                evalJs("renderUpdateInfo(" + updateInfoJson(info) + ")");
                if (mode == UPD_PAGE) {
                    // 就在「检查更新」页上，页面自己会显示状态，不必再弹窗或提示
                    return;
                }
                if (!info.updateAvailable) {
                    if (mode == UPD_MANUAL) {
                        Toast.makeText(this, "当前已是最新版本 v" + cur, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                showUpdateDialog(info, info.forceUpdate);
                // 用户先点了「立即更新」才发起检查：拿到结果直接续上下载
                if (mode == UPD_MANUAL && pendingAutoDownload) {
                    pendingAutoDownload = false;
                    startDownloadUpdate(info);
                }
            });
        }, "update-check-" + mode).start();
    }
    /**
     * 把 Info 序列化成**JS 对象字面量**（页面里直接当对象用：o.latest_version）。
     * 只需转义 &lt; &gt; &amp;，防止 changelog 里的标记提前闭合 &lt;script&gt;；
     * 不要再用 JSONObject.quote 包一层字符串 —— 那样注入的是字符串，
     * 页面取 o.xxx 全是 undefined，会渲染成空态。
     */
    private String updateInfoJson(UpdateManager.Info info) {
        if (info == null) return "null";
        try {
            JSONObject o = new JSONObject();
            o.put("update_available", info.updateAvailable);
            o.put("latest_version", info.latestVersion == null ? "" : info.latestVersion);
            o.put("changelog", info.changelog == null ? "" : info.changelog);
            o.put("content", info.content == null ? "" : info.content);
            o.put("force_update", info.forceUpdate);
            o.put("released_at", info.releasedAt == null ? "" : info.releasedAt);
            o.put("size", info.size);
            return o.toString()
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("&", "\\u0026");
        } catch (Exception e) {
            return "null";
        }
    }

    /**
     * 更新提示弹窗（旧版本收到更新信息时）。
     * 同时展示最新版更新内容与服务端公共内容；force_update 时不可取消、只能更新。
     */
    private void showUpdateDialog(final UpdateManager.Info info, final boolean force) {
        try {
            StringBuilder msg = new StringBuilder();
            if (info.changelog != null && !info.changelog.trim().isEmpty()) {
                msg.append("【更新内容】\n").append(info.changelog.trim()).append("\n");
            }
            if (info.content != null && !info.content.trim().isEmpty()) {
                if (msg.length() > 0) msg.append("\n");
                msg.append("【公告】\n").append(info.content.trim());
            }
            if (msg.length() == 0) {
                msg.append("暂无更新说明");
            }
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle("发现新版本 v" + info.latestVersion)
                    .setMessage(msg.toString())
                    .setPositiveButton("立即更新", (d, w) -> startDownloadUpdate(info))
                    .setNegativeButton(force ? null : "稍后", null);
            if (force) b.setCancelable(false);
            b.show();
            // 暂存更新内容：安装完成、下次启动时弹「更新内容」
            store.setPendingUpdateLog(info.latestVersion, info.changelog == null ? "" : info.changelog);
        } catch (Exception e) {
            Log.w(TAG, "show update dialog failed", e);
        }
    }

    /** 下载并安装更新包 */
    private void startDownloadUpdate(final UpdateManager.Info info) {
        if (updateManager == null || info == null) return;
        if (!info.ok()) {
            Toast.makeText(this, "更新信息无效，请重新检查更新", Toast.LENGTH_SHORT).show();
            return;
        }
        if (updateDownloading) {
            Toast.makeText(this, "正在下载，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!updateManager.canInstall()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("安装新版本需要允许本应用「安装未知应用」。点击去设置，打开后返回再点一次「立即更新」。")
                    .setPositiveButton("去设置", (d, w) -> updateManager.openInstallPermissionPage())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        updateDownloading = true;
        evalJs("setUpdateDownloading(true)");
        updateManager.downloadAndVerify(info, new UpdateManager.ProgressListener() {
            @Override
            public void onProgress(long done, long total) {
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                evalJs("setUpdateProgress(" + pct + ")");
            }

            @Override
            public void onFinished(java.io.File apk) {
                updateDownloading = false;
                evalJs("setUpdateDownloading(false)");
                Log.d(TAG, "update downloaded: " + apk.getAbsolutePath()
                        + " (" + apk.length() + " bytes)");
                boolean ok = updateManager.installApk(apk);
                if (!ok) {
                    Toast.makeText(MainActivity.this,
                            "已下载完成，但无法调起安装器", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailed(String reason) {
                updateDownloading = false;
                evalJs("setUpdateDownloading(false)");
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onCancelled() {
                updateDownloading = false;
                evalJs("setUpdateDownloading(false)");
                Toast.makeText(MainActivity.this, "已取消下载", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 取消下载（进度条上的「取消下载」按钮） */
    private void cancelDownloadUpdate() {
        if (updateManager != null) updateManager.cancelDownload();
        updateDownloading = false;
        evalJs("setUpdateDownloading(false)");
    }

    /** 读取相册选中的图片，压缩后存到应用私有目录，并应用到课表背景 */
    private void saveBackgroundImage(final android.net.Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) return;
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                    in.close();
                    if (bmp == null) return;
                    // 限制宽度，避免图片过大影响渲染
                    int maxW = 1080;
                    if (bmp.getWidth() > maxW) {
                        int h = (int) (bmp.getHeight() * (maxW / (float) bmp.getWidth()));
                        android.graphics.Bitmap scaled =
                            android.graphics.Bitmap.createScaledBitmap(bmp, maxW, h, true);
                        bmp.recycle();
                        bmp = scaled;
                    }
                    // 文件名带时间戳：URL 变化后 WebView 会重新加载，而不是用缓存
                    final String fileName = "user_bg_" + System.currentTimeMillis() + ".jpg";
                    java.io.File out = new java.io.File(getFilesDir(), fileName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                    fos.close();
                    bmp.recycle();

                    final String path = out.getAbsolutePath();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (store == null) store = new ScheduleStore(MainActivity.this);
                            // 删掉上一张背景图，避免残留占空间
                            String old = store.getBgImage();
                            if (!old.isEmpty() && !old.equals(fileName)) {
                                java.io.File f = new java.io.File(getFilesDir(), old);
                                if (f.exists()) f.delete();
                            }
                            store.setBgImage(fileName);
                            applyAppBackground();
                            webView.evaluateJavascript(
                                "applyBg('file://" + path + "')", null);
                            Toast.makeText(MainActivity.this, "背景已更新", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "save bg failed", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "设置背景失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        // 退到后台就不必再预热其余周次了（下次回到前台由 renderWeekSchedule 重新排入）
        handler.removeCallbacks(prewarmStep);
        prewarmQueue.clear();
        // 把主 WebView 也挂起：不挂起时它会在后台继续跑 CSS 动画、JS 定时器与合成，
        // 屏幕关着也照样吃 CPU/电量 —— 这是"待机也耗电"的常见来源。
        // 静默刷新用的是另一个隐藏 WebView，pauseTimers 会连它一起停，
        // 所以后台刷新进行中就不动定时器，只挂起主界面。
        try {
            if (webView != null) webView.onPause();
            if (!silentRunning && webView != null) webView.pauseTimers();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        // 预热是自续定时器（140ms 一步），Activity 销毁后若还在跑，
        // 会继续触碰已销毁的 WebView（loadDataWithBaseURL），必须先摘掉
        handler.removeCallbacks(prewarmStep);
        // 后台静默刷新用的隐藏 WebView 必须显式释放，否则会泄漏 Activity
        silentAbort("activity destroy");
        releaseStudentWv();
        // 教室查询用的隐藏 WebView 同理
        try {
            if (classroomWv != null) {
                ViewGroup p = (ViewGroup) classroomWv.getParent();
                if (p != null) p.removeView(classroomWv);
                classroomWv.destroy();
                classroomWv = null;
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 先问 JS 层：设置页/课程详情弹窗是否消费了本次返回
        webView.evaluateJavascript(
                "typeof handleBack==='function'?handleBack():'0'",
                value -> {
                    if (value != null && value.contains("1")) {
                        return; // JS 已关闭顶层页面，返回键到此为止
                    }
                    // 课表页是我们自己生成的 HTML，切周/改布局都会重新
                    // loadDataWithBaseURL，每次都会在 WebView 历史里压一条记录。
                    // 若此时还去 goBack()，返回键就会变成"回到上一周"而不是退出应用。
                    // 所以只要当前停留在自绘课表页，就直接交给系统处理（退出）。
                    if (onGeneratedSchedule) {
                        super.onBackPressed();
                        return;
                    }
                    // 仅 CAS 登录页 / 教务系统页面才回退历史
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        super.onBackPressed();
                    }
                });
    }

    // ====== WebView 初始化 ======

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // 页面是固定视口（viewport 里写死 initial-scale=1），不需要手势缩放：
        // 开着 zoom 会让 WebView 常驻一层缩放手势识别与合成层，纯属浪费。
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 允许 WebView 读取应用私有目录下的自定义背景图
        // 安全：页面不允许访问本地文件（自定义背景图由 Activity 层承载）
        s.setAllowFileAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setAllowContentAccess(false);
        // 移动端UA，确保CAS登录页按手机版布局渲染
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; Pixel 6) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        // 关掉点击时的蓝色/灰色高亮与长按反馈。
        // CSS 的 -webkit-tap-highlight-color 在部分 ROM 上不生效，这里从 View 层再关一次。
        webView.setHapticFeedbackEnabled(false);
        webView.setLongClickable(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsInterface(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                // 安全：只允许在本校域名内跳转，其余一律拦截，防止被重定向到外部站点
                if (isTrustedUrl(url)) return false;
                Log.w(TAG, "blocked external navigation: " + maskUrl(url));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handlePageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                super.onReceivedError(view, req, err);
                if (req.isForMainFrame()) {
                    handler.post(() -> showError());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 安全：只对校内域名的自签名证书放行，其余一律拒绝（防中间人）
                String url = error == null ? "" : error.getUrl();
                if (isTrustedUrl(url)) {
                    handler.proceed();
                } else {
                    Log.w(TAG, "blocked untrusted ssl: " + maskUrl(url));
                    handler.cancel();
                }
            }
        });
    }

    // ====== 启动逻辑 ======

    private void startApp() {
        hideError();
        hideLoading();
        // 重置导航状态
        onSchedulePage = false;
        cameFromLink = false;
        guessIdx = 0;
        parseRetries = 0;
        emptyScans = 0;
        showingDisplay = false;
        isAutoLogin = false;
        navToken++;

        store = new ScheduleStore(this);
        // 启动更新流程（只调度一次）：安装完成后的更新内容弹窗 + 每天一次的静默检查。
        // 挂在这里而不是 onCreate，是为了让 legacy 数据清理弹窗先走完，避免两个弹窗叠在一起。
        startUpdateFlowOnce();

        // 本地已存整学期课表 → 直接渲染今天所在周（离线秒开），
        // 随后在后台静默拉一次最新课表，有变化才弹窗询问
        if (store.hasData()) {
            int tw = store.todayWeek();
            Log.d(TAG, "startApp: local data ok, rendering week " + tw);
            renderWeekSchedule(tw > 0 ? tw : 1);
            // 静默刷新要 new 一个隐藏 WebView（几百毫秒的主线程开销）：
            // 开屏还在展示时做这件事会把推近动画卡住，所以等开屏收起后再开始；
            // 开屏被关掉时（splashHidden 已为 true）只需要让开首帧，700ms 足够。
            long bgDelay = splashHidden ? 700 : SPLASH_BACKSTOP_MS;
            handler.postDelayed(this::startBackgroundRefresh, bgDelay);
            // 姓名/学号在教务首页的「个人设置」iframe 里，课表页没有；
            // 升级安装后旧会话仍有效、不会经过首页，所以这里主动去首页抓一次。
            handler.postDelayed(() -> ensureStudentInfo(false), bgDelay + 600);
            return;
        }

        if (savedUser.isEmpty() || savedPass.isEmpty()) {
            // 没存过密码 → 直接显示CAS登录页
            loadLoginPage();
        } else {
            // 有存过的密码 → 直接访问教务系统
            // (会话活着→直达课表; 失效→重定向CAS→自动登录)
            Log.d(TAG, "startApp: saved creds, loading jwgl directly");
            showLoading(getString(R.string.fetching_schedule));
            loadJwgl(JWGL_BASE);
        }
    }

    private void loadLoginPage() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        webView.loadUrl(CAS_LOGIN_URL, headers);
    }

    /** 加载教务系统页面，统一带中文语言头 */
    private void loadJwgl(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        Log.d(TAG, "loadJwgl: " + url);
        webView.loadUrl(url, headers);
    }

    /** 兜底：依次尝试预设的课表URL猜测 */
    private void startGuessing() {
        onSchedulePage = true;
        cameFromLink = false;
        parseRetries = 0;
        emptyScans = 0;
        navToken++;
        if (guessIdx < SCHEDULE_URLS.length) {
            String url = JWGL_BASE + SCHEDULE_URLS[guessIdx];
            guessIdx++;
            Log.d(TAG, "startGuessing: trying " + url);
            showLoading(getString(R.string.fetching_schedule));
            loadJwgl(url);
        } else {
            Log.d(TAG, "startGuessing: all URLs exhausted");
            hideLoading();
            Toast.makeText(this, "未找到课表入口，请手动导航后重试", Toast.LENGTH_LONG).show();
        }
    }

    // ====== 页面加载处理 ======

    private void handlePageFinished(String url) {
        CookieManager.getInstance().flush();
        Log.d(TAG, "onPageFinished: " + url);

        // 自己生成的课表展示页，忽略
        if (showingDisplay) {
            showingDisplay = false;
            Log.d(TAG, "display page loaded, ignoring");
            return;
        }

        // 加载到了真实的网页（CAS 登录页 / 教务系统）→ 已离开自绘课表页，
        // 此后返回键应恢复为回退 WebView 历史
        onGeneratedSchedule = false;

        if (url.contains("authserver.shzu.edu.cn")) {
            // ====== CAS 域下（登录页/短信MFA验证页/其他）→ 检测页面类型 ======
            navToken++; // 取消教务导航的待执行回调
            detectCasPageType();

        } else if (url.contains("jwgl.shzu.edu.cn")) {
            // ====== 进了教务系统 ======
            isAutoLogin = false; // 登录成功

            // 只有真正到达教务系统才落盘凭据
            if (pendingUser != null && !pendingUser.isEmpty()) {
                savedUser = pendingUser;
                savedPass = pendingPass;
                prefs.edit().putString("username", savedUser)
                       .putString("password_enc", CryptoHelper.encrypt(savedPass))
                       .remove("password").apply();
                pendingUser = null;
                pendingPass = null;
            }

            // 顺手读一次学生资料（姓名/学号），只存本机；失败不影响下面任何流程
            try {
                webView.evaluateJavascript(buildStudentInfoJs(), null);
            } catch (Exception ignored) {
            }

            final int token = ++navToken;

            if (onSchedulePage) {
                // 课表页(或猜测URL)加载完毕 → 解析
                parseRetries = 0;
                parseResultReceived = false;
                showLoading(getString(R.string.loading));
                handler.postDelayed(() -> {
                    if (token == navToken) parseSchedule();
                }, 800);
                // 安全超时：parseSchedule JS 6秒内无回调 → 强制兜底
                handler.postDelayed(() -> {
                    if (token == navToken && !parseResultReceived && onSchedulePage) {
                        Log.d(TAG, "parse timeout (6s), forcing fallback");
                        if (guessIdx < SCHEDULE_URLS.length) {
                            String guessUrl = JWGL_BASE + SCHEDULE_URLS[guessIdx];
                            guessIdx++;
                            parseRetries = 0;
                            showLoading(getString(R.string.fetching_schedule));
                            loadJwgl(guessUrl);
                        } else {
                            hideLoading();
                            Toast.makeText(this, "课表加载超时，请点击刷新重试",
                                Toast.LENGTH_LONG).show();
                        }
                    }
                }, 6000);
            } else {
                // 教务首页 → 探测课表入口链接
                emptyScans = 0;
                showLoading(getString(R.string.fetching_schedule));
                handler.postDelayed(() -> {
                    if (token == navToken && !onSchedulePage) findScheduleLink();
                }, 600);
                // 兜底超时：探测一直无结果 → 转入URL猜测
                handler.postDelayed(() -> {
                    if (token == navToken && !onSchedulePage) {
                        Log.d(TAG, "link-scan timeout, falling back to guessing");
                        startGuessing();
                    }
                }, 12000);
            }
        } else {
            Log.d(TAG, "unhandled URL: " + url);
        }
    }

    /**
     * 检测CAS页面类型：login=账号密码登录页 / mfa=短信二次验证页 / captcha=带图片验证码 / unknown=其他
     * CAS判定WebView为不可信浏览器时，密码正确后返回短信MFA页，
     * 该页URL仍在authserver域名下，不能靠URL区分，必须检测页面DOM。
     */
    private void detectCasPageType() {
        webView.evaluateJavascript(buildCasTypeJs(), value -> {
            String type = value == null ? "unknown" : value.replace("\"", "").trim();
            Log.d(TAG, "CAS page type: " + type);
            handleCasPageType(type);
        });
    }

    /** CAS 页面类型探测脚本（主流程与后台静默刷新共用） */
    private String buildCasTypeJs() {
        return "(function(){"
            + "var pwd=document.getElementById('password');"
            + "if(pwd){"
            + "  var cap=document.getElementById('captchaDiv');"
            + "  if(cap&&!cap.classList.contains('hide')&&document.getElementById('captchaImg')){return 'captcha';}"
            + "  return 'login';"
            + "}"
            + "var dyn=document.getElementById('dynamicCode');"
            + "var t=(document.body.innerText||'').toLowerCase();"
            + "if(dyn||t.indexOf('验证码')>=0||t.indexOf('短信')>=0"
            + "||t.indexOf('sms')>=0||t.indexOf('verification')>=0){return 'mfa';}"
            + "return 'unknown';"
            + "})()";
    }

    private void handleCasPageType(String type) {
        switch (type) {
            case "login":
                // 重置课表导航状态（重新开始认证流程）
                onSchedulePage = false;
                cameFromLink = false;
                guessIdx = 0;
                emptyScans = 0;
                parseRetries = 0;
                showingDisplay = false;
                navToken++;

                if (!savedUser.isEmpty() && !savedPass.isEmpty() && !isAutoLogin) {
                    // 有保存的密码 → 自动登录（每轮会话只自动提交一次）
                    Log.d(TAG, "auto-login starting");
                    isAutoLogin = true;
                    showLoading(getString(R.string.logging_in));
                    handler.postDelayed(this::doAutoLogin, 500);
                } else {
                    hideLoading();
                    injectCredentialCapture();
                }
                break;
            case "mfa":
                // 短信二次验证页：绝不自动登录，交给用户手动完成
                hideLoading();
                long now = System.currentTimeMillis();
                if (now - lastMfaToast > 10000) {
                    lastMfaToast = now;
                    Toast.makeText(this, "账号需要短信验证码，请在页面中完成验证后自动继续",
                        Toast.LENGTH_LONG).show();
                }
                break;
            case "captcha":
                // 登录页弹出图片/滑块验证码 → 手动处理
                hideLoading();
                Toast.makeText(this, "需要验证码，请手动完成登录", Toast.LENGTH_LONG).show();
                break;
            default:
                // 账号激活/忘记密码等其他页面 → 不干预
                hideLoading();
                break;
        }
    }

    // ====== 自动登录 ======

    private void doAutoLogin() {
        webView.evaluateJavascript(buildAutoLoginJs(savedUser, savedPass), null);

        // 超时仅做UI提示；不重置isAutoLogin，防止在短信MFA验证页重复自动提交密码
        handler.postDelayed(() -> {
            String currentUrl = webView.getUrl();
            if (currentUrl != null && currentUrl.contains("authserver.shzu.edu.cn")) {
                Log.d(TAG, "auto-login timeout, hiding loading");
                hideLoading();
            }
        }, 10000);
    }

    /** CAS 自动填表登录脚本（主流程与后台静默刷新共用，凭据按 JSON 转义后拼入） */
    private String buildAutoLoginJs(String user, String pass) {
        return "javascript:(function(){"
            + "var d=document.getElementById('pwdLoginDiv');"
            + "if(d)d.style.display='block';"
            + "if(typeof showTabHeadAndDiv==='function')showTabHeadAndDiv('userNameLogin',1);"
            + "setTimeout(function(){"
            + "var u=document.getElementById('username');"
            + "if(u)u.value=" + org.json.JSONObject.quote(user) + ";"
            + "var p=document.getElementById('password');"
            + "if(p){p.removeAttribute('readonly');p.value=" + org.json.JSONObject.quote(pass) + ";}"
            + "var r=document.getElementById('rememberMe');"
            + "if(r)r.checked=true;"
            + "var b=document.getElementById('login_submit');"
            + "if(b)b.click();"
            + "},800);"
            + "})()";
    }

    // ====== 凭据捕获（首次登录） ======

    private void injectCredentialCapture() {
        String js = "javascript:(function(){"
            + "var btn=document.getElementById('login_submit');"
            + "if(btn){"
            + "  btn.addEventListener('click',function(){"
            + "    var u=document.getElementById('username');"
            + "    var p=document.getElementById('password');"
            + "    if(u&&p&&u.value&&p.value){"
            + "      Android.onLoginSubmitted(u.value,p.value);"
            + "    }"
            + "  },false);"
            + "}"
            + "})()";
        webView.evaluateJavascript(js, null);
    }

    // ====== 课表入口探测 ======

    /**
     * 在教务首页扫描课表链接。
     * 空结果(非过渡页)时1.6秒后复扫一次，两次都空 → 转入URL猜测。
     */
    /** 主流程：在教务首页扫描课表链接 */
    private void findScheduleLink() {
        webView.evaluateJavascript(buildFindLinkJs("Android"), null);
    }

    /** 课表入口探测脚本；bridge=回调用的 JS 接口名（主流程 Android / 静默刷新 AndroidSilent） */
    private String buildFindLinkJs(String bridge) {
        String js = "javascript:(function(){"
            + "try{"
            + "var found='';var linkCount=0;"
            + "var links=document.querySelectorAll('a,area');"
            + "linkCount=links.length;"
            // 跳过无效链接的通用判定
            + "function bad(l){"
            + "  if(!l)return true;"
            + "  var raw=l.getAttribute('href')||'';"
            + "  var h=l.href||'';"
            + "  if(!raw||raw==='#'||raw.charAt(0)==='#'||raw.indexOf('javascript')===0||h.length<5)return true;"
            + "  if(h.split('#')[0]===location.href.split('#')[0])return true;"
            + "  return false;"
            + "}"
            // 第一轮：href 含课表特征(xskb/kbcx)优先，最可靠
            + "for(var i=0;i<links.length;i++){"
            + "  if(bad(links[i]))continue;"
            + "  var h=links[i].href||'';"
            + "  if(h.indexOf('xskb')>=0||h.indexOf('kbcx')>=0){found=h;break;}"
            + "}"
            // 第二轮：文字含课表/日程(已过滤锚点)
            + "if(!found){"
            + "for(var i=0;i<links.length;i++){"
            + "  if(bad(links[i]))continue;"
            + "  var t=(links[i].textContent||'').trim();"
            + "  if(t.indexOf('课表')>=0||t.indexOf('日程')>=0||t.indexOf('课程表')>=0){found=links[i].href;break;}"
            + "}"
            + "}"
            // iframe扫描（同源）
            + "if(!found){"
            + "  try{var frames=document.querySelectorAll('iframe');"
            + "  for(var f=0;f<frames.length;f++){"
            + "    var fl=frames[f].contentWindow.document.querySelectorAll('a');"
            + "    linkCount+=fl.length;"
            + "    for(var i=0;i<fl.length;i++){"
            + "      var t=(fl[i].textContent||'').trim();"
            + "      var h=fl[i].href||'';"
            + "      var raw=fl[i].getAttribute('href')||'';"
            + "      if(!raw||raw==='#'||raw.charAt(0)==='#'||raw.indexOf('javascript')===0)continue;"
            + "      if(t.indexOf('课表')>=0||t.indexOf('日程')>=0||t.indexOf('课程表')>=0"
            + "      ||h.indexOf('xskb')>=0||h.indexOf('kbcx')>=0){found=h;break;}"
            + "    }"
            + "    if(found)break;"
            + "  }}catch(e){}"
            + "}"
            // 过渡页检测：重定向跳板（无内容无链接）→ 不算空结果，等跳转
            + "if(!found){"
            + "  var bodyText=(document.body.innerText||'').trim();"
            + "  if(bodyText.length<50&&linkCount<3){"
            + "    console.log('SCHED_SCAN: transition page, links='+linkCount);"
            + "    " + bridge + ".onScheduleLinkFound('TRANSITION');return;"
            + "  }"
            + "}"
            + "console.log('SCHED_SCAN: links='+linkCount+' found='+(found||'none')+' body='+(document.body.innerText||'').trim().length);"
            + bridge + ".onScheduleLinkFound(found);"
            + "}catch(e){console.log('SCHED_SCAN error: '+(e&&e.message?e.message:String(e)));" + bridge + ".onScheduleLinkFound('');}"
            + "})()";
        return js;
    }

    // ====== 课表解析 ======

    /**
     * 从教务页读取姓名与学号（只在本机保存，不上传）。
     *
     * 金智教务各校皮肤差异很大，这里按「常见 id/class → 正文正则」两级兜底：
     * 先试已知容器，再退化到“学号：xxx / 姓名：xxx / 欢迎您，xxx”这类文本。
     * 取不到就回调空串，不抛错、不影响课表解析。
     */
    /**
     * 从教务页读取姓名与学号（只在本机保存，不上传）。
     *
     * 实测（石大 jsxsd）：直接打开课表页时，文档里**没有任何**姓名/学号 ——
     * 学生信息在教务的框架（frameset/iframe）头部里，顶层 document 取不到。
     * 因此这里会递归扫描同源子框架，并在每个文档里按「已知容器 → 标签取值 → 正文正则」三级尝试。
     * 仍然取不到就回空串，不影响课表解析。
     */
    private String buildStudentInfoJs() {
        return "javascript:(function(){try{" + studentScanJsBody()
            + "scanDoc(document,0);"
            + "Android.onStudentInfo(JSON.stringify({name:name,sid:sid}));"
            + "}catch(e){try{Android.onStudentInfo('{}');}catch(e2){}}})()";
    }

    /**
     * 带重试的版本：iframe 是异步加载的，扫一次常常还太早（个人设置那个 iframe 还没到）。
     * 这里每隔 600ms 重扫一次，拿到姓名就停，最多试 maxTries 次。
     */
    private String buildStudentInfoRetryJs(int maxTries) {
        return "javascript:(function(){try{" + studentScanJsBody()
            + "var tries=0;"
            + "function run(){"
            + "  try{scanDoc(document,0);}catch(e){}"
            + "  if(name||tries>=" + maxTries + "){"
            + "    Android.onStudentInfo(JSON.stringify({name:name,sid:sid}));return;}"
            + "  tries++;setTimeout(run,600);}"
            + "run();"
            + "}catch(e){try{Android.onStudentInfo('{}');}catch(e2){}}})()";
    }

    /** 姓名/学号的扫描逻辑（供上面两种驱动复用） */
    private String studentScanJsBody() {
        return ""
            + "var name='',sid='';"
            + "function txt(el){return el?(el.textContent||'').replace(/\\s+/g,' ').trim():'';}"
            + "function pick(d,sel){try{return txt(d.querySelector(sel));}catch(e){return '';}}"
            // 校园表单里「姓名」后面常跟的是另一个字段名（性别/电话…），这些词不能当姓名
            + "var BAD=/^(性别|电话|手机|民族|政治|面貌|学院|专业|班级|年级|学号|姓名|出生|籍贯|地址|邮箱|"
            + "身份证|学制|层次|入学|考生|培养|辅导员|校区|照片|保存|修改|返回|打印)$/;"
            + "function cleanName(s){"
            + "  if(!s)return '';"
            + "  var r=(s||'').replace(/\\d{6,20}/g,' ')"
            + "    .replace(/欢迎[您]?|同学|老师|上午好|下午好|晚上好|您好|退出|登录|姓\\s*名|学\\s*号/g,' ')"
            + "    .replace(/[：:，,]/g,' ').trim();"
            + "  var m=r.match(/([\\u4e00-\\u9fa5]{2,4})/);"
            + "  if(!m)return '';"
            + "  return BAD.test(m[1])?'':m[1];"
            + "}"
            + "function parseMix(s){"
            + "  if(!s)return;"
            + "  if(!sid){var m=s.match(/(\\d{6,20})/);if(m)sid=m[1];}"
            + "  if(!name)name=cleanName(s);"
            + "}"
            // 个人信息表里姓名/学号通常在 <input value="...">，文本节点是空的
            + "function byInput(d){"
            + "  var ins=d.querySelectorAll('input');"
            + "  for(var i=0;i<ins.length;i++){"
            + "    var el=ins[i];var v=(el.value||'').trim();if(!v)continue;"
            + "    var k=((el.name||'')+' '+(el.id||'')+' '+(el.getAttribute&&el.getAttribute('title')||'')).toLowerCase();"
            + "    if(!name&&/(^|\\s)(xm|realname|username|xingming)(\\s|$)/.test(k)){"
            + "      var n=cleanName(v);if(n)name=n;}"
            + "    if(!sid&&/(^|\\s)(xh|sid|stuid|usercode|xuehao)(\\s|$)/.test(k)){"
            + "      var m=v.match(/(\\d{6,20})/);if(m)sid=m[1];}"
            + "  }"
            + "}"
            // 「姓名 | 张三」「学号 | 2026...」这种标签+取值的表格结构
            + "function byLabel(d){"
            + "  var els=d.querySelectorAll('td,th,span,div,label,b,strong,a,li');"
            + "  for(var i=0;i<els.length;i++){"
            + "    var raw=txt(els[i]);"
            + "    if(!raw||raw.length>8)continue;"
            + "    var t=raw.replace(/\\s/g,'');"
            + "    var isName=t.indexOf('姓名')>=0;"
            + "    var isId=t.indexOf('学号')>=0||t.indexOf('帐号')>=0||t.indexOf('账号')>=0;"
            + "    if(!isName&&!isId)continue;"
            + "    var v=txt(els[i].nextElementSibling);"
            + "    if(!v&&els[i].parentElement)v=txt(els[i].parentElement.nextElementSibling);"
            + "    if(isName){if(!name)name=cleanName(v);}"
            + "    else if(!sid){var m=(v||'').match(/(\\d{6,20})/);if(m)sid=m[1];}"
            + "  }"
            + "}"
            + "function scanDoc(d,depth){"
            + "  if(!d||depth>2)return;"
            + "  var sels=['#Top1_divLoginName','#session_user_name','#sessionUserKey','.userName',"
            + "    '#userName','.user-name','#Top1_trLoginName','.top-user','.user-info','.header-user',"
            + "    '.Nsb_top_menu_nc','#Top1_divTop'];"
            + "  for(var i=0;i<sels.length&&!(name&&sid);i++)parseMix(pick(d,sels[i]));"
            + "  byLabel(d);"
            + "  byInput(d);"
            + "  var body=txt(d.body);"
            + "  if(body){"
            + "    if(!sid){var m1=body.match(/(?:学号|帐号|账号)[:： ]?([0-9]{6,20})/);if(m1)sid=m1[1];}"
            // 必须带冒号：校园表单里「姓名」后面常常紧跟另一个字段名（性别/电话），
            // 少一个冒号就会把「电话」当成姓名。
            + "    if(!name){var m2=body.match(/(?:学生姓名|姓名)[:：]\\s*([\\u4e00-\\u9fa5]{2,4})/);if(m2)name=m2[1];}"
            + "    if(!name){var m3=body.match(/欢迎[您]?[，,:： ]*([\\u4e00-\\u9fa5]{2,4})/);if(m3)name=m3[1];}"
            + "  }"
            + "  var fs=d.querySelectorAll('iframe,frame');"
            + "  for(var j=0;j<fs.length;j++){"
            + "    var cd=null;try{cd=fs[j].contentDocument;}catch(e){}"
            + "    if(cd)scanDoc(cd,depth+1);"
            + "  }"
            + "}"
            ;
    }

    /** 主流程：解析当前教务页面课表 */
    private void parseSchedule() {
        webView.evaluateJavascript(buildParseJs("Android"), null);
        captureScheduleHtml();
    }

    /** 课表解析脚本；bridge=回调用的 JS 接口名（主流程 Android / 静默刷新 AndroidSilent） */
    private String buildParseJs(String bridge) {
        String js = "javascript:(function(){"
             + "var result={courses:[],error:null,currentWeek:0,totalWeeks:0,week1Monday:0,periodTimes:null};"
             + "result.sourceUrl=location.href;"
             + "result.fetchedAt=new Date().toISOString();"
             + "result.pageTitle=document.title||'';"
             + "result.tableStats={tables:document.querySelectorAll('table').length,rows:0,cells:0,kbcontent:document.querySelectorAll('div.kbcontent').length};"
            + "try{"
            // 当前周次/总周数探测（金智周次下拉框）
            + "var zc=document.querySelector('select#zc,select[name=zc]');"
            + "if(zc){"
            + "  var selV=zc.value||'';"
            + "  var v=parseInt(selV);"
            + "  if(v>0)result.currentWeek=v;"
            + "  if(zc.options&&zc.options.length>1)result.totalWeeks=zc.options.length-1;"
            + "}"
            // 从教学周历接口自动获取第1周周一日期
            + "try{"
            + "  var xhr=new XMLHttpRequest();"
            + "  xhr.open('GET','/jsxsd/jxzl/jxzl_query',false);"
            + "  xhr.send();"
            + "  var zlBody=xhr.responseText||'';"
            + "  var zlDates=zlBody.match(/20\\d{2}[年-]\\d{1,2}[月-]\\d{1,2}/g);"
            + "  if(zlDates&&zlDates.length>0){"
            // 跳过旧年份数据，取当前学年的第一个日期
            + "    var nowYear=new Date().getFullYear();"
            + "    var useDate=null;"
            + "    for(var zi=0;zi<zlDates.length;zi++){"
            + "      var yr=parseInt(zlDates[zi].substring(0,4));"
            + "      if(yr>=nowYear){useDate=zlDates[zi];break;}"
            + "    }"
            + "    if(!useDate)useDate=zlDates[zlDates.length-1];"
            + "    var dStr=useDate.replace(/[年月]/g,'-').replace(/日/g,'');"
            + "    var parts=dStr.split('-');"
             + "    if(parts.length>=3){"
             + "      var y=parseInt(parts[0]),m=parseInt(parts[1]),da=parseInt(parts[2]);"
             + "      result.week1Monday=new Date(y,m-1,da).getTime();"
             + "    }"
             // 用教学周历覆盖的日期范围推算学期总周数（比周次下拉框可靠，下拉框常带多余空周）
             + "    if(result.week1Monday>0){"
             + "      var maxTs=0;"
             + "      for(var zj=0;zj<zlDates.length;zj++){"
             + "        var zs=zlDates[zj].replace(/[年月]/g,'-').replace(/日/g,'');"
             + "        var zp=zs.split('-');"
             + "        if(zp.length>=3){"
             + "          var zt=new Date(parseInt(zp[0]),parseInt(zp[1])-1,parseInt(zp[2])).getTime();"
             + "          if(zt>maxTs)maxTs=zt;"
             + "        }"
             + "      }"
             + "      if(maxTs>result.week1Monday){"
             + "        var wks=Math.floor((maxTs-result.week1Monday)/(7*86400000))+1;"
             + "        if(wks>=6&&wks<=30)result.totalWeeks=wks;"
             + "      }"
             + "    }"
             + "  }"
             + "}catch(e){}"
            // 从页面提取节次时间表（教务课表每个大节th自带时间，如"第一二节 10:00-11:40"）：th标签 > 页面文本
            + "try{"
            + "  var ptRes=[];"
            + "  var ths=document.querySelectorAll('th');"
            + "  for(var thi=0;thi<ths.length;thi++){"
            + "    var tt=(ths[thi].textContent||'').trim();"
            + "    var tm=tt.match(/\\d{1,2}:\\d{2}\\s*[-–]\\s*\\d{1,2}:\\d{2}/);"
            + "    if(tm)ptRes.push(tm[0]);"
            + "  }"
            + "  if(ptRes.length<3){"
            + "    var pageTimes=((document.body.innerText||document.body.textContent)||'').match(/\\d{1,2}:\\d{2}\\s*[-–]\\s*\\d{1,2}:\\d{2}/g);"
            + "    if(pageTimes&&pageTimes.length>=5)ptRes=pageTimes.slice(0,10);"
            + "  }"
            + "  if(ptRes.length>=3)result.periodTimes=ptRes;"
            + "}catch(e){}"
            + "var table=document.querySelector('table.kbtable')"
            + "||document.querySelector('#kbtable')"
            + "||document.querySelector('#xskb_table')"
            + "||document.querySelector('.kbcjtable')"
             + "||document.querySelector('#timetable');"
            // 兜底：找行列数足够的最大表格
            + "if(!table){"
            + "  var tables=document.querySelectorAll('table');"
            + "  var best=null;var bestScore=0;"
            + "  for(var i=0;i<tables.length;i++){"
            + "    var tb=tables[i];"
            + "    var cols=tb.rows&&tb.rows[0]?tb.rows[0].cells.length:0;"
            + "    if(tb.rows.length>=3&&cols>=4){"
            + "      var score=tb.rows.length*cols;"
            + "      if(score>bestScore){bestScore=score;best=tb;}"
            + "    }"
            + "  }"
            + "  table=best;"
            + "}"
            + "if(!table||table.rows.length<2){"
            + "  result.error='no_table';"
            + "  " + bridge + ".onScheduleParsed(JSON.stringify(result));"
            + "  return;"
            + "}"
            + "var rows=table.querySelectorAll('tr');"
            + "var rowOffset=1;"
             + "if(rows.length>0){var first=(rows[0].textContent||'').replace(/\\s+/g,'');if(first.indexOf('周一')<0&&first.indexOf('星期一')<0&&first.indexOf('星期')<0)rowOffset=0;}"
             + "for(var i=rowOffset;i<rows.length;i++){"
             + "  var courseRow=i-rowOffset+1;"
            + "  var cells=rows[i].querySelectorAll('td');"
            + "  for(var j=0;j<cells.length&&j<7;j++){"  // td[0]=周一(节次标签是th不是td), j=0→周一, j=6→周日
            + "    var cell=cells[j];"
            // 金智课表：div.kbcontent 可见课程块（不含 kbcontent1 弹窗）
            + "    var kbcs=cell.querySelectorAll('div.kbcontent:not([class*=kbcontent1])');"
            + "    if(kbcs.length===0){kbcs=cell.querySelectorAll('div[class*=kbcontent]');}"
            + "    for(var kc=0;kc<kbcs.length;kc++){"
            + "      if(kbcs[kc].className.indexOf('kbcontent1')>=0)continue;"
            + "      if(kbcs[kc].style.display==='none')continue;"
            // 一个kbcontent内可能有多门课程（同一时段不同周次），需逐个提取
            + "      var fonts=kbcs[kc].querySelectorAll('font');"
            + "      var name='',teacher='',loc='',weeks='',remark='',cnt='',cls='';"
            + "      var inCourse=false;"
            + "      for(var f=0;f<fonts.length;f++){"
            + "        var ft=fonts[f];"
            + "        var title=ft.getAttribute('title')||'';"
            + "        var fname=ft.getAttribute('name')||'';"
             + "        var txt=(ft.textContent||'').trim();"
            + "        if(!txt)continue;"
            // 属性类标签：教师/教室/周次/备注等
            + "        if(fname==='jxlmc'||fname==='tzdbh'||fname==='wkxx'||fname==='xsks')continue;"
             + "        if(fname==='jxbz'||fname==='bzstr'){remark=txt.replace(/^教学备注[：:]*/,'').replace(/^备注[：:]*/,'').trim();continue;}"
             + "        if(fname==='ktmcstr'){cls=txt.replace(/^班级[：:]*/,'').trim();var cmb2=cls.match(/(\\d+)\\s*人/);if(cmb2)cnt=cmb2[1];continue;}"
             + "        if(title==='教师'||title==='老师'){teacher=txt;continue;}"
            + "        if(title.indexOf('周')>=0||title.indexOf('节次')>=0){weeks=txt;continue;}"
            + "        if(title==='教室'||title.indexOf('地点')>=0){loc=txt;continue;}"
            + "        if(title.indexOf('教学楼')>=0){continue;}"
            // 教学备注：去掉前缀（如"教学备注："），空内容显示时兜底
            + "        if(title.indexOf('备注')>=0){remark=txt.replace(/^教学备注[：:]*/,'').replace(/^备注[：:]*/,'').trim();continue;}"
            // 班级：去掉前缀，存为教学班信息；若含人数（如"XX班(125人)"）一并提取
            + "        if(title.indexOf('班级')>=0){"
            + "          cls=txt.replace(/^班级[：:]*/,'').trim();"
            + "          var cmb=cls.match(/(\\d+)\\s*人/);if(cmb)cnt=cmb[1];"
            + "          continue;"
            + "        }"
            + "        if(title.indexOf('通知')>=0){continue;}"
            + "        if(/\\d+\\s*-\\s*\\d+\\s*周/.test(txt)||/第\\d+周/.test(txt)||txt.indexOf('单周')>=0||txt.indexOf('双周')>=0){weeks=txt;continue;}"
            // 教室兜底：必须同时含数字，否则"实验室安全教育"这类课程名会被误当教室而丢失
             + "        if(!loc&&title===''&&txt.length<=16&&/\\d/.test(txt)&&/楼|室|区|馆|场|苑|厅|号|教/.test(txt)){loc=txt;continue;}"
            // 非属性类：可能是课程名或学时信息
            // 学时信息以(开头，如(讲课:56)，不是课程名
            // 学时信息兜底：只认 "(讲课:16)" / "讲课:16" 这类严格格式。
             // 旧写法用 contains('实验')/'实践'/'线上' 宽匹配，会把课程名含这些词的课整条丢弃
             + "        if(/^[（(].*[）)]$/.test(txt)||/(讲课|实践|实验|线上|其它)\\s*[:：]/.test(txt)){continue;}"
            // 新课程名：如果已有课程名，先保存当前课程
            + "        if(name){"
            + "          result.courses.push({"
            + "            day:j+1,row:courseRow,name:name,teacher:teacher,"
            + "            location:loc,weeks:weeks,remark:remark,count:cnt,cls:cls,"
            + "            text:kbcs[kc].textContent.replace(/\\s+/g,' ').trim().substring(0,80)"
            + "          });"
            + "          name=txt;teacher='';loc='';weeks='';remark='';cnt='';cls='';inCourse=true;"
            + "          continue;"
            + "        }"
            + "        name=txt;inCourse=true;"
            + "      }"
            // 保存最后一门课程
            + "      if(name&&inCourse){"
            + "        result.courses.push({"
            + "          day:j+1,row:courseRow,name:name,teacher:teacher,"
            + "          location:loc,weeks:weeks,remark:remark,count:cnt,cls:cls,"
            + "          text:kbcs[kc].textContent.replace(/\\s+/g,' ').trim().substring(0,80)"
            + "        });"
            + "      }"
            + "    }"
            // 兜底：无kbcontent时用旧方式提取
            + "    if(kbcs.length===0&&!cell.getAttribute('colspan')){"
            + "      var text=cell.textContent.trim();"
            + "      if(text&&text.length>2&&text!=='\\u00a0'){"
            + "        result.courses.push({"
            + "          day:j+1,row:courseRow,"
            + "          name:text.substring(0,20),teacher:'',"
            + "          location:'',weeks:'',"
            + "          text:text.replace(/\\s+/g,' ').substring(0,80)"
            + "        });"
            + "      }"
            + "    }"
            + "  }"
            + "}"
            + "if(result.courses.length===0)result.error='empty';"
            + "console.log('SCHED_PARSE: courses='+result.courses.length+' currentWeek='+result.currentWeek+' totalWeeks='+result.totalWeeks+' week1Monday='+result.week1Monday);"
            + bridge + ".onScheduleParsed(JSON.stringify(result));"
            + "}catch(e){"
            + "result.error='js_exception:'+(e&&e.message?e.message:String(e));"
            + "console.log('SCHED_PARSE exception: '+result.error);"
            + bridge + ".onScheduleParsed(JSON.stringify(result));"
            + "}"
            + "})()";
        return js;
    }

    /** 缓存当前教务页面 DOM 原文，用于排查页面结构变化导致的漏解析 */
    private void captureScheduleHtml() {
        capturePageHtml("latest_schedule_capture.html");
    }


    /**
     * 把当前页面 DOM 存到 files/<fileName>，用于事后排查。
     * 除了课表页，教务首页也会存一份：学生姓名/学号到底在哪个节点、哪个框架里，
     * 只能靠真实页面确认，猜选择器没有意义。
     */
    private void capturePageHtml(final String fileName) {
        webView.evaluateJavascript("(function(){try{return btoa(unescape(encodeURIComponent(document.documentElement.outerHTML||'')))}catch(e){return ''}})()", value -> {
            if (value == null || "null".equals(value) || "\"\"".equals(value)) return;
            try {
                String encoded = value;
                if (encoded.startsWith("\"") && encoded.endsWith("\""))
                    encoded = encoded.substring(1, encoded.length() - 1);
                if (encoded.length() > 12_000_000) encoded = encoded.substring(0, 12_000_000);
                byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                java.io.File f = new java.io.File(getFilesDir(), fileName);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(bytes); }
            } catch (Exception e) { Log.w(TAG, "page capture failed: " + fileName, e); }
        });
    }

    // ====== 后台静默刷新 ======

    /**
     * 后台静默刷新。
     *
     * 启动时先渲染本地缓存（离线秒开），随后用一个不可见的 WebView 复用同一套
     * 「CAS 自动登录 → 教务首页找入口 → DOM 解析」脚本，再去教务系统取一次最新课表。
     * 取到后与本地数据比对，确有变化才弹窗询问是否更新 —— 全程不影响用户正在看的课表，
     * 也不写任何数据（用户点「立即更新」才落盘）。
     */
    private WebView silentWebView;

    // ====== 查询教室：用隐藏 WebView 借教务自己的联动逻辑 ======
    // 教室查询页的校区→教学楼是 AJAX 联动、结果由 fcenter 框架承载，
    // 与其在客户端复刻一套（易随校方改版失效），不如让教务页面自己算，我们只取值。
    private WebView classroomWv;
    /** 专门用来抓姓名/学号的隐藏 WebView（打开教务首页） */
    private WebView studentWv;
    /** 用户点了「重新获取资料」：抓完要给出结果提示 */
    private boolean studentInfoManual = false;
    /** 当前查询来源：room=查询教室，empty=找空教室（决定结果渲染到哪个页面） */
    private String classroomMode = "room";
    /** 查询序号：只接受最后一次提交的回调，避免切换功能后旧结果串台 */
    private int classroomSeq = 0;
    private boolean classroomLoaded = false;
    private boolean classroomOptionsPending = false;
    private boolean silentRunning = false;
    private boolean silentOnSchedulePage = false;   // 已进入课表页(含猜测URL)
    private boolean silentAutoLogged = false;       // 本轮已自动提交过密码
    private boolean silentParseSent = false;        // 已发出解析请求，等回调
    private int silentGuessIdx = 0;
    private int silentToken = 0;
    private String pendingRefreshJson = null;       // 解析到的新课表，等用户确认
    private static final long SILENT_TIMEOUT_MS = 45000L;

    /** 启动后台刷新：仅在已有本地课表、且凭证齐全时进行 */
    private void startBackgroundRefresh() {
        if (silentRunning) return;
        if (store == null || !store.hasData()) return;
        if (savedUser.isEmpty() || savedPass.isEmpty()) return;
        silentRunning = true;
        silentOnSchedulePage = false;
        silentAutoLogged = false;
        silentParseSent = false;
        silentGuessIdx = 0;
        final int token = ++silentToken;
        Log.d(TAG, "silent refresh start");
        ensureSilentWebView();
        silentLoad(JWGL_BASE);
        // 总超时：到点没拿到结果就安静收场，不在后台留任何东西
        handler.postDelayed(() -> {
            if (token == silentToken && silentRunning) silentAbort("timeout");
        }, SILENT_TIMEOUT_MS);
    }

    private void ensureSilentWebView() {
        if (silentWebView != null) return;
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 与主 WebView 保持同一 UA，避免教务/ CAS 按浏览器差异返回不同页面
        s.setUserAgentString(webView.getSettings().getUserAgentString());
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.addJavascriptInterface(new SilentJsInterface(), "AndroidSilent");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl() == null ? "" : request.getUrl().toString();
                if (isTrustedUrl(u)) return false;
                Log.w(TAG, "silent blocked external nav: " + maskUrl(u));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handler.post(() -> handleSilentPageFinished(url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                super.onReceivedError(view, req, err);
                // 后台刷新失败就安静收场，绝不能弹错或影响主界面
                if (req.isForMainFrame()) handler.post(() -> silentAbort("network error"));
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
                String u = error == null ? "" : error.getUrl();
                if (isTrustedUrl(u)) h.proceed(); else h.cancel();
            }
        });
        // 透明且不可见，但**必须参与布局**：解析脚本依赖 innerText/表格尺寸，
        // 用 GONE 或 0dp 会让部分页面取不到文本。
        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        wv.setAlpha(0f);
        wv.setVisibility(View.INVISIBLE);
        wv.setClickable(false);
        wv.setFocusable(false);
        root.addView(wv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        silentWebView = wv;
    }

    private void silentLoad(String url) {
        if (silentWebView == null) return;
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        Log.d(TAG, "silent load: " + url);
        silentWebView.loadUrl(url, headers);
    }

    private void handleSilentPageFinished(String url) {
        if (!silentRunning || silentWebView == null || url == null) return;
        Log.d(TAG, "silent page: " + url);
        if (url.contains("authserver.shzu.edu.cn")) {
            silentWebView.evaluateJavascript(buildCasTypeJs(), value -> {
                if (!silentRunning) return;
                String type = value == null ? "unknown" : value.replace("\"", "").trim();
                Log.d(TAG, "silent CAS page: " + type);
                if ("login".equals(type)) {
                    if (!silentAutoLogged) {
                        silentAutoLogged = true;
                        silentWebView.evaluateJavascript(buildAutoLoginJs(savedUser, savedPass), null);
                    } else {
                        silentAbort("login rejected");
                    }
                } else {
                    // 短信/图形验证码等需要人工介入的页面：后台刷新直接放弃，交给用户手动刷新
                    silentAbort("cas " + type);
                }
            });
            return;
        }
        if (!url.contains("jwgl.shzu.edu.cn")) return;
        if (silentOnSchedulePage) {
            if (silentParseSent) return;
            silentParseSent = true;
            handler.postDelayed(() -> {
                if (silentRunning && silentWebView != null) {
                    silentWebView.evaluateJavascript(buildParseJs("AndroidSilent"), null);
                }
            }, 800);
        } else {
            handler.postDelayed(() -> {
                if (silentRunning && silentWebView != null && !silentOnSchedulePage) {
                    silentWebView.evaluateJavascript(buildFindLinkJs("AndroidSilent"), null);
                }
            }, 600);
            // 首页扫描无结果时的兜底：转入预设 URL 猜测
            handler.postDelayed(() -> {
                if (silentRunning && !silentOnSchedulePage) {
                    Log.d(TAG, "silent link-scan timeout, guessing");
                    handleSilentLink("");
                }
            }, 8000);
        }
    }

    /** 静默模式下探测到课表入口（found 为空=没找到，TRANSITION=跳板页） */
    private void handleSilentLink(String found) {
        if (!silentRunning || silentWebView == null) return;
        if (found == null) found = "";
        if ("TRANSITION".equals(found)) {
            handler.postDelayed(() -> {
                if (silentRunning && !silentOnSchedulePage) handleSilentLink("");
            }, 1200);
            return;
        }
        if (!found.isEmpty()) {
            silentOnSchedulePage = true;
            silentParseSent = false;
            silentLoad(found);
            return;
        }
        if (silentGuessIdx < SCHEDULE_URLS.length) {
            silentOnSchedulePage = true;
            silentParseSent = false;
            silentLoad(JWGL_BASE + SCHEDULE_URLS[silentGuessIdx++]);
        } else {
            silentAbort("no schedule entry");
        }
    }

    private void handleSilentParsed(String json) {
        // 静默刷新能进课表页，但姓名在首页的 iframe 里，这里顺带补抓
        handler.postDelayed(() -> ensureStudentInfo(false), 500);
        if (!silentRunning) return;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray courses = sanitizeCourses(obj.optJSONArray("courses"));
            Object errObj = obj.opt("error");
            boolean hasError = errObj != null && errObj != JSONObject.NULL;
            int n = courses == null ? 0 : courses.length();
            Log.d(TAG, "silent parsed: courses=" + n + " error=" + errObj);
            if (hasError || n == 0) {
                // 这个页面没有课表 → 换下一个猜测 URL，仍然没有就收场
                silentParseSent = false;
                if (silentGuessIdx < SCHEDULE_URLS.length) {
                    silentLoad(JWGL_BASE + SCHEDULE_URLS[silentGuessIdx++]);
                } else {
                    silentAbort("parse empty");
                }
                return;
            }
            boolean changed = !scheduleDigest(store.getAllCourses()).equals(scheduleDigest(courses));
            silentAbort("done");
            Log.d(TAG, "silent refresh: changed=" + changed + " new=" + n);
            if (changed) {
                pendingRefreshJson = json;
                showScheduleUpdatedDialog(n, countOverlaps(courses));
            }
        } catch (Exception e) {
            Log.w(TAG, "silent parsed exception", e);
            silentAbort("parse exception");
        }
    }

    /** 课表内容指纹：按「星期|节次|课名|教师|地点|周次」排序拼接，与顺序无关 */
    private static String scheduleDigest(JSONArray list) {
        if (list == null) return "";
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            rows.add(c.optInt("day", 0) + "|" + c.optInt("row", 0) + "|"
                    + normText(c.optString("name", "")) + "|" + normText(c.optString("teacher", "")) + "|"
                    + normText(c.optString("location", "")) + "|" + normText(c.optString("weeks", "")));
        }
        java.util.Collections.sort(rows);
        return android.text.TextUtils.join("\n", rows);
    }

    private static String normText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * 新课表与自定义课程的「撞格」统计结果。
     * example 形如「第5周 周一 3-4节」，只用于弹窗里给一个可核对的例子。
     */
    private static class OverlapStat {
        int count = 0;
        String example = "";
    }

    /** 节次标签（1..8 → 1-2节 / 3-4节 …），越界时按「第 2n-1 ~ 2n 节」兜底 */
    private static String periodLabelOf(int row) {
        return row >= 1 && row <= PERIOD_LABELS.length
                ? PERIOD_LABELS[row - 1] : (row * 2 - 1) + "-" + (row * 2) + "节";
    }

    /**
     * 统计新课表会**新增**多少处与自定义课程的重叠。
     *
     * 两个要点：
     * 1. 逐周判定：自定义课可能只在第 12 周，而新课表同格的教务课只在 1-8 周，不算重叠。
     * 2. 只报「新增」：用户本来就主动叠加在教务课上的，更新前后都重叠，不该再报一次；
     *    因此同时对**本地现有课表**与**新课表**建「周 → 占格集合」，只有
     *    「新课表有、本地没有」才计入。
     */
    private OverlapStat countOverlaps(JSONArray newCourses) {
        OverlapStat st = new OverlapStat();
        if (store == null || newCourses == null || newCourses.length() == 0) return st;

        int totalWeeks = store.getTotalWeeks();
        if (totalWeeks <= 0) totalWeeks = ScheduleStore.maxWeekOf(newCourses);
        if (totalWeeks <= 0) return st;

        Map<Integer, java.util.Set<String>> oldCells = cellsByWeek(store.getAllCourses(), totalWeeks);
        Map<Integer, java.util.Set<String>> newCells = cellsByWeek(newCourses, totalWeeks);

        JSONObject ov = store.getOverrides();
        JSONArray customs = store.getCustomCourses();
        for (int i = 0; i < customs.length(); i++) {
            JSONObject c = customs.optJSONObject(i);
            if (c == null) continue;
            int[] pos = resolvePosition(c, ov);
            int day = pos[0], row = pos[1];
            if (day < 1 || day > 7 || row < 1 || row > MAX_RENDER_ROWS) continue;
            String key = row + "_" + (day - 1);
            String weeks = c.optString("weeks", "");
            for (int w = 1; w <= totalWeeks; w++) {
                if (!ScheduleStore.weekMatches(weeks, w)) continue;
                if (!newCells.get(w).contains(key)) continue;
                if (oldCells.get(w).contains(key)) break;   // 原本就重叠，静默
                st.count++;
                if (st.example.isEmpty()) {
                    st.example = "第" + w + "周 " + WEEKDAY_CN[day] + " " + periodLabelOf(row);
                }
                break;
            }
        }
        return st;
    }

    /** 把课程数组展开成「周 → 占格集合」，键与渲染时一致：row_(day-1) */
    private static Map<Integer, java.util.Set<String>> cellsByWeek(JSONArray list, int totalWeeks) {
        Map<Integer, java.util.Set<String>> map = new HashMap<>();
        for (int w = 1; w <= totalWeeks; w++) map.put(w, new java.util.HashSet<>());
        if (list == null) return map;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            int day = c.optInt("day", 0), row = c.optInt("row", 0);
            if (day < 1 || day > 7 || row < 1 || row > MAX_RENDER_ROWS) continue;
            String key = row + "_" + (day - 1);
            String weeks = c.optString("weeks", "");
            for (int w = 1; w <= totalWeeks; w++) {
                if (ScheduleStore.weekMatches(weeks, w)) map.get(w).add(key);
            }
        }
        return map;
    }

    private void showScheduleUpdatedDialog(int newCount, OverlapStat stat) {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 17 && isDestroyed()) return;
        final int oldCount = store.getAllCourses().length();
        StringBuilder msg = new StringBuilder();
        msg.append("教务系统的课表与本地不一致（本地 ").append(oldCount)
           .append(" 条，最新 ").append(newCount).append(" 条）。");
        if (stat != null && stat.count > 0) {
            msg.append("\n\n其中 ").append(stat.count).append(" 门自定义课程会被新课表撞上（此前不重叠）");
            if (!stat.example.isEmpty()) {
                msg.append("，如 ").append(stat.example).append(stat.count > 1 ? " 等" : "");
            }
            msg.append("。更新后这些格子会以红色描边提示；你自己叠加的仍为琥珀色。");
        }
        msg.append("\n\n是否立即更新？更新后桌面小组件会一起刷新。");
        new AlertDialog.Builder(this)
                .setTitle("课表有更新")
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", (d, w) -> applyRefreshedSchedule())
                .setNegativeButton("稍后", (d, w) -> pendingRefreshJson = null)
                .setCancelable(true)
                .show();
    }

    /** 用户确认后：把静默刷新拿到的课表落盘并重渲染 */
    private void applyRefreshedSchedule() {
        String json = pendingRefreshJson;
        pendingRefreshJson = null;
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray courses = sanitizeCourses(obj.optJSONArray("courses"));
            if (courses == null || courses.length() == 0) return;
            String[] times = parsePeriodTimes(obj.optJSONArray("periodTimes"));
            // 落盘前先算重叠（落盘后 store 里就是新课表，拿不到「新数据 vs 自定义课」的对比）
            OverlapStat stat = countOverlaps(courses);
            saveAndRenderParsed(obj, courses, times);
            Toast.makeText(this, stat.count > 0
                    ? "课表已更新，新增 " + stat.count + " 处与自定义课程的重叠（红色标记）"
                    : "课表已更新", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "apply refreshed schedule failed", e);
            Toast.makeText(this, "课表更新失败，请手动刷新", Toast.LENGTH_SHORT).show();
        }
    }

    /** 收尾：停掉并彻底释放隐藏 WebView（失败路径也必须走到这里） */
    private void silentAbort(String reason) {
        if (!silentRunning && silentWebView == null) return;
        Log.d(TAG, "silent refresh end: " + reason);
        silentRunning = false;
        silentToken++;
        silentOnSchedulePage = false;
        silentParseSent = false;
        silentGuessIdx = 0;
        final WebView wv = silentWebView;
        silentWebView = null;
        if (wv == null) return;
        try {
            wv.stopLoading();
            ViewGroup parent = (ViewGroup) wv.getParent();
            if (parent != null) parent.removeView(wv);
            wv.destroy();
        } catch (Exception e) {
            Log.w(TAG, "silent webview release failed", e);
        }
    }

    /** 隐藏 WebView 的桥：只接静默刷新用的两个回调，与主流程的 Android 完全隔离 */
    private class SilentJsInterface {
        @JavascriptInterface
        public void onScheduleParsed(final String json) {
            handler.post(() -> handleSilentParsed(json));
        }

        @JavascriptInterface
        public void onScheduleLinkFound(final String url) {
            handler.post(() -> handleSilentLink(url));
        }
    }

    // ====== 学生资料（姓名/学号）抓取 ======

    /**
     * 抓姓名/学号：单独开一个隐藏 WebView 打开教务首页。
     *
     * 为什么必须走首页：姓名在首页 iframe「个人设置」(grsz_xggrxx.do) 的表单里，
     * **课表页本身没有这个 iframe**。升级安装后旧会话仍有效，App 直接进课表页，
     * 永远不经过首页 → 抓不到姓名，只能退出重登才恢复。
     */
    private void ensureStudentInfo(final boolean force) {
        if (store == null || savedUser.isEmpty()) return;
        if (!force && !store.getStudentName().isEmpty()
                && savedUser.equals(store.getStudentOwner())) {
            return;   // 已有且归属当前账号
        }
        if (studentWv != null) {
            if (!force) return;   // 已经在抓了
            releaseStudentWv();
        }
        try {
            WebView wv = new WebView(this);
            WebSettings st = wv.getSettings();
            st.setJavaScriptEnabled(true);
            st.setDomStorageEnabled(true);
            st.setUserAgentString(webView.getSettings().getUserAgentString());
            st.setAllowFileAccess(false);
            st.setAllowContentAccess(false);
            wv.addJavascriptInterface(new JsInterface(), "Android");
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    super.onPageFinished(v, url);
                    Log.d(TAG, "student info page: " + url);
                    if (url != null && url.contains("jwgl.shzu.edu.cn")) {
                        // 「个人设置」iframe 还要再等一会儿，靠重试兜住
                        v.evaluateJavascript(buildStudentInfoRetryJs(14), null);
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    String u = r.getUrl() == null ? "" : r.getUrl().toString();
                    return !isTrustedUrl(u);
                }
            });
            ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
            wv.setAlpha(0f);
            wv.setVisibility(View.INVISIBLE);
            wv.setClickable(false);
            wv.setFocusable(false);
            root.addView(wv, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            studentWv = wv;
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Language", "zh-CN,zh;q=0.9");
            // 走教务根地址：它会跳到「石河子大学教务一体化系统」框架首页，
            // 那页面上直接挂着「个人设置」的 iframe（/jsxsd/grsz/grsz_xggrxx.do），
            // 姓名就在那个表单里。直接请求 xsMain 之类的子页面反而会拿到「错误提示页面」。
            wv.loadUrl(JWGL_BASE, h);
            // 兜底：最多等 25 秒，超时就放弃（不影响其他功能）
            handler.postDelayed(this::releaseStudentWv, 25000);
            Log.d(TAG, "student info: capture started");
        } catch (Exception e) {
            Log.w(TAG, "student info capture start failed", e);
        }
    }

    /** 释放抓姓名用的隐藏 WebView */
    private void releaseStudentWv() {
        final WebView wv = studentWv;
        studentWv = null;
        if (wv == null) return;
        try {
            wv.stopLoading();
            ViewGroup parent = (ViewGroup) wv.getParent();
            if (parent != null) parent.removeView(wv);
            wv.destroy();
        } catch (Exception e) {
            Log.w(TAG, "student info webview release failed", e);
        }
    }

    // ====== 查询教室实现 ======

    /** 隐藏 WebView 载入教务「教室课表查询」页（与主 WebView 共用 Cookie 会话） */
    private void ensureClassroomWebView() {
        if (classroomWv != null) return;
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(webView.getSettings().getUserAgentString());
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.addJavascriptInterface(new ClassroomBridge(), "AndroidClassroom");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (url != null && url.contains("kbxx_classroom") && !classroomLoaded) {
                    classroomLoaded = true;
                    Log.d(TAG, "classroom page ready");
                    // 页面自带的 initJc 是异步 AJAX，稍等再取节次选项
                    handler.postDelayed(() -> {
                        if (classroomWv != null) classroomWv.evaluateJavascript(CLS_SNAPSHOT_JS, null);
                    }, 900);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl() == null ? "" : r.getUrl().toString();
                return !isTrustedUrl(u);
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                h.proceed();   // 校内域名，与主 WebView 策略一致
            }
        });
        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        wv.setAlpha(0f);
        wv.setVisibility(View.INVISIBLE);
        wv.setClickable(false);
        wv.setFocusable(false);
        root.addView(wv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        classroomWv = wv;
    }

    /** 打开（或复用）教室查询页，并把可选条件回传给「查询教室」页面 */
    private void classroomOpenOptions() {
        ensureClassroomWebView();
        if (classroomLoaded) {
            classroomWv.evaluateJavascript(CLS_SNAPSHOT_JS, null);
            return;
        }
        if (classroomOptionsPending) return;
        classroomOptionsPending = true;
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        classroomWv.loadUrl(JWGL_BASE + "/jsxsd/kbcx/kbxx_classroom", h);
        // 会话过期/网络异常时不能一直转圈，到点告诉页面先登录
        handler.postDelayed(() -> {
            if (!classroomLoaded) {
                classroomOptionsPending = false;
                evalJs("crSetOptions(null)");
            }
        }, 15000);
    }

    private void classroomSendJs(String js) {
        if (classroomWv != null) classroomWv.evaluateJavascript(js, null);
    }

    /** 取当前页面上的可选条件（校区/周次/星期/节次） */
    private static final String CLS_SNAPSHOT_JS = "(function(){try{"
        + "function opts(id){var e=document.getElementById(id);if(!e)return[];var a=[];"
        + "for(var i=0;i<e.options.length;i++){var o=e.options[i];"
        + "if(o.value)a.push({v:o.value,t:(o.text||'').replace(/\\s+/g,' ').trim()});}return a;}"
        + "AndroidClassroom.onOptions(JSON.stringify("
        + "{campus:opts('xqid'),term:opts('xnxqh'),week:opts('zc1'),weekday:opts('skxq1'),jc:opts('jc1')}));"
        + "}catch(e){AndroidClassroom.onOptions('{}');}})()";

    /** 选校区 → 借教务自身 onChangeXq 拉教学楼列表 */
    private static String clsPickCampusJs(String xqid, String target) {
        return "(function(){try{"
            + "var s=document.getElementById('xqid');"
            + "if(!s){AndroidClassroom.onBuildings('[]'," + jsString(target) + ");return;}"
            + "s.value=" + jsString(xqid) + ";"
            + "if(typeof onChangeXq==='function')onChangeXq(s);"
            + "var b=document.getElementById('jzwid'),a=[];"
            + "if(b)for(var i=0;i<b.options.length;i++){var o=b.options[i];"
            + "if(o.value)a.push({v:o.value,t:(o.text||'').replace(/\\s+/g,' ').trim()});}"
            + "AndroidClassroom.onBuildings(JSON.stringify(a)," + jsString(target) + ");"
            + "}catch(e){AndroidClassroom.onBuildings('[]'," + jsString(target) + ");}})()";
    }

    /**
     * 选教学楼 → 取该楼教室清单。
     *
     * 教务的教室选择是 showModalDialog 弹窗（`jx0601_select.htmlx`），这里不去模拟弹窗，
     * 而是同源 XHR 拉那份页面，读每行 `ondblclick="getData('<教室id>','<教室名>')"`——
     * 这正是弹窗双击回填到 `jsid`/`skjs` 的两个值，等价于用户手动选教室。
     */
    private static String clsRoomsJs(String bld) {
        return "(function(){try{"
            + "function done(a){AndroidClassroom.onRooms(JSON.stringify(a||[]));}"
            + "var xq=document.getElementById('xqid');var xv=xq?xq.value:'';"
            + "var s=document.getElementById('jzwid');if(s)s.value=" + jsString(bld) + ";"
            + "if(!xv||!s||!s.value){done([]);return;}"
            + "var url='/jsxsd/view/kbxx/kbcx/jx0601_select.htmlx?id=jsid&name=skjs&type=1&where=&xqid='"
            + "+encodeURIComponent(xv)+'&jzwid='+encodeURIComponent(s.value);"
            + "var x=new XMLHttpRequest();x.open('GET',url,true);"
            + "x.onreadystatechange=function(){"
            + "  if(x.readyState!==4)return;"
            + "  if(x.status!==200){done([]);return;}"
            + "  var doc=null;"
            + "  try{doc=new DOMParser().parseFromString(x.responseText,'text/html');}catch(e){doc=null;}"
            + "  var out=[],seen={};"
            + "  if(doc){var trs=doc.getElementsByTagName('tr');"
            + "    for(var i=0;i<trs.length;i++){"
            + "      var oc=trs[i].getAttribute('ondblclick')||'';"
            + "      var m=oc.match(/getData\\('([^']*)','([^']*)'\\)/);"
            + "      if(!m)continue;"
            + "      var id=m[1],nm=m[2];"
            + "      if(!id||!nm||seen[id])continue;"
            + "      seen[id]=1;out.push({v:id,t:nm});}}"
            + "  done(out);"
            + "};x.send();"
            + "}catch(e){try{AndroidClassroom.onRooms('[]');}catch(e2){}}})()";
    }

    /** 提交查询：填好表单 → submit（结果进 fcenter 框架）→ 轮询取结果表 */
    private static String clsQueryJs(String campus, String bld, String week,
                                     String wd, String j1, String j2,
                                     String roomId, String roomName, int seq) {
        return "(function(){try{"
            + "var SQ=" + seq + ";"
            + "function set(id,v){var e=document.getElementById(id);if(e&&v)e.value=v;}"
            // 教室可以不选：必须能把 jsid/skjs 清空，否则会一直沿用上一次选的教室
            + "function setv(id,v){var e=document.getElementById(id);if(e)e.value=(v==null?'':v);}"
            // ===== 结果表解析：转成 [{room,day,lines}] =====
            + "function cellText(el){if(!el)return '';"
            + "  var h=(el.innerHTML||'').replace(/<br\\s*\\/?>/gi,'\\n');"
            + "  var d=el.ownerDocument.createElement('div');d.innerHTML=h;"
            + "  return (d.textContent||'').replace(/\\u00a0/g,' ').trim();}"
            + "function parse(tbl){"
            + "  var out=[],allRooms=[],free={};"
            + "  try{"
            + "    var rows=tbl.rows,matrix=[];"
            // 展开 rowspan/colspan，得到完整矩阵（否则取不到被合并的格子）
            + "    for(var r=0;r<rows.length;r++){"
            + "      if(!matrix[r])matrix[r]=[];"
            + "      var ci=0;"
            + "      for(var c=0;c<rows[r].cells.length;c++){"
            + "        var td=rows[r].cells[c],rs=td.rowSpan||1,cs=td.colSpan||1;"
            + "        while(matrix[r][ci]!==undefined)ci++;"
            + "        for(var dr=0;dr<rs;dr++)for(var dc=0;dc<cs;dc++){"
            + "          var rr=r+dr,cc=ci+dc;if(!matrix[rr])matrix[rr]=[];"
            + "          matrix[rr][cc]={el:td,origin:(dr===0&&dc===0)};"
            + "        }"
            + "        ci+=cs;"
            + "      }"
            + "    }"
            + "    var dayCol={},headRow=-1;"
            + "    for(var r2=0;r2<matrix.length;r2++){"
            + "      for(var c2=0;c2<matrix[r2].length;c2++){"
            + "        var cell=matrix[r2][c2];if(!cell)continue;"
            + "        var m=(cell.el.textContent||'').replace(/\\s+/g,'').match(/星期([一二三四五六日天])/);"
            + "        if(m){dayCol[c2]='星期'+m[1];if(headRow<0)headRow=r2;}"
            + "      }"
            + "    }"
            + "    var ks=[];for(var kk in dayCol)ks.push(Number(kk));"
            + "    if(!ks.length)return out;"
            + "    var minDay=Math.min.apply(null,ks);"
            + "    for(var r3=headRow+1;r3<matrix.length;r3++){"
            + "      var row=matrix[r3]||[],room='';"
            + "      for(var c3=0;c3<minDay;c3++){"
            + "        if(row[c3]){room=(row[c3].el.textContent||'').replace(/\\s+/g,'').trim();if(room)break;}}"
            + "      if(!room)continue;"
            // 只排表头行（「教室\节次」及其后面的 0102 这类节次码）。
            // 不能按「名字里含教室」跳过——真实教室名就带这个字，
            // 例如「博学A-203（智慧教室）」，按旧规则会把这一整行丢掉，表现为「未识别出卡片」。
            + "      var rk=room.replace(/\\s+/g,'');"
            + "      if(rk.indexOf('节次')>=0)continue;"
            + "      if(rk.length<=6&&rk.indexOf('教室')>=0)continue;"
            + "      allRooms.push(room);"
            // 该教室在某星期列里「没有任何课」即为空。表格已由教务按周次+节次过滤，
            // 空格的含义就是「这一整段没课」，不需要自己算时间。
            + "      for(var dk0 in dayCol){"
            + "        var c0=matrix[r3][Number(dk0)];if(!c0)continue;"
            + "        if(cellText(c0.el).replace(/\\s+/g,'')===''){"
            + "          if(!free[dayCol[dk0]])free[dayCol[dk0]]=[];"
            + "          free[dayCol[dk0]].push(room);}}"
            + "      for(var dk in dayCol){"
            + "        var cc2=Number(dk),cell2=matrix[r3][cc2];"
            + "        if(!cell2||!cell2.origin)continue;"
            + "        var txt=cellText(cell2.el);"
            + "        if(!txt||txt.replace(/\\s+/g,'')===room)continue;"
            + "        var lines=txt.split('\\n').map(function(s){return s.replace(/\\s+/g,' ').trim();})"
            + "          .filter(function(s){return s.length>0&&s.replace(/\\s+/g,'')!==room;});"
            + "        if(!lines.length)continue;"
            + "        out.push({room:room,day:dayCol[dk],lines:lines,text:lines.join(' ')});"
            + "      }"
            + "    }"
            + "  }catch(e){}"
            + "  return {list:out,rooms:allRooms,free:free};"
            + "}"
            + "set('xqid'," + jsString(campus) + ");"
            + "var x=document.getElementById('xqid');if(typeof onChangeXq==='function')onChangeXq(x);"
            // 教学楼可以为空（整校区）：必须能把 jzwid 清空，否则会沿用上一次选的教学楼
            + "setv('jzwid'," + jsString(bld) + ");"
            + "if(typeof onChangeJXL==='function')onChangeJXL();"
            + "set('zc1'," + jsString(week) + ");set('zc2'," + jsString(week) + ");"
            + "set('skxq1'," + jsString(wd) + ");set('skxq2'," + jsString(wd) + ");"
            + "set('jc1'," + jsString(j1) + ");set('jc2'," + jsString(j2) + ");"
            // 教室过滤：实测教务只认 skjs（教室名称文本），jsid 一填反而让名称过滤失效
            // （同一次请求 jsid+skjs 返回整栋楼，只给 skjs 才只剩那一间）。
            // 所以 jsid 恒留空，skjs 只在选了具体教室时写名称；
            // 未选时必须清空——「全部教室（整栋楼）」这种界面文案写进去会查出空结果。
            + "setv('jsid','');"
            + "setv('skjs'," + jsString(roomId == null || roomId.isEmpty() ? "" : roomName) + ");"
            + "var f=document.getElementById('Form1');"
            + "if(!f){AndroidClassroom.onResult(JSON.stringify({html:'',msg:'教务页面结构异常，未找到查询表单',seq:SQ}));return;}"
            // 提交前先记下 iframe 里旧结果的指纹并清空：
            // 不清空的话，poll 第一次（400ms）就可能读到上一次查询留在 fcenter 里的表，
            // 表现为「查 A 教室却显示 B」「刚查完还是上一个条件的结果」。
            // 每次查询换一个全新的 iframe：
            // 复用旧 iframe 时，submit 之后新页面还没到、旧页面还在（旧页脚本还会继续重绘），
            // 于是会读到上一次的表（表现为「查 A 显示 B」）；整校区大表时还会读到渲染到一半的内容，
            // 同一条件两次查出行数不一样就是这么来的。换成空的新 iframe，出现内容就一定是这次的。
            + "try{var old=document.getElementById('fcenter');"
            + "  if(old&&old.parentNode){"
            + "    var nf=document.createElement('iframe');"
            + "    nf.id='fcenter';nf.name='fcenter';nf.width='100%';nf.height='600';nf.style.border='0';"
            + "    old.parentNode.replaceChild(nf,old);}}catch(e){}"
            + "function fdoc(){try{var fr=document.getElementById('fcenter');"
            + "  return fr?fr.contentDocument:null;}catch(e){return null;}}"
            + "function fsig(d){try{return d&&d.body?(d.body.textContent||'').replace(/\\s+/g,''):'';}catch(e){return '';}}"
            + "var tries=0,lastRows=-1,stable=0;"
            + "var timer=setInterval(function(){"
            + "  tries++;var doc=fdoc();"
            + "  var txt=fsig(doc);"
            + "  if(txt.length>0&&doc&&doc.readyState==='complete'){"
            // 教务的表格是逐行渲染的（脚本一行行插），readyState 完成不等于表格插完。
            // 所以再看表格行数：连续两次行数不变才算渲染结束，否则会只读到前几行。
            + "    var tb=null;try{tb=doc.querySelector('table');}catch(e){}"
            + "    var rc=tb&&tb.rows?tb.rows.length:0;"
            + "    if(rc>0){"
            + "      if(rc===lastRows){stable++;}else{stable=0;lastRows=rc;}"
            + "      if(stable<1&&tries<50)return;"
            + "    }"
            + "    clearInterval(timer);"
            + "    var t=doc.querySelector('table'),rep={html:'',rows:0,list:[],msg:'',seq:SQ};"
            + "    if(t){rep.html=t.outerHTML;rep.rows=t.rows?t.rows.length:0;"
            + "      var pr=parse(t)||{};rep.list=pr.list||[];rep.rooms=pr.rooms||[];rep.free=pr.free||{};}"
            + "    else{rep.html=doc.body.innerHTML;rep.rows=0;}"
            + "    if(!rep.html)rep.msg='该条件没有查询到占用记录';"
            + "    AndroidClassroom.onResult(JSON.stringify(rep));return;}"
            + "  if(tries>60){clearInterval(timer);"
            + "    AndroidClassroom.onResult(JSON.stringify({html:'',msg:'查询超时，教务系统没有返回结果',list:[],seq:SQ}));}"
            + "},400);"
            + "f.submit();"
            + "}catch(e){AndroidClassroom.onResult(JSON.stringify({html:'',msg:'查询失败：'+e.message,list:[],seq:SQ}));}})()";
    }

    /** 隐藏 WebView 的桥：只回传教室查询要用的三类数据 */
    private class ClassroomBridge {
        /** 临时：把结果表落盘，用于核对解析规则（发布前置 false） */
        private final boolean dump = false;
        @JavascriptInterface
        public void onOptions(final String json) {
            handler.post(() -> evalJs("crSetOptions(" + (json == null ? "null" : json) + ")"));
        }

        @JavascriptInterface
        public void onBuildings(final String json, final String target) {
            final String t = (target == null || target.isEmpty()) ? "crBuilding" : target;
            handler.post(() -> evalJs("crSetBuildings(" + (json == null ? "[]" : json)
                    + ",\"" + t + "\")"));
        }

        /** 某教学楼的教室清单（来自教务「选择教室」弹窗页，不是自己编的） */
        @JavascriptInterface
        public void onRooms(final String json) {
            handler.post(() -> evalJs("crSetRoom(" + (json == null ? "[]" : json) + ")"));
        }

        @JavascriptInterface
        public void onResult(final String json) {
            handler.post(() -> {
                try {
                    JSONObject o = new JSONObject(json == null ? "{}" : json);
                    // 同一时刻只认最后一次提交的结果：两个页面共用一个 WebView，
                    // 用户切换功能后迟到的回调必须丢弃，否则会把 A 的结果画到 B 页面上。
                    int seq = o.optInt("seq", -1);
                    if (seq >= 0 && seq != classroomSeq) {
                        Log.d(TAG, "classroom result dropped: seq=" + seq + " current=" + classroomSeq);
                        return;
                    }
                    // 结果表来自另一个文档，去掉脚本再塞进我们的页面
                    String html = o.optString("html", "").replaceAll("(?is)<script.*?</script>", "");
                    o.put("html", html);
                    if (dump && !html.isEmpty()) {
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                                new java.io.File(getFilesDir(), "classroom_result.html"))) {
                            out.write(html.getBytes("UTF-8"));
                        } catch (Exception ignored) {
                        }
                    }
                    Log.d(TAG, "classroom result: rows=" + o.optInt("rows", 0)
                            + " list=" + (o.optJSONArray("list") == null ? 0 : o.optJSONArray("list").length()));
                    evalJs(("empty".equals(classroomMode) ? "eqShowResult(" : "crShowResult(")
                            + o.toString() + ")");
                } catch (Exception e) {
                    Log.w(TAG, "classroom onResult failed", e);
                }
            });
        }
    }

    // ====== 课表展示 ======

    /**
     * 清洗解析出的课程数据
     * 教务页面底部有些文字（如“XX课程实习”）会被误解析成课程，行号会跑到 1-5 之外，
     * 这里统一丢弃行号越界与无课名的条目，避免脏数据写进存储。
     */
    private static JSONArray sanitizeCourses(JSONArray in) {
        if (in == null) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject c = in.optJSONObject(i);
            if (c == null) continue;
            int day = c.optInt("day", 0);
            int row = c.optInt("row", 0);
            if (day < 1 || day > 7 || row < 1 || row > 5) continue;
            String name = c.optString("name", "").trim();
            if (name.isEmpty()) continue;
            out.put(c);
        }
        if (out.length() != in.length()) {
            Log.d(TAG, "sanitizeCourses: dropped " + (in.length() - out.length())
                + " invalid entries");
        }
        return out;
    }

    /**
     * 校验并规整教务作息时间表：全部符合 HH:mm-HH:mm 才采用，单节课时自动合并为大节。
     * 主流程与后台静默刷新共用。
     */
    private String[] parsePeriodTimes(JSONArray ptArr) {
        if (ptArr == null || ptArr.length() < 3) return null;
        String[] cand = new String[ptArr.length()];
        for (int i = 0; i < ptArr.length(); i++) {
            String t = ptArr.optString(i, "");
            if (!t.matches("\\d{1,2}:\\d{2}\\s*[-–]\\s*\\d{1,2}:\\d{2}")) return null;
            cand[i] = t.replaceAll("\\s+", "");
        }
        String[] merged = mergePeriodTimes(cand);
        // 剔除 22:20-22:40 这类短时段，否则课表底部会多出一行空节次
        merged = sanitizePeriodTimes(merged);
        Log.d(TAG, "parsed periodTimes: " + merged.length + " slots: " + String.join(",", merged));
        return merged;
    }

    /** 解析结果落盘 → 重渲染本周 → 刷新桌面小组件（主流程与静默刷新共用） */
    private void saveAndRenderParsed(JSONObject obj, JSONArray courses, String[] parsedTimes) {
        if (store == null) store = new ScheduleStore(this);
        store.save(this, obj.optInt("currentWeek", 0), obj.optInt("totalWeeks", 0),
                obj.optLong("week1Monday", 0), parsedTimes, courses);
        Log.d(TAG, "saved window " + store.getWindowStart() + "-" + store.getWindowEnd()
                + " week1Monday=" + store.getWeek1Monday() + " todayWeek=" + store.todayWeek());
        int tw = store.todayWeek();
        renderWeekSchedule(tw > 0 ? tw : 1);
        // 新数据落地后同步刷新桌面小组件
        NextCourseWidgetProvider.refreshAll(this);
    }

    private void displaySchedule(String json) {
        parseResultReceived = true;
        latestParsedScheduleJson = json == null ? "" : json;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray courses = obj.optJSONArray("courses");
            // 节次时间表（从教务页面提取的作息时间，全部合法才存）
            String[] parsedTimes = parsePeriodTimes(obj.optJSONArray("periodTimes"));
            // 注意：optString("error") 会把 JSON null 读成字符串 "null"，必须用 opt + NULL 判断
            Object errObj = obj.opt("error");
            boolean hasError = errObj != null && errObj != JSONObject.NULL;
            // 清洗解析结果：丢弃行号越界(非1-5大节)、无课名的解析残留
            // （教务页面底部文字会被误当成课程，行号会算到 1-5 之外）
            courses = sanitizeCourses(courses);
            int n = courses == null ? 0 : courses.length();
            int parsedWeek = obj.optInt("currentWeek", 0);
            int parsedTotal = obj.optInt("totalWeeks", 0);
            long parsedWeek1Monday = obj.optLong("week1Monday", 0);
            Log.d(TAG, "parse result: courses=" + n + " error=" + errObj
                + " currentWeek=" + parsedWeek + " totalWeeks=" + parsedTotal
                + " week1Monday=" + parsedWeek1Monday);

            if (!hasError && n > 0) {
                // 解析成功 → 保存整学期课表（覆盖旧数据）→ 渲染今天所在周
                if (store == null) store = new ScheduleStore(this);
                saveAndRenderParsed(obj, courses, parsedTimes);
                return;
            }

            // 解析失败 → 重试（页面可能AJAX异步加载课表）
            if (parseRetries < MAX_PARSE_RETRIES) {
                parseRetries++;
                Log.d(TAG, "parse retry " + parseRetries + "/" + MAX_PARSE_RETRIES);
                final int token = navToken;
                handler.postDelayed(() -> {
                    if (token == navToken) parseSchedule();
                }, 1200);
                return;
            }

            // 重试用尽 → 转入URL猜测兜底（金智系统已确认，课表URL命中率高）
            if (guessIdx < SCHEDULE_URLS.length) {
                parseRetries = 0;
                String url = JWGL_BASE + SCHEDULE_URLS[guessIdx];
                guessIdx++;
                Log.d(TAG, "next guess URL: " + url);
                showLoading(getString(R.string.fetching_schedule));
                loadJwgl(url);
                return;
            }

            // 全部用尽 → 显示当前原始页面（用户可直接查看）
            Log.d(TAG, "all options exhausted, showing raw page");
            hideLoading();
            Toast.makeText(this, "未能获取课表，已显示原始页面，请手动导航", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e(TAG, "displaySchedule exception", e);
            hideLoading();
        }
    }

    /**
     * 单节课粒度检测与合并：若所有时段时长<=60分钟（单节课40~50分钟）且为偶数个，
     * 视为“每节课一个时段”，两两合并为大节时段（课表一行=2节课）。
     * 大节粒度（时长约90~100分钟）直接返回。
     */
    private String[] mergePeriodTimes(String[] times) {
        if (times == null || times.length < 8 || times.length % 2 != 0) return times;
        try {
            boolean allShort = true;
            for (String t : times) {
                String[] se = t.split("[-\u2013]");
                int m = minutesOf(se[1]) - minutesOf(se[0]);
                if (m <= 0 || m > 60) { allShort = false; break; }
            }
            if (!allShort) return times;
            String[] merged = new String[times.length / 2];
            for (int i = 0; i < merged.length; i++) {
                String s = times[2 * i].split("[-\u2013]")[0];
                String e = times[2 * i + 1].split("[-\u2013]")[1];
                merged[i] = s + "-" + e;
            }
            Log.d(TAG, "periodTimes merged: " + times.length + " single slots -> " + merged.length + " big slots");
            return merged;
        } catch (Exception e) {
            return times;
        }
    }

    private static int minutesOf(String hhmm) {
        String[] p = hhmm.trim().split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /**
     * 清洗作息时间表：丢弃时长过短的「延时/机动」时段。
     *
     * 教务页面除了正常大节（约 90~100 分钟），还会挂一条 20 分钟左右的时段
     * （如 22:20-22:40），旧实现把它当成第 6 大节，导致课表底部凭空多出一行
     * 「6节 22:20-22:40」。这类时段不可能排课，直接剔除。
     *
     * 保留项不足 3 条时原样返回，避免数据异常把整张作息表清空。
     */
    private static String[] sanitizePeriodTimes(String[] times) {
        if (times == null || times.length == 0) return times;
        java.util.ArrayList<String> keep = new java.util.ArrayList<>();
        for (String t : times) {
            if (t == null) continue;
            String v = t.replaceAll("\\s+", "");
            if (!v.matches("\\d{1,2}:\\d{2}[-–]\\d{1,2}:\\d{2}")) {
                keep.add(t);
                continue;
            }
            String[] se = v.split("[-–]");
            int span = minutesOf(se[1]) - minutesOf(se[0]);
            if (span >= MIN_PERIOD_MINUTES) keep.add(v);
            else Log.d(TAG, "periodTimes: drop short slot " + v + " (" + span + "min)");
        }
        if (keep.size() < 3 || keep.size() == times.length) return times;
        return keep.toArray(new String[0]);
    }

    /** 课程排课记录唯一标识（用于拖动位置覆盖）：名称|教师|周次|原day|原row
     *  必须编入教务原始位置：同名称同周次的多条记录（如晚自习排周一~周五及周日5条）key 各不相同，
     *  可独立拖动互不覆盖；而同一门课跨周渲染时原始位置恒定，key 不变，拖动位置对所有周同步生效。
     *  旧格式 override（不含位置后缀）自然失配失效，无需清除数据 */
    private static String courseKey(JSONObject c) {
        return ScheduleStore.courseKey(c);
    }

    // ====== 按周渲染课表 ======

    private int currentDisplayWeek = 1;

    /**
     * 是否处于「背景图铺满全屏」状态：有自定义背景图时开启。
     *
     * 开启后窗口内容延伸到状态栏与系统导航条后面，背景图能一直画到刘海和手势条，
     * WebView 再用 margin 让出这两条高度 —— 页面布局与字号完全不受影响，
     * 只是「窗口」变高了。关掉背景图就恢复成原来的安全区布局。
     */
    private boolean edgeToEdge = false;
    /** 当前已应用的系统栏高度（px），用来判断是否真的需要改 margin */
    private int appliedInsetTop = 0;
    private int appliedInsetBottom = 0;

    /** 课程配色。必须与 NextCourse.COLORS 完全一致，否则小组件与课表页颜色会对不上 */
    private static final String[] COURSE_COLORS = {
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFA07A",
        "#DDA0DD", "#87CEEB", "#F0E68C", "#FFB6C1", "#98D8C8",
        "#B0C4DE", "#DEB887", "#87CEFA", "#90EE90", "#FFD700"
    };
    /** 自定义课程可选色：比自动配色更鲜明，便于与教务课程区分 */
    private static final String[] CUSTOM_COLORS = {
        "#667EEA", "#F06292", "#26A69A", "#FF8A65", "#7E57C2",
        "#42A5F5", "#66BB6A", "#FFA726", "#8D6E63", "#78909C"
    };
    private static final String[] PERIOD_LABELS = {"1-2节", "3-4节", "5-6节", "7-8节", "9-10节"};
    private static final String[] DEFAULT_PERIOD_TIMES = {
        "10:00-11:50", "12:00-13:50", "14:00-15:50", "16:00-17:50", "18:00-19:50"
    };
    /** 渲染上限：教务通常 5 大节，自定义课程可落到第 6 节之后 */
    private static final int MAX_RENDER_ROWS = 8;
    /** 单个大节的最短时长（分钟）：低于此值的作息条目视为「延时/机动」时段，不占课表行 */
    private static final int MIN_PERIOD_MINUTES = 25;
    private static final String[] WEEKDAY_CN = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /**
     * 后台预热周数上限：只备邻近几周，避免无意义地占内存。
     *
     * 从 6 降到 4：每预生成一周都要拼一次完整 HTML（几十 KB 字符串 + 全部卡片），
     * 6 周在主线程上累计明显可感（启动后 CPU 会顶一小会儿）。
     * 热门方向是"往后翻"，所以队列本身也是先正向后负向，4 周已覆盖绝大多数切换。
     */
    private static final int PREWARM_MAX_WEEKS = 4;
    /**
     * 周课表 HTML 缓存（LRU，只留邻近几周）。
     *
     * 启动时只拼装「今天所在周」并立即显示，其余周次交给 prewarmStep 在首帧之后
     * 后台逐周预生成 —— 页面先出来，之后切周直接命中缓存，不再重新拼装整页 HTML。
     */
    private final java.util.LinkedHashMap<Integer, String> weekHtmlCache =
            new java.util.LinkedHashMap<Integer, String>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, String> eldest) {
                    return size() > PREWARM_MAX_WEEKS + 1;
                }
            };
    /** 缓存对应的状态指纹：主题/背景/拖动布局/课表数据变更即整体失效 */
    private String weekHtmlStateSig = "";
    private final java.util.ArrayDeque<Integer> prewarmQueue = new java.util.ArrayDeque<>();

    private void renderWeekSchedule(int week) {
        if (store == null) store = new ScheduleStore(this);
        // 教务课表为空但用户自己加过课，也要能正常渲染（自定义课程是独立通道）
        if (!store.hasData() && store.getCustomCourseCount() == 0) return;
        if (week < 1) week = 1;
        int total = store.getTotalWeeks();
        if (total > 0 && week > total) week = total;
        currentDisplayWeek = week;

        String sig = scheduleStateSignature();
        if (!sig.equals(weekHtmlStateSig)) {
            weekHtmlCache.clear();
            weekHtmlStateSig = sig;
        }
        String html = weekHtmlCache.get(week);
        boolean fromCache = html != null;
        if (!fromCache) {
            html = generateWeekHTML(week, store.coursesForWeek(week));
            weekHtmlCache.put(week, html);
        }
        Log.d(TAG, "renderWeekSchedule: week=" + week + (fromCache ? " (cache hit)" : ""));
        hideLoading();
        showingDisplay = true;
        onGeneratedSchedule = true;
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        // 首帧之后再去预热其余周次：启动关键路径上不增加任何额外开销
        schedulePrewarm(week);
    }

    /**
     * 渲染状态指纹。
     *
     * 周课表 HTML 里烘焙了主题、背景、拖动后的位置以及课表数据本身，
     * 任何一项变化都必须让旧缓存作废，否则会渲染出与当前设置不一致的页面。
     */
    /**
     * 取某一周的「网格片段」（`<div class='grid'>…</div>`），给横条两侧的相邻周面板用。
     *
     * 不重新拼装整页：优先复用 weekHtmlCache 里已有的整页 HTML，按 <!--/grid--> 标记
     * 切出网格部分（两次 indexOf，几十 KB 字符串，耗时 <1ms）；缺失时才生成一次并缓存 ——
     * 那份缓存以后整页渲染也能直接命中，不浪费。
     */
    private String gridFragment(int week) {
        if (store == null) return "";
        if (!store.hasData() && store.getCustomCourseCount() == 0) return "";
        int total = store.getTotalWeeks();
        if (week < 1 || (total > 0 && week > total)) return "";
        String full = weekHtmlCache.get(week);
        if (full == null) {
            full = generateWeekHTML(week, store.coursesForWeek(week));
            weekHtmlCache.put(week, full);
        }
        int a = full.indexOf("<!--grid--><div class='grid");
        int b = full.indexOf("<!--/grid-->", a);
        if (a < 0 || b < 0) return "";
        // 从标记之后开始，避免把 <!--grid--> 本身带进面板
        return full.substring(a + "<!--grid-->".length(), b);
    }

    /** 当前是周几（1=周一 … 7=周日），与课表里「今日高亮」的判定口径保持一致 */
    private static int currentDow() {
        int dw = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return dw == Calendar.SUNDAY ? 7 : dw - 1;
    }

    /**
     * 某一周在「标题区 + 表头」上的全部文字。
     *
     * 切周只滑网格、不重载整页，所以这些固定不动的文字要单独回填：
     * 日期、第 N 周、本周提示、7 个日期、今日列高亮、◀▶ 是否可用。
     */
    private String weekMetaJson(int week) {
        JSONObject o = new JSONObject();
        try {
            int total = store == null ? 0 : store.getTotalWeeks();
            long monday = (store != null && store.getWeek1Monday() > 0) ? store.mondayOf(week) : 0;
            int todayWeek = store == null ? -1 : store.todayWeek();
            boolean isToday = (week == todayWeek);
            o.put("w", week);
            o.put("label", "第" + week + "周");
            o.put("date", monday > 0 ? ScheduleStore.fmtDate(monday) + " 起" : "");
            JSONArray days = new JSONArray();
            for (int d = 1; d <= 7; d++) {
                days.put(monday > 0 ? ScheduleStore.fmtDate(monday + (d - 1) * 86400000L) : "");
            }
            o.put("days", days);
            o.put("todayDow", isToday ? currentDow() : 0);
            o.put("hint", isToday
                    ? "本周 · 今天 " + ScheduleStore.fmtDate(System.currentTimeMillis())
                      + " " + ScheduleStore.fmtWeekday(System.currentTimeMillis())
                    : "");
            o.put("canPrev", week > 1);
            o.put("canNext", total > 0 && week < total);
        } catch (Exception ignored) {
        }
        // 与 updateInfoJson 同样的口径：只转义 < > &，保持对象字面量、不要再套 JSONObject.quote
        return o.toString()
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
    }

    private String scheduleStateSignature() {
        if (store == null) return "";
        JSONObject ov = store.getOverrides();
        Calendar c = Calendar.getInstance();
        return store.getTheme() + "|" + store.getBgImage()
                + "|" + store.getTotalWeeks() + "|" + store.getWeek1Monday()
                + "|" + store.todayWeek() + "|" + c.get(Calendar.DAY_OF_YEAR)
                + "|" + (ov == null ? "" : ov.toString().hashCode())
                + "|" + store.getCoursesHash()
                + "|" + store.getCustomCoursesHash()
                + "|" + store.getConflictPriority()
                // 字号是烘焙进 CSS 的（:root 的 --fs），改了必须让缓存整体作废
                + "|" + store.getFontScale();
    }

    /**
     * 界面字体缩放系数，写进 CSS 变量 --fs。
     *
     * 保留两位小数即可：用户能选的是 5% 一档，没必要打印更长的小数，
     * 而且这个值会进 HTML 与缓存指纹，越短越好。
     */
    private String fontScaleFactor() {
        int pct = store == null ? 100 : store.getFontScale();
        return String.format(Locale.US, "%.2f", pct / 100.0);
    }

    /**
     * 课表网格内的字号缩放系数（比 --fs 温和）。
     *
     * 网格的格子高度是 1fr 均分的，卡片尺寸不随字号变化。若按用户选的比例
     * 原样放大（140%），一格的「课名 + 教师 + 地点 + 周次」必然塞不下，
     * 地点会被直接裁掉 —— 实测就是这样。所以网格内只跟 45% 的增量：
     * 140% → 1.18，既看得出变大，又能保住信息。
     *
     * 网格外的页面（设置、我的、查询教室）没有固定高度约束，照常用 --fs。
     */
    private String gridFontScaleFactor() {
        int pct = store == null ? 100 : store.getFontScale();
        double f = 1.0 + (pct / 100.0 - 1.0) * 0.45;
        return String.format(Locale.US, "%.2f", f);
    }

    /** 由近及远排入待预热的周次（本周±1、±2…），等首帧画完再开始 */
    private void schedulePrewarm(int fromWeek) {
        handler.removeCallbacks(prewarmStep);
        prewarmQueue.clear();
        if (store == null || !store.hasData()) return;
        int total = store.getTotalWeeks();
        if (total <= 0) return;
        for (int d = 1; d <= total && prewarmQueue.size() < PREWARM_MAX_WEEKS; d++) {
            if (fromWeek + d <= total) prewarmQueue.add(fromWeek + d);
            if (prewarmQueue.size() >= PREWARM_MAX_WEEKS) break;
            if (fromWeek - d >= 1) prewarmQueue.add(fromWeek - d);
        }
        // 等当前页首帧画完再开工，避免与 WebView 首屏渲染抢主线程
        handler.postDelayed(prewarmStep, 400);
    }

    private final Runnable prewarmStep = new Runnable() {
        @Override
        public void run() {
            // 兜底：销毁中的 Activity 不再继续预热（正常路径 onDestroy 已摘掉回调）
            if (isFinishing() || isDestroyed()) return;
            if (prewarmQueue.isEmpty() || store == null || !store.hasData()) return;
            String sig = scheduleStateSignature();
            if (!sig.equals(weekHtmlStateSig)) {
                weekHtmlCache.clear();
                weekHtmlStateSig = sig;
            }
            Integer w = prewarmQueue.poll();
            if (w == null) return;
            if (!weekHtmlCache.containsKey(w)) {
                weekHtmlCache.put(w, generateWeekHTML(w, store.coursesForWeek(w)));
                Log.d(TAG, "prewarm week " + w + " ready");
            }
            // 一周一帧地做，主线程每次只承担一次拼装的耗时。
            // 间隔从 80ms 放宽到 140ms：相邻两帧之间至少要留出一次正常输入的时间，
            // 否则连点切换周/拖动时会跟预渲染抢主线程（表现为掉帧、发烫）。
            handler.postDelayed(this, 140);
        }
    };

    /** 应用拖动覆盖后的 (day,row)。教务课程与自定义课程共用同一套规则 */
    private int[] resolvePosition(JSONObject c, JSONObject ov) {
        int day = c.optInt("day", 0);
        int row = c.optInt("row", 0);
        if (ov != null) {
            JSONObject o = ov.optJSONObject(courseKey(c));
            if (o != null) {
                int nd = o.optInt("d", 0), nr = o.optInt("r", 0);
                if (nd >= 1 && nd <= 7 && nr >= 1 && nr <= MAX_RENDER_ROWS) {
                    day = nd;
                    row = nr;
                }
            }
        }
        return new int[]{day, row};
    }

    /** 把一组课程按 (节次,星期) 归档进单元格表，返回这组课程出现的最大节次 */
    private int collectCells(JSONArray list, JSONObject ov, Map<String, JSONArray> out, int maxRow) {
        if (list == null) return maxRow;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            int[] pos = resolvePosition(c, ov);
            int day = pos[0], row = pos[1];
            if (day < 1 || day > 7 || row < 1 || row > MAX_RENDER_ROWS) continue;
            String key = row + "_" + (day - 1);
            JSONArray arr = out.get(key);
            if (arr == null) { arr = new JSONArray(); out.put(key, arr); }
            arr.put(c);
            if (row > maxRow) maxRow = row;
        }
        return maxRow;
    }

    /** 把 src 里的对象逐个追加到 dst */
    private static void appendAll(JSONArray dst, JSONArray src) {
        if (src == null) return;
        for (int i = 0; i < src.length(); i++) {
            JSONObject o = src.optJSONObject(i);
            if (o != null) dst.put(o);
        }
    }

    /**
     * 这一格里的自定义课是否全部是「有意叠加」（加课时该格已有课）。
     * 只要有一门不是，就按意外撞车处理——宁可多报警，也不要把真实冲突柔化掉。
     */
    private static boolean customAllIntentional(JSONArray customs) {
        if (customs == null || customs.length() == 0) return false;
        for (int i = 0; i < customs.length(); i++) {
            JSONObject c = customs.optJSONObject(i);
            if (c == null) continue;
            if (!c.optBoolean("overlap", false)) return false;
        }
        return true;
    }

    /**
     * 指定周、指定格子里已有的课程数（教务 + 自定义，位置按 overrides 修正）。
     * 用于「重叠加课」时告诉用户这一格已经压了几门课。
     *
     * @param excludeId 编辑已有自定义课时传它的 id，避免把它自己算进去
     */
    private int countOccupied(int week, int day, int row, String excludeId) {
        if (store == null) store = new ScheduleStore(this);
        if (week < 1) week = 1;
        JSONObject ov = store.getOverrides();
        int n = 0;
        JSONArray school = store.coursesForWeek(week);
        for (int i = 0; i < school.length(); i++) {
            JSONObject c = school.optJSONObject(i);
            if (c == null) continue;
            int[] p = resolvePosition(c, ov);
            if (p[0] == day && p[1] == row) n++;
        }
        JSONArray customs = store.customCoursesForWeek(week);
        for (int i = 0; i < customs.length(); i++) {
            JSONObject c = customs.optJSONObject(i);
            if (c == null) continue;
            if (excludeId != null && excludeId.equals(c.optString("id", ""))) continue;
            int[] p = resolvePosition(c, ov);
            if (p[0] == day && p[1] == row) n++;
        }
        return n;
    }

    /**
     * 课程卡片颜色。
     * 自定义课程允许用户自选颜色，优先级高于按课名分配；其余沿用同一套色板。
     */
    private String courseColor(JSONObject c, Map<String, Integer> colorMap) {
        String own = c.optString("color", "").trim();
        if (own.matches("^#[0-9a-fA-F]{6}$")) return own;
        int idx = colorMap.getOrDefault(c.optString("name", ""), 0);
        return COURSE_COLORS[idx % COURSE_COLORS.length];
    }

    /** 打开添加/编辑表单：把初始数据交给页面，由 JS 填表并滑入 */
    private void showCustomForm(JSONObject data) {
        try {
            String json = data == null ? "{}" : data.toString();
            webView.evaluateJavascript("openCustomForm(" + json + ")", null);
        } catch (Exception e) {
            Log.e(TAG, "showCustomForm error", e);
        }
    }

    /** 设置页「自定义课程」列表：每条都可点开编辑，右侧可单独删除 */
    private String buildCustomCourseListHtml() {
        if (store == null) store = new ScheduleStore(this);
        JSONArray list = store.getCustomCourses();
        if (list.length() == 0) {
            return "<div class='cc-empty'>还没有自定义课程<br>点下方按钮，或回课表点「＋ 加课」再点空白格子</div>";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("id", "");
            String name = esc(c.optString("name", ""));
            int day = c.optInt("day", 0);
            int row = c.optInt("row", 0);
            String weeks = c.optString("weeks", "").trim();
            String color = c.optString("color", "").trim();
            if (!color.matches("^#[0-9a-fA-F]{6}$")) color = CUSTOM_COLORS[0];
            String period = row >= 1 && row <= PERIOD_LABELS.length
                    ? PERIOD_LABELS[row - 1] : (row * 2 - 1) + "-" + (row * 2) + "节";
            // 单次卡片周次存的是纯数字（当前周），列表里显示成「仅第 N 周」更直观
            String weeksText = weeks.isEmpty() ? "每周"
                    : (weeks.matches("\\d{1,2}") ? "仅第" + weeks + "周" : esc(weeks));
            String when = (day >= 1 && day <= 7 ? WEEKDAY_CN[day] : "—")
                    + " " + period + " · " + weeksText;
            sb.append("<div class='cc-item'>");
            sb.append("<span class='cc-dot' style='background:").append(color).append("'></span>");
            sb.append("<div class='cc-txt' onclick='Android.onEditCustom(\"").append(id).append("\")'>");
            sb.append("<b>").append(name).append("</b><i>").append(when).append("</i></div>");
            sb.append("<button class='cc-del' onclick='deleteCustom(event,\"").append(id).append("\")'>删除</button>");
            sb.append("</div>");
        }
        return sb.toString();
    }

    private String generateWeekHTML(int week, JSONArray courses) {
        int todayWeek = store.todayWeek();
        int totalWeeks = store.getTotalWeeks();
        if (totalWeeks <= 0) totalWeeks = week;
        long monday = store.getWeek1Monday() > 0 ? store.mondayOf(week) : 0;

        Calendar cal = Calendar.getInstance();
        int dw = cal.get(Calendar.DAY_OF_WEEK);
        int todayDow = dw == Calendar.SUNDAY ? 7 : dw - 1;

        // 用户拖动产生的位置覆盖（教务课程与自定义课程共用同一张表，键由 courseKey 区分）
        JSONObject ov = store.getOverrides();
        // 本周的自定义课程：独立通道，与上面的教务课程互不影响
        JSONArray customs = store.customCoursesForWeek(week);

        // 同课同色：按「教务在前、自定义在后」的顺序分配，与 NextCourse.colorMapFor 保持一致
        Map<String, Integer> colorMap = new HashMap<>();
        int colorIdx = 0;
        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("name", "");
            if (!colorMap.containsKey(name)) colorMap.put(name, colorIdx++);
        }
        for (int i = 0; i < customs.length(); i++) {
            JSONObject c = customs.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("name", "");
            if (!colorMap.containsKey(name)) colorMap.put(name, colorIdx++);
        }

        // 两路课程各自归档到 (节次,星期) 单元格，之后再按冲突优先级合并
        Map<String, JSONArray> schoolCells = new HashMap<>();
        Map<String, JSONArray> customCells = new HashMap<>();
        int maxRow = collectCells(courses, ov, schoolCells, 0);
        maxRow = collectCells(customs, ov, customCells, maxRow);

        // 合并：同一格里同时存在教务课与自定义课时，按用户偏好决定谁排在前面；
        // 再区分两种语义——「用户有意叠加」（琥珀色）与「教务刷新后才撞上」（红色报警）。
        boolean schoolFirst = !"custom".equals(store.getConflictPriority());
        Map<String, JSONArray> cellMap = new HashMap<>();
        java.util.Set<String> clashCells = new java.util.HashSet<>();
        java.util.Set<String> overlapCells = new java.util.HashSet<>();
        java.util.Set<String> allCells = new java.util.LinkedHashSet<>(schoolCells.keySet());
        allCells.addAll(customCells.keySet());
        for (String key : allCells) {
            JSONArray s = schoolCells.get(key);
            JSONArray cu = customCells.get(key);
            int schoolCount = s == null ? 0 : s.length();
            int customCount = cu == null ? 0 : cu.length();
            JSONArray arr = new JSONArray();
            if (schoolCount > 0 && customCount > 0) {
                if (customAllIntentional(cu)) overlapCells.add(key);
                else clashCells.add(key);
                if (schoolFirst) { appendAll(arr, s); appendAll(arr, cu); }
                else { appendAll(arr, cu); appendAll(arr, s); }
            } else if (s != null) {
                appendAll(arr, s);
            } else if (cu != null) {
                appendAll(arr, cu);
                // 同一格叠了多门自定义课：都属于有意叠加，不需要红色报警
                if (customCount > 1) overlapCells.add(key);
            }
            if (arr.length() > 0) cellMap.put(key, arr);
        }

        // 节次时间表（从教务作息数据获取，失败时用新疆高校常见作息兜底）
        // 旧版本曾把「22:20-22:40」这类延时时段一并存下，渲染时再洗一遍，老数据无需重抓即可恢复
        String[] periodTimes = sanitizePeriodTimes(store.getPeriodTimes());
        if (periodTimes == null || periodTimes.length == 0) periodTimes = DEFAULT_PERIOD_TIMES;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1.0'>");
        sb.append("<style>");
        sb.append("*{margin:0;padding:0;box-sizing:border-box;}");
        // 主题变量：浅色（默认）/ 深色，背景与文字带过渡动画
        // --fs 是界面字体缩放系数（由「设置 → 通用设置 → 字体大小」写入内存配置）。
        // 所有字号都写成 calc(<原字号> * var(--fs,1))，所以调这个变量能实时缩放整页文字，
        // 而卡片/网格的尺寸仍由布局决定，不会因为放大字而把课表挤变形。
        sb.append(":root{--fs:").append(fontScaleFactor())
          .append(";--fsg:").append(gridFontScaleFactor()).append(";");
        sb.append("--bg:#FFFFFF;--fg:#333;--sub:#999;--card:#F0F0F5;--card2:#F8F8FA;");
        sb.append("--line:#F0F0F5;--empty:#FAFAFC;--modal:#FFFFFF;}");
        // 深色主题直接用纯黑：OLED 屏上黑像素不发光，既省电也不刺眼。
        // 备一层极暗的分隔色（--line）与卡片色（--card），纯黑底上才看得出层次。
        sb.append("body.dark{--bg:#000000;--fg:#EDEDF2;--sub:#8E8E9E;--card:#0A0A0C;--card2:#000000;");
        sb.append("--line:#1C1C20;--empty:#050506;--modal:#0C0C0E;}");
        sb.append("body{font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;");
        // 底部给标签栏留出固定高度，网格用 flex 自动吃掉剩余空间
        sb.append("background-color:var(--bg);height:100vh;color:var(--fg);");
        sb.append("padding:12px 8px calc(56px + env(safe-area-inset-bottom,0px)) 8px;");
        sb.append("transition:background-color 0.35s ease,color 0.35s ease;");
        sb.append("display:flex;flex-direction:column;overflow:hidden;}");
        // 有自定义背景图时页面背景透明，露出 Activity 层图片（切周不闪）
        sb.append("body.hasbg{background-color:transparent;}");
        sb.append(".header{text-align:center;margin-bottom:6px;flex:0 0 auto;}");
        sb.append(".header h1{font-size:calc(20px * var(--fs,1));font-weight:700;color:var(--fg);margin-bottom:2px;}");
        // 日期与周次同一行：日期维持次要灰，周次用正文色（浅色主题下即近黑）加粗突出
        sb.append(".subline{display:flex;justify-content:center;align-items:baseline;gap:8px;}");
        sb.append(".subline .sdate{font-size:calc(12px * var(--fs,1));color:#999;}");
        sb.append(".subline .sweek{font-size:calc(15px * var(--fs,1));font-weight:700;color:var(--fg);letter-spacing:0.5px;");
        // 可点击暗示：负 margin 扩大热区不挤占排版，按下时给一层品牌色底
        sb.append("cursor:pointer;padding:3px 8px;margin:-3px -8px;border-radius:8px;}");
        sb.append(".subline .sweek:active{background:rgba(102,126,234,0.15);}");
                // min-height 用 em（跟随自身字号）：无论有没有文字，这一行的高度都固定，
        // 各周课表网格分到的高度才会完全一致。
        sb.append(".hint{text-align:center;font-size:calc(11px * var(--fs,1));color:#999;");
        sb.append("margin-top:3px;line-height:1.5;min-height:1.5em;}");
        sb.append(".btns{display:flex;justify-content:center;align-items:center;gap:8px;margin-top:8px;}");
        sb.append(".btn{background:var(--card);border:none;color:var(--fg);font-size:calc(12px * var(--fs,1));");
        sb.append("border-radius:16px;padding:0 14px;height:32px;line-height:32px;flex:0 0 auto;}");
        sb.append(".btn:disabled{opacity:0.35;}");
        // 周切换按钮与其它按钮同高同圆角，只是收窄成方形
        sb.append(".btn-nav{padding:0;width:32px;font-size:calc(13px * var(--fs,1));color:var(--sub);}");
        sb.append(".btn-add{background:#667eea;color:#fff;font-weight:600;}");
        // ===== 底部标签栏 =====
        // 固定在最下方并高于设置页（z-index 96），任何页面下都可见，像真正的 tab。
        // 页面容器 .page 的 bottom 也要让出这一段（见 .page 规则）。
        sb.append(".tabbar{position:fixed;left:0;right:0;bottom:0;height:56px;z-index:96;");
        sb.append("display:flex;align-items:stretch;background:var(--bg);");
        sb.append("border-top:1px solid var(--line);");
        sb.append("padding-bottom:env(safe-area-inset-bottom,0px);}");
        sb.append(".tabitem{flex:1;border:none;background:transparent;color:var(--sub);");
        sb.append("font-size:calc(13px * var(--fs,1));cursor:pointer;position:relative;}");
        sb.append(".tabitem.on{color:#667eea;font-weight:700;}");
        sb.append(".tabitem.on::after{content:'';position:absolute;left:50%;bottom:6px;");
        sb.append("width:18px;height:2px;border-radius:1px;background:#667eea;transform:translateX(-50%);}");
        sb.append(".tabitem:active{opacity:0.7;}");
        // 我的页面：资料卡 + 右上角齿轮
        sb.append(".ph-gear{background:transparent;border:none;color:var(--fg);width:48px;height:48px;");
        sb.append("display:flex;align-items:center;justify-content:center;cursor:pointer;}");
        sb.append(".me-card{display:flex;align-items:center;gap:14px;background:var(--card);");
        sb.append("border-radius:14px;padding:16px;margin-bottom:14px;}");
        sb.append(".me-avatar{width:52px;height:52px;border-radius:50%;flex:0 0 auto;");
        sb.append("background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;");
        sb.append("display:flex;align-items:center;justify-content:center;font-size:calc(22px * var(--fs,1));font-weight:700;}");
        sb.append(".me-info{flex:1;min-width:0;}");
        sb.append(".me-info b{display:block;font-size:calc(17px * var(--fs,1));color:var(--fg);font-weight:700;}");
        sb.append(".me-info i{display:block;font-style:normal;font-size:calc(13px * var(--fs,1));color:var(--sub);margin-top:5px;}");
        sb.append(".me-empty{font-size:calc(13px * var(--fs,1));color:var(--sub);line-height:1.7;}");
        sb.append(".page-hint{font-size:calc(12px * var(--fs,1));color:var(--sub);line-height:1.7;padding:2px 4px 20px;}");
        // 查询教室：状态行 + 教务原始结果表的兜底样式
        sb.append(".cr-status{font-size:calc(13px * var(--fs,1));color:var(--sub);line-height:1.7;padding:8px 2px;}");
        sb.append(".cr-raw{margin-top:16px;overflow:auto;-webkit-overflow-scrolling:touch;}");
        sb.append(".cr-raw table{border-collapse:collapse;width:auto;min-width:100%;font-size:calc(11px * var(--fs,1));}");
        // 教务的表列很多：不逐字断行、允许横向滚动，比硬挤在屏宽内可读
        sb.append(".cr-raw td,.cr-raw th{border:1px solid var(--line);padding:5px 6px;color:var(--fg);");
        sb.append("white-space:nowrap;word-break:normal;vertical-align:middle;}");
        sb.append(".cr-raw th{background:var(--card);font-weight:600;}");
        // 解析后的占用卡片
        sb.append(".cr-card{background:var(--card);border-radius:12px;padding:12px;margin-top:10px;}");
        sb.append(".cr-card .ch{display:flex;justify-content:space-between;align-items:baseline;gap:8px;}");
        sb.append(".cr-card .ch b{font-size:calc(14px * var(--fs,1));color:var(--fg);font-weight:700;}");
        sb.append(".cr-card .ch span{font-size:calc(12px * var(--fs,1));color:#667eea;font-weight:600;flex:0 0 auto;}");
        sb.append(".cr-card .cb{margin-top:6px;font-size:calc(12px * var(--fs,1));color:var(--sub);line-height:1.7;word-break:break-all;}");
        sb.append(".cr-card .cb em{font-style:normal;color:var(--fg);font-weight:600;}");
        // 卡片详情弹窗（两个教室功能共用）：左侧字段名、右侧值的两列行
        sb.append(".cd-row{display:flex;gap:12px;font-size:calc(13px * var(--fs,1));line-height:1.9;color:var(--fg);}");
        sb.append(".cd-row span{flex:0 0 52px;color:var(--sub);}");
        sb.append(".cd-row b{flex:1;min-width:0;font-weight:600;word-break:break-word;}");
        sb.append(".cd-act{display:block;width:100%;margin-top:16px;background:var(--card);");
        sb.append("border:none;color:#667eea;font-weight:600;border-radius:12px;padding:12px;");
        sb.append("font-size:calc(14px * var(--fs,1));font-family:inherit;}");
        sb.append(".cd-act:active{opacity:0.7;}");
        // 找空教室：按楼栋分组，房间用小块排布（整校区时可能有上百间）
        sb.append(".eq-group{margin-top:16px;}");
        sb.append(".eq-gh{display:flex;align-items:baseline;justify-content:space-between;");
        sb.append("padding:0 2px 7px;}");
        sb.append(".eq-gh b{font-size:calc(13px * var(--fs,1));color:var(--fg);}");
        sb.append(".eq-gh i{font-style:normal;font-size:calc(11px * var(--fs,1));color:var(--sub);}");
        sb.append(".eq-rooms{display:flex;flex-wrap:wrap;gap:8px;}");
        sb.append(".eq-room{background:var(--card);border-radius:10px;padding:9px 12px;");
        sb.append("font-size:calc(12.5px * var(--fs,1));color:var(--fg);line-height:1.3;}");
        sb.append(".eq-room:active{opacity:0.6;}");
        // 查询教室：自绘选择行 + 底部选择面板。
        // 原生 <select> 的弹出列表由系统绘制，各 ROM 差异极大（澎湃/三星/MIUI 各不相同），
        // 且无法搜索——教学楼动辄几十间教室，必须能过滤，所以自己画。
        sb.append(".cr-field{display:flex;align-items:center;gap:8px;background:var(--card);");
        sb.append("border-radius:12px;padding:0 14px;min-height:46px;font-size:calc(14px * var(--fs,1));color:var(--fg);");
        sb.append("box-sizing:border-box;}");
        sb.append(".cr-field:active{opacity:0.7;}");
        sb.append(".cr-field .crf-v{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;");
        sb.append("white-space:nowrap;}");
        sb.append(".cr-field.ph .crf-v{color:var(--sub);}");
        sb.append(".cr-field .crf-a{flex:0 0 auto;color:var(--sub);font-size:calc(13px * var(--fs,1));}");
        sb.append(".cr-field.lock{opacity:0.45;}");
        // 真正的 <select> 只做「值容器」，不参与显示
        sb.append(".cr-sel{position:absolute;left:-9999px;top:0;width:1px;height:1px;");
        sb.append("opacity:0;pointer-events:none;}");
        sb.append(".pk-mask{position:fixed;left:0;right:0;top:0;bottom:0;background:rgba(0,0,0,0.42);");
        sb.append("z-index:300;opacity:0;visibility:hidden;transition:opacity 0.2s;}");
        sb.append(".pk-mask.show{opacity:1;visibility:visible;}");
        sb.append(".pk-sheet{position:fixed;left:0;right:0;bottom:0;z-index:301;background:var(--bg);");
        sb.append("border-radius:18px 18px 0 0;max-height:74vh;display:flex;flex-direction:column;");
        sb.append("transform:translateY(102%);visibility:hidden;");
        sb.append("transition:transform 0.26s cubic-bezier(.22,.61,.36,1),visibility 0.26s;}");
        sb.append(".pk-sheet.show{transform:translateY(0);visibility:visible;}");
        sb.append(".pk-head{display:flex;align-items:center;justify-content:space-between;");
        sb.append("padding:15px 16px 9px;}");
        sb.append(".pk-title{font-size:calc(15px * var(--fs,1));font-weight:600;color:var(--fg);}");
        sb.append(".pk-cancel{background:none;border:none;padding:2px;font-size:calc(14px * var(--fs,1));color:#667eea;}");
        sb.append(".pk-search{padding:0 12px 8px;}");
        sb.append(".pk-search input{width:100%;box-sizing:border-box;background:var(--card);");
        sb.append("border:none;border-radius:10px;padding:10px 12px;font-size:calc(14px * var(--fs,1));color:var(--fg);");
        sb.append("font-family:inherit;}");
        sb.append(".pk-list{overflow-y:auto;-webkit-overflow-scrolling:touch;");
        sb.append("padding:2px 0 calc(12px + env(safe-area-inset-bottom,0px));}");
        sb.append(".pk-item{display:flex;align-items:center;justify-content:space-between;gap:10px;");
        sb.append("padding:13px 18px;font-size:calc(14px * var(--fs,1));color:var(--fg);line-height:1.45;}");
        sb.append(".pk-item:active{background:rgba(127,127,127,0.13);}");
        sb.append(".pk-item.on{color:#667eea;font-weight:600;}");
        sb.append(".pk-ck{display:none;color:#667eea;font-size:calc(14px * var(--fs,1));flex:0 0 auto;}");
        sb.append(".pk-item.on .pk-ck{display:block;}");
        sb.append(".pk-empty{padding:22px;text-align:center;color:var(--sub);font-size:calc(13px * var(--fs,1));}");
        // WebView 默认会给可点击元素加一层蓝色/灰色高亮，Android 上尤其明显。
        // 一律关掉，按压反馈交给各自的 :active 样式。
        sb.append("*{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;");
        sb.append("-webkit-tap-highlight-color:transparent;tap-highlight-color:transparent;outline:none;}");
        // 部分 ROM 的 WebView 仍会画出高亮框，再兜一层
        sb.append("a,button,div,span,li{-webkit-tap-highlight-color:transparent!important;}");
        sb.append("html,body{-webkit-tap-highlight-color:transparent;}");
        sb.append(".modal input,.modal textarea{-webkit-user-select:auto;user-select:auto;}");
        sb.append(".grid{display:grid;grid-template-columns:34px repeat(7,1fr);gap:3px;");
        sb.append("flex:1;min-height:0;grid-auto-rows:1fr;background:var(--card2);border-radius:14px;padding:8px;");
        sb.append("transition:background-color 0.35s ease;}");
        // 表头格子高度必须恒定：一行是「周一」+ 一行日期，共两行。
        // 列宽只有 40 出头，「11月10日」比「11月9日」多一个数字就会折成两行，
        // 把整行撑高、连带下面的课表网格一起往下跳 —— 所以这里锁死 nowrap。
        sb.append(".gh{text-align:center;font-weight:700;font-size:calc(12px * var(--fsg,1));padding:8px 2px;");
        sb.append("background:var(--card);border-radius:8px;color:var(--fg);");
        sb.append("white-space:nowrap;overflow:hidden;line-height:1.25;}");
        // 日期用略小的字号 + 负字距，「11月10日」这种 5 字日期也能塞进一格
        sb.append(".gh-date{font-size:calc(9px * var(--fsg,1));font-weight:400;color:#999;");
        sb.append("margin-top:2px;letter-spacing:-0.2px;white-space:nowrap;line-height:1.35;}");
        sb.append(".gh.today{background:#667eea;color:#fff;}");
        sb.append(".gh.today .gh-date{color:#ddd;}");
        sb.append(".ts{font-size:calc(10px * var(--fsg,1));text-align:center;color:#777;");
        sb.append("display:flex;flex-direction:column;align-items:center;justify-content:center;");
        sb.append("background:var(--card);border-radius:8px;padding:4px 2px;line-height:1.15;}");
        sb.append(".ts .ts-time{font-size:calc(8px * var(--fsg,1));color:#aaa;margin-top:2px;}");
        sb.append(".course{border-radius:10px;padding:9px 7px;font-size:calc(11px * var(--fsg,1));");
        sb.append("display:flex;flex-direction:column;justify-content:flex-start;cursor:pointer;position:relative;overflow:hidden;");
        sb.append("transition:transform 0.18s,opacity 0.18s,box-shadow 0.18s;}");
        // 长名称底部淡出遮罩（渐变到卡片底色，文字不超出卡片）
        sb.append(".course::after{content:'';position:absolute;left:0;right:0;bottom:0;height:14px;");
        sb.append("background:linear-gradient(rgba(255,255,255,0) 0,var(--cc,#fff) 100%);pointer-events:none;}");
        sb.append(".course:active{transform:scale(0.96);}");
        // 长按浮起拖动态：变大+微透明+深阴影
        sb.append(".course.dragging{opacity:0.72;transform:scale(1.07);z-index:50;");
        sb.append("box-shadow:0 10px 28px rgba(0,0,0,0.28);transition:none;animation:none;pointer-events:none;}");
        // 落点高亮
        sb.append(".drop-hint{outline:2.5px dashed #667eea;outline-offset:-2px;}");
        sb.append(".empty.drop-hint{background:#EEF0FF;}");
        sb.append(".course .name{font-weight:700;margin-bottom:3px;line-height:1.25;");
        sb.append("font-size:calc(12px * var(--fsg,1));color:#fff;}");
        sb.append(".course .info{font-size:calc(10px * var(--fsg,1));opacity:0.95;line-height:1.35;color:#fff;");
        sb.append("font-weight:600;letter-spacing:0.2px;}");
        sb.append(".empty{background:var(--empty);border-radius:8px;}");
        // ===== 与教务课程位置冲突：整格描红，并在右下角挂感叹号 =====
        // 用 box-shadow 而非 border：卡片高度是按 1fr 分配的，
        // border 会把内容区压小导致文字被裁，inset 阴影不占布局尺寸。
        sb.append(".course.conflict{box-shadow:inset 0 0 0 2.5px #FF3B30,0 0 0 1px rgba(255,59,48,0.35);}");
        // 有意叠加：琥珀色描边，不挂感叹号。用户自己把课压在同一格上，不该像报错。
        sb.append(".course.overlap{box-shadow:inset 0 0 0 2.5px #F2A24B,0 0 0 1px rgba(242,162,75,0.4);}");
        sb.append(".course.remind-on.conflict{box-shadow:inset 0 0 0 2.5px #FF3B30,");
        sb.append("inset 0 0 0 5px rgba(255,214,71,0.92);}");
        sb.append(".clash-mark{position:absolute;right:4px;bottom:4px;width:15px;height:15px;");
        sb.append("border-radius:50%;background:#FF3B30;color:#fff;font-size:calc(11px * var(--fsg,1));font-weight:800;");
        sb.append("line-height:15px;text-align:center;z-index:14;box-shadow:0 1px 4px rgba(0,0,0,0.3);}");
        // ===== 加课模式：空格子变成可点选的占位 =====
        sb.append("body.adding .empty{border:2px dashed #667eea;background:rgba(102,126,234,0.10);");
        sb.append("display:flex;align-items:center;justify-content:center;cursor:pointer;}");
        sb.append("body.adding .empty::after{content:'+';color:#667eea;font-size:calc(20px * var(--fs,1));font-weight:700;");
        sb.append("line-height:1;opacity:0.75;}");
        // 加课模式：已有课的格子同样可点（重叠加课），用虚线描边表示「可叠加」。
        // 不能沿用 opacity 压暗——那样像禁用状态，而且文字会一起变淡。
        sb.append("body.adding .course{outline:2.5px dashed #667eea;outline-offset:-2.5px;");
        sb.append("filter:saturate(0.9);cursor:pointer;}");
        // 顶部提示条
        sb.append(".addbar{display:none;align-items:center;justify-content:space-between;gap:8px;");
        sb.append("margin-top:8px;padding:8px 12px;border-radius:12px;background:rgba(102,126,234,0.12);");
        sb.append("font-size:calc(12px * var(--fs,1));color:#5A6FD6;font-weight:600;}");
        sb.append("body.adding .addbar{display:flex;}");
        sb.append(".addbar button{background:#667eea;color:#fff;border:none;border-radius:8px;");
        sb.append("padding:5px 10px;font-size:calc(12px * var(--fs,1));}");
        sb.append(".grid-head{display:grid;grid-template-columns:34px repeat(7,1fr);gap:3px;margin-bottom:4px;padding:0 8px;}");
        // 拖动模式底部操作栏（横向拉长、低高度；拖动中下滑隐藏）
        // **必须抬到底部标签栏上方**：标签栏是 bottom:0 的固定层（高 56px + 安全区），
        // 操作栏若还停在 bottom:10px，就会被标签栏整块盖住 —— 「应用/取消/撤销」全点不到。
        sb.append(".dockbar{position:fixed;left:50%;");
        sb.append("bottom:calc(56px + env(safe-area-inset-bottom,0px) + 10px);");
        sb.append("transform:translate(-50%,150%);");
        sb.append("background:#fff;border-radius:16px;box-shadow:0 8px 32px rgba(0,0,0,0.2);");
        sb.append("display:flex;gap:6px;padding:6px 8px;z-index:80;opacity:0;");
        sb.append("width:calc(100% - 24px);max-width:420px;");
        sb.append("transition:transform 0.3s cubic-bezier(.22,.68,.36,1),opacity 0.3s ease;}");
        sb.append(".dockbar.show{transform:translate(-50%,0);opacity:1;}");
        sb.append(".dockbar.hide-drag{transform:translate(-50%,150%);opacity:0;}");
        sb.append(".db-btn{border:none;border-radius:12px;padding:9px 10px;font-size:calc(12px * var(--fs,1));font-weight:600;}");
        sb.append(".db-btn:disabled{opacity:0.4;}");
        sb.append(".db-apply{background:#667eea;color:#fff;flex:2.2;order:2;font-size:calc(14px * var(--fs,1));}");
        sb.append(".db-cancel{background:#F0F0F5;color:#555;flex:1;order:1;}");
        sb.append(".db-undo{background:#F0F0F5;color:#555;flex:1;order:3;}");
        // 课程卡片入场动画：由小变大（缩放淡入），**全屏卡片同时开始，不再按行列错峰**。
        // 之前用 (row+d)*0.05s 的延迟做出「左上到右下依次弹出」的级联，整屏 60+ 张卡片
        // 要花近 1 秒才铺满，而且每次都重播，观感很吵。现在统一 0 延迟一起弹。
        //
        // fill-mode 必须是 backwards：
        //   - backwards：动画延迟期间就应用起始帧(opacity:0,scale)，
        //     否则卡片会先以最终样式整块闪出来、等延迟结束后再从头播，视觉上
        //     就是"先直接显示 → 消失 → 再放大"
        //   - 不能用 both/forwards：那会在结束后锁死 transform，
        //     导致 .dragging 的 scale(1.07) 与 :active 的 scale(0.96) 全部失效
        sb.append("@keyframes popIn{from{opacity:0;transform:scale(0.86);}");
        sb.append("to{opacity:1;transform:scale(1);}}");
        sb.append(".course{animation:popIn 0.18s cubic-bezier(.34,1.32,.64,1) backwards;}");
        // ===== 切周：网格横向跟手滑动（不重载整页）=====
        // .tabview 是课表主体的容器（标题 + 日期行 + 按钮 + 表头 + 网格）。
        // 切标签时它左移让位；切周只动里面的 .weekstrip。
        // 这里刻意不写 will-change：常驻的 will-change 会把这整层永久提升为合成层，
        // 白白占显存并让每次重绘都走 GPU 合成；动画期间浏览器自己会临时提升。
        sb.append(".tabview{flex:1;display:flex;flex-direction:column;min-height:0;");
        sb.append("transition:transform 0.22s cubic-bezier(.22,.68,.36,1);}");
        // 切标签时课表主体左移让位：视觉上是"翻页推走"，而不是被新页盖住
        sb.append(".tabview.shift-out{transform:translateX(-16%);}");
        // ---- 周横条：视口裁切层 + 300% 宽的横条 + 三个等宽面板 ----
        // 面板宽度取横条的 1/3（= 视口宽）。基准位移 -33.3333% 就是把中间那块对齐视口。
        // 位移用 calc(-33.3333% + Npx)：百分比相对横条自身宽度，px 是手指位移，两者可叠加。
        sb.append(".gridview{flex:1;min-height:0;overflow:hidden;position:relative;}");
        sb.append(".weekstrip{display:flex;width:300%;height:100%;");
        sb.append("transform:translateX(-33.3333%);");
        sb.append("transition:transform 0.28s cubic-bezier(.22,.68,.36,1);}");
        // 拖动中必须关掉过渡，否则每一帧都会被动画追尾，手感发黏
        sb.append(".weekstrip.dragging{transition:none;}");
        sb.append(".weekpanel{flex:0 0 33.3333%;height:100%;display:flex;flex-direction:column;min-width:0;}");
        // 新插入的面板在屏幕外，别让它播卡片入场动画（白耗一帧合成）
        sb.append(".weekpanel.nofx .course{animation:none;}");
        // 拖动落位/撤销：不要入场动画，直接从浮动状态静止落位
        sb.append(".course.no-anim{animation:none!important;}");
        // ===== 课程详情弹窗：仿鸿蒙/ColorOS「应用从图标展开成窗口」 =====
        //
        // 思路：不做整体缩放。卡片是细高条、弹窗是宽扁块，任何 scale 都会变形。
        // 改为「窗口生长」：一个面板从卡片矩形(位置/尺寸/圆角)平滑过渡到弹窗矩形，
        // 内容始终按最终尺寸排版，只在窗口成形后淡入。这样只有边界在动，文字不变形。
        // 遮罩：显隐与深浅完全交给 CSS 类控制。
        // 不要在 JS 里设置/清空 background —— 清空后会回落到这里的默认值，
        // 若默认值与 .show 不同就会出现"页面先暗一下再亮"的闪烁。
        sb.append(".modal-bg{display:flex;align-items:center;justify-content:center;position:fixed;");
        sb.append("top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0);z-index:100;");
        sb.append("visibility:hidden;pointer-events:none;");
        sb.append("transition:background-color 220ms ease,visibility 0s linear 220ms;}");
        sb.append(".modal-bg.show{visibility:visible;pointer-events:auto;background:rgba(0,0,0,0.4);");
        sb.append("transition:background-color 220ms ease,visibility 0s;}");

        // 生长面板：定位用 left/top/width/height（不用 transform，避免与内容缩放纠缠）
        //
        // visibility 由 .modal-bg.show 同步驱动，且不参与过渡（0s）：
        // 关闭时移除 .show 会让面板「同一帧内立刻不可见」，之后清空内联的
        // left/top/width/height 就不会再露出「弹回居中大尺寸」的一帧。
        // 若把可见性交给遮罩那 220ms 的延迟过渡，面板会在收缩完成后
        // 又整块闪回一次（实测 46px → 340px）。
        sb.append(".modal{position:fixed;background:var(--modal);");
        sb.append("width:85%;max-width:340px;padding:20px;box-sizing:border-box;");
        sb.append("box-shadow:0 12px 40px rgba(0,0,0,0.32);");
        sb.append("will-change:left,top,width,height,border-radius;");
        sb.append("visibility:hidden;");
        sb.append("border-radius:18px;overflow:hidden;}");
        sb.append(".modal-bg.show .modal{visibility:visible;}");
        // 内容层：始终按最终尺寸渲染，只做透明度
        sb.append(".modal .m-inner{opacity:1;}");

        sb.append(".modal h2{font-size:calc(16px * var(--fs,1));font-weight:700;margin-bottom:12px;color:var(--fg);}");
        sb.append(".modal .row{display:flex;padding:8px 0;border-bottom:1px solid var(--line);}");
        sb.append(".modal .row .label{color:var(--sub);font-size:calc(13px * var(--fs,1));min-width:70px;}");
        sb.append(".modal .row .val{color:var(--fg);font-size:calc(13px * var(--fs,1));flex:1;}");
        sb.append(".modal #cmBody{max-height:58vh;overflow-y:auto;-webkit-overflow-scrolling:touch;}");
        sb.append(".modal .cblock{margin-top:2px;}");
        sb.append(".modal .cblock+.cblock{border-top:1px solid var(--line);margin-top:14px;padding-top:14px;}");
        sb.append(".modal .cb-name{font-size:calc(15px * var(--fs,1));font-weight:700;color:var(--fg);margin-bottom:4px;}");
        sb.append(".modal .cb-tag{font-size:calc(11px * var(--fs,1));color:var(--sub);font-weight:400;margin-left:6px;}");
        sb.append(".modal-close{display:block;margin:16px auto 0;background:#667eea;color:#fff;");
        sb.append("border:none;border-radius:12px;padding:8px 32px;font-size:calc(14px * var(--fs,1));}");
        // 设置弹窗
        sb.append(".settings-modal{background:var(--modal);border-radius:16px;width:88%;max-width:360px;");
        sb.append("overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,0.3);");
        sb.append("animation:slideUp 0.25s ease-out;max-height:82vh;");
        sb.append("display:flex;flex-direction:column;transition:background-color 0.3s ease;}");
        // 标题与标签栏固定，仅内容区滚动
        sb.append(".settings-modal h2{font-size:calc(18px * var(--fs,1));font-weight:700;padding:20px 20px 12px;color:var(--fg);");
        sb.append("flex:0 0 auto;}");
        sb.append(".tab-bar{display:flex;border-bottom:1px solid var(--line);flex:0 0 auto;}");
        sb.append(".tab{flex:1;text-align:center;padding:10px;font-size:calc(14px * var(--fs,1));color:var(--sub);");
        sb.append("border-bottom:2px solid transparent;}");
        sb.append(".tab.active{color:#667eea;border-bottom-color:#667eea;font-weight:600;}");
        sb.append(".tab-content{padding:16px 20px;overflow-y:auto;-webkit-overflow-scrolling:touch;flex:1 1 auto;}");
        sb.append("@keyframes tabIn{from{opacity:0;transform:translateX(14px);}to{opacity:1;transform:translateX(0);}}");
        sb.append(".tab-in{animation:tabIn 0.22s ease-out;}");
        sb.append(".settings-item{display:flex;justify-content:space-between;align-items:center;");
        sb.append("padding:14px 0;border-bottom:1px solid var(--line);}");
        sb.append(".settings-item .si-label{font-size:calc(15px * var(--fs,1));color:var(--fg);}");
        sb.append(".settings-item .si-desc{font-size:calc(12px * var(--fs,1));color:var(--sub);margin-top:2px;}");
        sb.append(".logout-btn{display:block;width:100%;margin-top:20px;background:#FF4444;color:#fff;");
        sb.append("border:none;border-radius:12px;padding:12px;font-size:calc(15px * var(--fs,1));font-weight:600;}");
        // 设置页按钮统一样式（跟随主题，带按压反馈）
        sb.append(".adv-btn{background:var(--card);border:1px solid var(--line);");
        sb.append("border-radius:10px;padding:9px 14px;font-size:calc(13px * var(--fs,1));color:var(--fg);");
        sb.append("cursor:pointer;transition:background-color 0.2s ease,border-color 0.2s ease;}");
        sb.append(".adv-btn:active{opacity:0.75;}");
        sb.append(".si-hint{font-size:calc(12px * var(--fs,1));color:#999;margin-top:8px;line-height:1.5;}");
        // ===== 单开关按钮（设置项右侧，与文字同一行）=====
        sb.append(".switch{width:48px;height:28px;border-radius:14px;background:#D8D8E0;border:none;");
        sb.append("padding:0;position:relative;flex:0 0 auto;cursor:pointer;");
        sb.append("transition:background-color 0.2s ease;}");
        sb.append(".switch.on{background:#667eea;}");
        sb.append(".switch i{position:absolute;top:3px;left:3px;width:22px;height:22px;border-radius:50%;");
        sb.append("background:#fff;box-shadow:0 1px 3px rgba(0,0,0,0.25);");
        sb.append("transition:left 0.2s cubic-bezier(.3,.8,.4,1);}");
        sb.append(".switch.on i{left:23px;}");
        // 强制生效/不适用时置灰：开关仍显示真实状态，只是不可点
        sb.append(".switch:disabled{cursor:default;opacity:0.55;}");
        sb.append("body.dark .switch{background:#3A3B4A;}");
        // 深色下「开」的紫色底要被单独补回来：body.dark .switch 的特异性高于 .switch.on，
        // 不补的话深色主题里所有开关都看不出是开着的（一直是灰底）。
        sb.append("body.dark .switch.on{background:#667eea;}");
        // ===== 字体大小：滑块 + 档位按钮 =====
        // 滑块负责连续调节（实时预览），下面四个档位负责一键回到常用值。
        // 两者共用 applyFontScale()，每次调节都会同步滑块位置、数值文字与档位高亮。
        sb.append(".fs-wrap{display:flex;align-items:center;gap:10px;margin-top:12px;}");
        sb.append(".fs-wrap .fs-a{font-size:calc(13px * var(--fs,1));color:var(--sub);flex:0 0 auto;}");
        sb.append(".fs-wrap .fs-b{font-size:calc(19px * var(--fs,1));color:var(--sub);flex:0 0 auto;}");
        sb.append(".fs-range{flex:1;min-width:0;-webkit-appearance:none;appearance:none;height:4px;");
        sb.append("border-radius:2px;background:var(--line);outline:none;margin:0;}");
        sb.append(".fs-range::-webkit-slider-thumb{-webkit-appearance:none;width:20px;height:20px;");
        sb.append("border-radius:50%;background:#667eea;box-shadow:0 1px 4px rgba(0,0,0,0.28);}");
        sb.append(".fs-val{flex:0 0 auto;min-width:44px;text-align:right;");
        sb.append("font-size:calc(13px * var(--fs,1));color:#667eea;font-weight:700;}");
        sb.append(".fs-presets{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;}");
        // ===== 自定义课程：表单与列表 =====
        sb.append(".cf-label{display:block;font-size:calc(13px * var(--fs,1));color:var(--sub);margin:14px 0 6px;}");
        sb.append(".cf-input{width:100%;background:var(--card);border:1px solid var(--line);");
        sb.append("border-radius:10px;padding:11px 12px;font-size:calc(15px * var(--fs,1));color:var(--fg);");
        sb.append("box-sizing:border-box;font-family:inherit;-webkit-appearance:none;}");
        sb.append(".cf-input:focus{outline:none;border-color:#667eea;}");
        sb.append(".cf-row{display:flex;gap:10px;}");
        sb.append(".cf-row>div{flex:1;min-width:0;}");
        // 重复方式分段选择：仅本周 / 按周次
        sb.append(".cf-seg{display:flex;gap:8px;}");
        sb.append(".cf-seg-item{flex:1;background:var(--card);border:1px solid var(--line);");
        sb.append("color:var(--sub);border-radius:10px;padding:10px 8px;font-size:calc(13px * var(--fs,1));");
        sb.append("font-family:inherit;transition:all 0.16s ease;}");
        sb.append(".cf-seg-item.sel{background:#667eea;border-color:#667eea;color:#fff;font-weight:600;}");
        sb.append(".cf-colors{display:flex;flex-wrap:wrap;gap:10px;margin-top:4px;}");
        sb.append(".cf-color{width:30px;height:30px;border-radius:50%;border:none;padding:0;");
        sb.append("box-shadow:0 0 0 0 #667eea;transition:box-shadow 0.16s ease;}");
        sb.append(".cf-color.sel{box-shadow:0 0 0 2px var(--bg),0 0 0 4px #667eea;}");
        sb.append(".cf-actions{display:flex;gap:10px;margin-top:24px;}");
        sb.append(".cf-save{flex:1;background:#667eea;color:#fff;border:none;border-radius:12px;");
        sb.append("padding:13px;font-size:calc(15px * var(--fs,1));font-weight:600;}");
        sb.append(".cf-del{flex:0 0 auto;background:#FFF0F0;color:#e55;border:none;border-radius:12px;");
        sb.append("padding:13px 18px;font-size:calc(15px * var(--fs,1));font-weight:600;}");
        sb.append(".cf-tip{font-size:calc(11px * var(--fs,1));color:var(--sub);margin-top:6px;line-height:1.5;}");
        // 叠加加课提示（目标格已有课）：琥珀色，与卡片上的叠加描边同一语义
        sb.append(".cf-overlap{font-size:calc(12px * var(--fs,1));color:#8A5A12;background:rgba(242,162,75,0.14);");
        sb.append("border-left:3px solid #F2A24B;border-radius:8px;padding:9px 11px;");
        sb.append("margin-top:12px;line-height:1.55;}");
        sb.append("body.dark .cf-overlap{color:#F2C078;background:rgba(242,162,75,0.18);}");
        sb.append(".cc-item{display:flex;align-items:center;gap:10px;padding:12px 0;");
        sb.append("border-bottom:1px solid var(--line);}");
        sb.append(".cc-item:last-child{border-bottom:none;}");
        sb.append(".cc-dot{width:10px;height:10px;border-radius:50%;flex:0 0 auto;}");
        sb.append(".cc-txt{flex:1;min-width:0;}");
        sb.append(".cc-txt b{display:block;font-size:calc(14px * var(--fs,1));font-weight:600;color:var(--fg);");
        sb.append("white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}");
        sb.append(".cc-txt i{display:block;font-style:normal;font-size:calc(11px * var(--fs,1));color:var(--sub);margin-top:3px;}");
        sb.append(".cc-del{background:transparent;border:none;color:#E5533D;font-size:calc(12px * var(--fs,1));");
        sb.append("padding:8px;flex:0 0 auto;}");
        sb.append(".cc-empty{text-align:center;color:var(--sub);font-size:calc(13px * var(--fs,1));padding:34px 0;line-height:1.7;}");
        // 自定义课程详情标签、页面内 toast
        sb.append(".cb-src{display:inline-block;font-size:calc(10px * var(--fs,1));font-weight:600;color:#667eea;");
        sb.append("background:rgba(102,126,234,0.12);border-radius:6px;padding:1px 6px;margin-left:6px;");
        sb.append("vertical-align:middle;}");
        sb.append(".cb-clash{font-size:calc(12px * var(--fs,1));color:#E5533D;background:rgba(255,59,48,0.08);");
        sb.append("border-radius:8px;padding:8px 10px;margin-top:8px;line-height:1.5;}");
        // toast 同样要避开底部标签栏，否则会贴着导航栏边缘
        sb.append(".toast{position:fixed;left:50%;");
        sb.append("bottom:calc(56px + env(safe-area-inset-bottom,0px) + 16px);");
        sb.append("transform:translateX(-50%);");
        sb.append("background:rgba(0,0,0,0.82);color:#fff;padding:9px 16px;border-radius:20px;");
        sb.append("font-size:calc(13px * var(--fs,1));opacity:0;pointer-events:none;transition:opacity 0.2s ease;");
        sb.append("z-index:200;max-width:80%;text-align:center;line-height:1.4;}");
        sb.append(".toast.show{opacity:1;}");
        // ===== 检查更新页 =====
        sb.append(".up-block{margin-top:16px;background:var(--card);border-radius:12px;padding:14px;}");
        sb.append(".up-block-title{font-size:calc(13px * var(--fs,1));font-weight:700;color:var(--fg);margin-bottom:8px;}");
        sb.append(".up-log{font-size:calc(13px * var(--fs,1));color:var(--fg);line-height:1.7;");
        sb.append("white-space:pre-wrap;word-break:break-word;}");
        sb.append(".up-empty{font-size:calc(13px * var(--fs,1));color:var(--sub);line-height:1.7;}");
        // 公告/日志里的【前缀】着色（任务书第五章）
        sb.append(".up-tag{color:#667eea;font-weight:700;margin-right:2px;}");
        sb.append("body.dark .up-tag{color:#9AA6FF;}");
        sb.append(".up-progress{margin-top:16px;}");
        sb.append(".up-bar{height:8px;border-radius:4px;background:var(--line);overflow:hidden;}");
        sb.append(".up-bar i{display:block;height:100%;width:0;background:#667eea;");
        sb.append("transition:width 0.2s ease;}");
        sb.append(".up-prog-txt{font-size:calc(12px * var(--fs,1));color:var(--sub);margin-top:6px;text-align:center;}");
        // 空课周提示
        sb.append(".emptyweek{grid-column:1/-1;text-align:center;color:var(--sub);font-size:calc(13px * var(--fs,1));padding:40px 0;}");
        // 空周：唯一那一行撑满整块高度，提示文字垂直居中（否则空网格看着像塌了）
        sb.append(".grid.empty-grid{grid-template-rows:1fr;}");
        sb.append(".grid.empty-grid .emptyweek{align-self:center;padding:0;}");
        // 自定义图片背景：卡片/控件半透明，文字保持原色
        sb.append("body.hasbg .grid{background:transparent;}");
        sb.append("body.hasbg .gh{background:rgba(240,240,245,0.62);}");
        sb.append("body.hasbg .ts{background:rgba(240,240,245,0.62);}");
        sb.append("body.hasbg .empty{background:transparent;}");
        sb.append("body.hasbg .btn{background:rgba(240,240,245,0.62);}");
        sb.append("body.hasbg .btn-nav{background:rgba(240,240,245,0.62);}");
        sb.append("body.hasbg .course{background:transparent;}");
        sb.append("body.hasbg .course::before{content:'';position:absolute;inset:0;");
        sb.append("background:var(--cc);opacity:0.84;z-index:0;}");
        sb.append("body.hasbg .course .name,body.hasbg .course .info{position:relative;z-index:1;}");
        sb.append("body.hasbg .course::after{opacity:0.84;}");
        sb.append("body.hasbg.dark .grid{background:transparent;}");
        sb.append("body.hasbg.dark .gh{background:rgba(30,31,42,0.62);}");
        sb.append("body.hasbg.dark .ts{background:rgba(30,31,42,0.62);}");
        sb.append("body.hasbg.dark .empty{background:transparent;}");
        sb.append("body.hasbg.dark .btn{background:rgba(30,31,42,0.62);}");
        sb.append("body.hasbg.dark .btn-nav{background:rgba(30,31,42,0.62);}");
        // 深色 + 背景图：原来这里是 88% 的课程色（整屏彩色）。改成灰，
        // 让背景图透过半透明灰卡显示，与无背景图时的纯灰卡一致。
        sb.append("body.hasbg.dark .course::before{background:#1A1B1D!important;opacity:0.62;}");
        sb.append("body.hasbg.dark .course::after{opacity:0.88;}");
        // 有自定义背景图时弹窗半透明（毛玻璃观感）
        sb.append("body.hasbg .settings-modal{background:rgba(255,255,255,0.78);}");
        sb.append("body.hasbg.dark .settings-modal{background:rgba(22,23,34,0.78);}");
        // 课程详情弹窗同样要半透明。这里的 alpha 必须与 showCourseDetail 里
        // playOpenAnim 的终点色 paleBg(0.72) 一致：动画结束时 Java 侧会清掉内联
        // background，改由 CSS 的 --modal 接管，若两处不一致，完全弹出后会"咔"地
        // 跳成不透明纯白（深色主题则为纯黑）。
        sb.append("body.hasbg .modal{background:rgba(255,255,255,0.72);}");
        sb.append("body.hasbg.dark .modal{background:rgba(0,0,0,0.72);}");
        sb.append("body.hasbg .modal-close{background:rgba(102,126,234,0.92);}");
        // 底部标签栏与整页容器：有背景图时改成半透明毛玻璃，图能透出来。
        // 不加这两条的话，标签栏用的是不透明的 --bg，背景图到最底下就被一块实色截断了。
        // 底部标签栏镜像顶部 scrim 的做法：上缘完全透明、往下渐实。
        // 均匀半透明色带会在深色照片上切出一条生硬的亮带（用户反馈"和刘海颜色不一致"），
        // 渐变后顶边融进照片，与顶部 scrim「从边缘往里渐实」的方向感一致。
        // blur 仍作用于整个元素：透明上缘处露出的是柔化后的照片，过渡更自然。
        sb.append("body.hasbg .tabbar{background:linear-gradient(180deg,");
        sb.append("rgba(255,255,255,0) 0%,rgba(255,255,255,0.60) 30%,rgba(255,255,255,0.85) 100%);");
        sb.append("border-top-color:transparent;");
        sb.append("-webkit-backdrop-filter:blur(20px) saturate(120%);");
        sb.append("backdrop-filter:blur(20px) saturate(120%);}");
        sb.append("body.hasbg.dark .tabbar{background:linear-gradient(180deg,");
        sb.append("rgba(10,10,12,0) 0%,rgba(10,10,12,0.50) 30%,rgba(10,10,12,0.72) 100%);");
        sb.append("border-top-color:transparent;}");
        // 页面盖住课表时（我的/设置），标签栏改用与 .page 相同的玻璃底：
        // 照片底部往往比页面处深得多，渐变再淡也会透出一条暗带，
        // 用户看到的"底栏和刘海颜色不一致"就是这条。统一成页面色后整屏一块玻璃。
        sb.append("body.hasbg.pagecover .tabbar{background:rgba(255,255,255,0.82);}");
        sb.append("body.hasbg.dark.pagecover .tabbar{background:rgba(8,8,10,0.72);}");
        sb.append("body.hasbg .page{background:rgba(255,255,255,0.76);");
        sb.append("-webkit-backdrop-filter:blur(12px) saturate(118%);");
        sb.append("backdrop-filter:blur(12px) saturate(118%);}");
        sb.append("body.hasbg.dark .page{background:rgba(8,8,10,0.62);}");
        // 我的/设置页里的卡片与按钮模块玻璃化：之前只有整页容器半透明，
        // 卡片还是实色 --card，页面看起来"黑一块白一块"。
        sb.append("body.hasbg .me-card,body.hasbg .nav-card,body.hasbg .about-card,");
        sb.append("body.hasbg .up-block,body.hasbg .cr-card,body.hasbg .cr-field,");
        sb.append("body.hasbg .eq-room,body.hasbg .pk-sheet{background:rgba(255,255,255,0.62);");
        sb.append("-webkit-backdrop-filter:blur(10px) saturate(120%);");
        sb.append("backdrop-filter:blur(10px) saturate(120%);}");
        sb.append("body.hasbg.dark .me-card,body.hasbg.dark .nav-card,body.hasbg.dark .about-card,");
        sb.append("body.hasbg.dark .up-block,body.hasbg.dark .cr-card,body.hasbg.dark .cr-field,");
        sb.append("body.hasbg.dark .eq-room,body.hasbg.dark .pk-sheet{background:rgba(22,23,34,0.62);}");
        // 危险行保持红底实色：半透明红在照片上读不出"删除/补救"的语义
        sb.append("body.hasbg .nav-item.danger,body.hasbg.dark .nav-item.danger{background:#E5484D;}");        // ===== 雾面玻璃卡片（.glass）=====
        // 为什么需要它：深色主题下卡片用的是高饱和实色（#667EEA 这类），
        // 黑暗环境里整屏十几次亮度很高的色块，看着晃眼。
        //
        // 做法：卡片底色换成「极低透明度的白 + 一点点 --cc 色相」，
        // 文字仍是白色；色相只留 20%，课程的区分度靠色相而不是靠亮度。
        // 顺序很关键：这段必须放在 body.hasbg 的 ::before 之后，同特异性下后定义者胜出。
        // 只有深色主题挂 .glass（浅色主题沿用原来的实色卡片，见下方 glassOn 判定）。
        // 深色卡片用「灰底」而不是近纯黑：纯黑背景上若卡片也是黑的，
        // 整屏就只剩十几道描边，分不清卡片边界。灰底 + 对角边缘光一起才读得出层次。
        sb.append("body.dark.glass .course{background:radial-gradient(120% 90% at 0% 0%,");
        sb.append("rgba(255,255,255,0.06),rgba(255,255,255,0) 62%),#1A1B1D!important;}");
        // 有背景图时卡片必须半透明，否则整块灰会把背景图糊死；
        // 这里的 alpha 与下方 backdrop-filter 配合，观感仍是「深色玻璃」。
        sb.append("body.dark.glass.hasbg .course{background:radial-gradient(120% 90% at 0% 0%,");
        sb.append("rgba(255,255,255,0.06),rgba(255,255,255,0) 62%),rgba(26,27,29,0.62)!important;}");
        // 真·雾面：只在有背景图时开（平底色上模糊没有视觉收益，纯烧 GPU）
        sb.append("body.glass.hasbg .course{-webkit-backdrop-filter:blur(14px) saturate(130%);");
        sb.append("backdrop-filter:blur(14px) saturate(130%);}");
        // 深色卡片不带任何课程色：纯灰底 + 白字 + 边缘光。
        // 曾经叠过 8% 的 --cc 做科目区分，用户在真机上看还是"有颜色"，要求全灰。
        // ::before 仍要占住（有背景图那套规则会用到），这里只把它置为透明。
        sb.append("body.dark.glass .course::before{content:'';position:absolute;inset:0;");
        sb.append("border-radius:inherit;background:transparent;opacity:0;z-index:0;}");
        // 对角边缘光（模仿 ColorOS 按钮的边缘发光）：
        // ::after 原本是底部淡出遮罩，这里改成 1.5px 的渐变描边环 ——
        // 用 padding + mask 只保留边框那一圈，渐变沿 135° 走：
        // 左上最亮 → 中间几乎透明 → 右下略微回亮，形成对角走向的「光」。
        // 直接用 inset box-shadow 也能做，但那样只有均匀亮边，没有方向感。
        sb.append(".glass .course::after{content:'';position:absolute;inset:0;top:0;height:auto;");
        sb.append("left:0;right:0;bottom:0;border-radius:inherit;background:none;pointer-events:none;");
        sb.append("padding:1.5px;box-sizing:border-box;");
        sb.append("background:linear-gradient(135deg,rgba(255,255,255,0.78) 0%,");
        sb.append("rgba(255,255,255,0.16) 28%,rgba(255,255,255,0.03) 50%,");
        sb.append("rgba(255,255,255,0.03) 64%,rgba(255,255,255,0.26) 100%);");
        sb.append("-webkit-mask:linear-gradient(#000,#000) content-box,linear-gradient(#000,#000);");
        sb.append("-webkit-mask-composite:xor;");
        sb.append("mask:linear-gradient(#000,#000) content-box,linear-gradient(#000,#000);");
        sb.append("mask-composite:exclude;}");
        // 深色主题的边缘光用课程色而不是白色：
        // 卡片本身是统一的灰，唯一能区分科目的地方就是这一圈光。
        // 左上最亮（0.95）→ 中段收薄（0.14）→ 右下回亮（0.70），保留 135° 的方向感。
        sb.append("body.dark.glass .course::after{padding:2px;");
        sb.append("background:linear-gradient(135deg,");
        sb.append("rgba(var(--cc-rgb,150,160,175),0.98) 0%,");
        sb.append("rgba(var(--cc-rgb,150,160,175),0.72) 26%,");
        sb.append("rgba(var(--cc-rgb,150,160,175),0.60) 50%,");
        sb.append("rgba(var(--cc-rgb,150,160,175),0.60) 66%,");
        sb.append("rgba(var(--cc-rgb,150,160,175),0.86) 100%);}");
        // 外发光：一圈极淡的同色晕，让边框有"亮着"的感觉而不是干瘪的线条。
        // 用负扩展压掉大部分，只留贴着卡片的薄薄一层，避免整张卡片糊成一团。
        sb.append("body.dark.glass .course{box-shadow:0 0 9px -3px var(--cc);}");
        // 长按浮起必须压过上面这条（特异性更高），否则拖动时只剩一圈微光，
        // 看不出卡片被"拿起来"了。
        sb.append("body.dark.glass .course.dragging{");
        sb.append("box-shadow:0 12px 30px rgba(0,0,0,0.6),0 0 10px -2px var(--cc)!important;}");
        // 灰底上的文字用白色（浅色主题不挂 .glass，仍是原来的白字 + 实色卡）
        sb.append(".glass .course .name{color:#FFFFFF;position:relative;z-index:1;}");
        sb.append(".glass .course .info{color:rgba(255,255,255,0.86);opacity:1;");
        sb.append("position:relative;z-index:1;}");
        // 冲突/叠加的整格描边不能被雾面盖掉：这两种状态的优先级更高，单独再声明一次
        sb.append(".glass .course.conflict{box-shadow:inset 0 0 0 2.5px #FF3B30,0 0 0 1px rgba(255,59,48,0.35);}");
        sb.append(".glass .course.overlap{box-shadow:inset 0 0 0 2.5px #F2A24B,0 0 0 1px rgba(242,162,75,0.4);}");
        // 应用主题与自定义背景图
        String theme = store != null ? store.getTheme() : "light";
        String bgName = store != null ? store.getBgImage() : "";
        // ===== 独立设置页样式 =====
        // 页面让出底部标签栏：bottom 不写 0，标签栏才能在任何页面下都露出来
        sb.append(".page{position:fixed;top:0;left:0;right:0;");
        sb.append("bottom:calc(56px + env(safe-area-inset-bottom,0px));background:var(--bg);z-index:95;");
        sb.append("display:flex;flex-direction:column;visibility:hidden;transform:translateX(100%);");
        sb.append("transition:transform 0.26s cubic-bezier(.22,.68,.36,1),visibility 0.26s ease;}");
        sb.append(".page.show{visibility:visible;transform:translateX(0);}");
        // ===== 标签之间横滑：旧页滑走、新页同时滑入，中间不再「收回再弹出」=====
        // 用 animation 而不是 transition：切标签时旧页要往「与进入方向相反」的一侧滑走，
        // 若靠 transition，先移除 .show 会让它往默认的 +100% 弹回，就是用户看到的抽搐。
        // 动画跑完由 JS 把该页 display:none，避免它在后台又溜回原位。
        sb.append("@keyframes tabOutLeft{from{transform:translateX(0);}to{transform:translateX(-100%);}}");
        sb.append("@keyframes tabOutRight{from{transform:translateX(0);}to{transform:translateX(100%);}}");
        sb.append("@keyframes tabInRight{from{transform:translateX(100%);}to{transform:translateX(0);}}");
        sb.append("@keyframes tabInLeft{from{transform:translateX(-100%);}to{transform:translateX(0);}}");
        sb.append(".page.leave-left{visibility:visible;transition:none;");
        sb.append("animation:tabOutLeft 0.2s cubic-bezier(.4,0,.7,1) forwards;}");
        sb.append(".page.leave-right{visibility:visible;transition:none;");
        sb.append("animation:tabOutRight 0.2s cubic-bezier(.4,0,.7,1) forwards;}");
        sb.append(".page.enter-right{visibility:visible;transition:none;");
        sb.append("animation:tabInRight 0.22s cubic-bezier(.22,.68,.36,1) forwards;}");
        sb.append(".page.enter-left{visibility:visible;transition:none;");
        sb.append("animation:tabInLeft 0.22s cubic-bezier(.22,.68,.36,1) forwards;}");
        sb.append(".page-head{display:flex;align-items:center;padding:8px;border-bottom:1px solid var(--line);flex:0 0 auto;}");
        sb.append(".ph-back{background:transparent;border:none;font-size:calc(26px * var(--fs,1));color:var(--fg);width:48px;height:48px;cursor:pointer;}");
        sb.append(".ph-title{flex:1;text-align:center;font-size:calc(17px * var(--fs,1));font-weight:700;color:var(--fg);}");
        sb.append(".ph-pad{width:48px;flex:0 0 auto;}");
        sb.append(".page-body{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:16px;}");
        sb.append(".nav-card{background:var(--card);border-radius:14px;overflow:hidden;}");
        sb.append(".nav-item{display:flex;align-items:center;padding:16px;border-bottom:1px solid var(--line);cursor:pointer;}");
        sb.append(".nav-item:last-child{border-bottom:none;}");
        sb.append(".nav-item:active{background:rgba(102,126,234,0.12);}");
        // 「重新获取资料」：与上面的功能入口同一种行样式，只把底色换成红，
        // 表示这是「出问题时才点」的补救动作。放在卡片最后一行，靠 .nav-card 的
        // overflow:hidden 自动收出圆角，不需要单独写 border-radius。
        sb.append(".nav-item.danger{background:#E5484D;}");
        sb.append(".nav-item.danger .ni-txt b{color:#fff;}");
        sb.append(".nav-item.danger .ni-txt i{color:rgba(255,255,255,0.85);}");
        sb.append(".nav-item.danger .ni-arrow{color:rgba(255,255,255,0.9);}");
        sb.append(".nav-item.danger:active{background:#CF3F44;}");
        sb.append(".ni-txt{flex:1;}");
        sb.append(".ni-txt b{display:block;font-size:calc(15px * var(--fs,1));font-weight:600;color:var(--fg);}");
        sb.append(".ni-txt i{display:block;font-style:normal;font-size:calc(12px * var(--fs,1));color:var(--sub);margin-top:4px;}");
        sb.append(".ni-arrow{color:var(--sub);font-size:calc(20px * var(--fs,1));margin-left:8px;}");
        sb.append(".about-hero{text-align:center;padding:26px 0 22px;}");
        sb.append(".about-hero .ah-name{font-size:calc(26px * var(--fs,1));font-weight:800;color:var(--fg);letter-spacing:2px;}");
        sb.append(".about-hero .ah-ver{margin-top:8px;font-size:calc(13px * var(--fs,1));color:#667eea;font-weight:600;}");
        sb.append(".about-hero .ah-slogan{margin-top:10px;font-size:calc(12px * var(--fs,1));color:var(--sub);line-height:1.6;}");
        sb.append(".about-card{background:var(--card);border-radius:14px;padding:2px 16px;margin-bottom:14px;}");
        sb.append(".about-card .ac-title{font-size:calc(12px * var(--fs,1));color:#667eea;font-weight:700;padding:14px 0 6px;letter-spacing:1px;}");
        sb.append(".about-card .kv{display:flex;padding:11px 0;border-top:1px solid var(--line);font-size:calc(13px * var(--fs,1));}");
        sb.append(".about-card .kv:first-of-type{border-top:none;}");
        sb.append(".about-card .kv span{color:var(--sub);min-width:74px;flex:0 0 auto;}");
        sb.append(".about-card .kv b{color:var(--fg);font-weight:500;flex:1;word-break:break-all;line-height:1.5;}");
        sb.append(".about-card .kv b.tap{color:#667eea;cursor:pointer;}");
        sb.append(".about-card .kv b.tap:active{opacity:0.6;}");
        sb.append(".about-note{font-size:calc(12px * var(--fs,1));color:var(--sub);line-height:1.7;padding:0 4px 24px;}");

        boolean hasBg = !bgName.isEmpty() && new java.io.File(getFilesDir(), bgName).exists();
        boolean darkTheme = "dark".equals(theme);
        // 雾面卡片何时开启：深色主题（暗环境晃眼）、有自定义背景图（雾面才有意义）、
        // 或用户在浅色主题里手动打开。JS 侧 applyGlass() 用的是同一套判断，
        // 这样切主题时不用重渲染也能保持正确。
        // 玻璃只给深色主题：浅色主题保持原来的实色卡片（用户要求「浅色保留以前的颜色」）。
        // 有背景图时浅色也是实色卡，卡片自带 --cc 底色，同样能压住背景。
        boolean glassOn = darkTheme;
        sb.append("</style></head><body class='").append(darkTheme ? "dark" : "light")
          .append(hasBg ? " hasbg" : "")
          .append(glassOn ? " glass" : "")
          .append("'>");

        // 头部：只保留标题 → 日期+周次 → 按钮行（设置已移到「我的」页右上角齿轮）
        // 课表主体统一包一层 .tabview：切周推屏与切标签让位都作用在这一层上。
        // 它必须保留 flex 纵向布局，否则 .grid 的 flex:1 会失去可分配的高度。
        sb.append("<div class='tabview'>");
        sb.append("<div class='header'>");
        sb.append("<h1>石大课表</h1>");
        // 日期与周次同一行，周次用正文色加粗突出（不再是灰色小字）
        sb.append("<div class='subline'>");
        if (monday > 0) {
            sb.append("<span class='sdate'>").append(ScheduleStore.fmtDate(monday)).append(" 起</span>");
        }
        // 周次可点：弹出周次选择面板直接跳周（openWeekPick）
        sb.append("<span class='sweek' onclick='openWeekPick()'>第").append(week).append("周</span>");
        sb.append("</div>");
        // 这一行只在「本周」才有内容，但位置**每周都要占住**：
        // 否则本周的网格比其他周少一行的高度，翻周时整块课表会跳一下。
        // 非本周用一个不换行空格撑住同样的行高（空字符串会让这行塌成 0 高）。
        sb.append("<div class='hint' id='weekHint'>");
        if (week == todayWeek) {
            sb.append("本周 · 今天 ")
              .append(ScheduleStore.fmtDate(System.currentTimeMillis())).append(" ")
              .append(ScheduleStore.fmtWeekday(System.currentTimeMillis()));
        } else {
            sb.append("&nbsp;");
        }
        sb.append("</div>");
        // 按钮行：◀ / 刷新课表 / ＋加课 / ▶ —— 四个按钮同一套 .btn 尺寸与圆角
        // 左右按钮带 id：切周后表头与按钮都在原地，由 JS 按当前周改 disabled，不再整页重渲染
        sb.append("<div class='btns'>");
        sb.append("<button class='btn btn-nav' id='btnPrev' onclick='goWeek(-1)'")
          .append(week <= 1 ? " disabled" : "").append(">◀</button>");
        sb.append("<button class='btn' onclick='Android.onRefreshRequested()'>刷新课表</button>");
        sb.append("<button class='btn btn-add' onclick='toggleAddMode()'>＋ 加课</button>");
        sb.append("<button class='btn btn-nav' id='btnNext' onclick='goWeek(1)'")
          .append(totalWeeks <= 0 || week >= totalWeeks ? " disabled" : "").append(">▶</button>");
        sb.append("</div>");
        sb.append("<div class='addbar'><span>点击任意格子添加自定义课程（已有课也可叠加）</span>");
        sb.append("<button onclick='toggleAddMode()'>取消</button></div>");
        sb.append("</div>");

        // 表头（独立网格，不参与课程行高度分配）
        sb.append("<div class='grid-head'>");
        sb.append("<div class='gh'></div>");
        String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (int d = 1; d <= 7; d++) {
            boolean today = (week == todayWeek && d == todayDow);
            String dateStr = "";
            if (monday > 0) {
                long dayDate = monday + (d - 1) * 86400000L;
                dateStr = "<div class='gh-date' id='ghd" + d + "'>" + ScheduleStore.fmtDate(dayDate) + "</div>";
            }
            String cls = "gh" + (today ? " today" : "");
            // id 供切周后局部改日期/今日高亮用（表头不参与滑动，只能原地更新）
            sb.append("<div class='").append(cls).append("' id='ghd").append(d).append("'>")
              .append(days[d - 1]).append(dateStr).append("</div>");
        }
        sb.append("</div>");

        // 课程网格：外面套一层横条容器，左右两侧各留一块给相邻周。
        // 表头（.grid-head）留在外层不参与滑动 —— 切周只滑这一块。
        sb.append("<div class='gridview'><div class='weekstrip' id='weekstrip'>");
        sb.append("<div class='weekpanel' data-week='").append(week).append("'>");
        // 行数：至少铺满作息时间表（教务可能有 6 大节「中午延时」），
        // 再按实际课程往上探，上限 MAX_RENDER_ROWS，避免异常数据把网格撑爆
        int numRows = Math.min(Math.max(5, Math.max(maxRow, periodTimes.length)), MAX_RENDER_ROWS);
        // 空课周：教务与自定义都没有课时才提示，只渲染提示不画空格子。
        // 这一周同样要占满整块高度（empty-grid 让唯一那一行撑满），
        // 否则空周的网格会比有课的周矮一截，翻过去像课表塌了。
        if (courses.length() == 0 && customs.length() == 0) numRows = 0;
        sb.append("<!--grid--><div class='grid").append(numRows == 0 ? " empty-grid" : "").append("'>");
        if (numRows == 0) {
            sb.append("<div class='emptyweek'>这一周没有课程</div>");
        }
        for (int row = 1; row <= numRows; row++) {
            String label = row <= PERIOD_LABELS.length
                    ? PERIOD_LABELS[row - 1] : (row * 2 - 1) + "-" + (row * 2) + "节";
            String time = row <= periodTimes.length ? periodTimes[row - 1] : "";
            // 节次标签竖向排列：每个字符独占一行（1-2节 → 1/−/2/节）
            StringBuilder vLabel = new StringBuilder();
            for (int ci = 0; ci < label.length(); ci++) {
                if (ci > 0) vLabel.append("<br>");
                vLabel.append(esc(String.valueOf(label.charAt(ci))));
            }
            sb.append("<div class='ts'>").append(vLabel);
            if (!time.isEmpty()) sb.append("<div class='ts-time'>").append(time).append("</div>");
            sb.append("</div>");

            for (int d = 1; d <= 7; d++) {
                String cellKey = row + "_" + (d - 1);
                JSONArray arr = cellMap.get(cellKey);
                if (arr != null && arr.length() > 0) {
                    JSONObject first = arr.optJSONObject(0);
                    String color = courseColor(first, colorMap);
                    boolean clash = clashCells.contains(cellKey);
                    boolean overlap = overlapCells.contains(cellKey);
                    // 详情包含该格所有课程（同一时段可能有多门重叠课）
                    String detail = buildCourseDetailArray(arr, week, label, time, color, clash);
                    // 格内所有课程的标识（拖动按整格处理）
                    StringBuilder keysB = new StringBuilder();
                    for (int k = 0; k < arr.length(); k++) {
                        if (k > 0) keysB.append(";;;");
                        String ck = courseKey(arr.optJSONObject(k));
                        keysB.append(ck.replace("'", "\\'"));
                    }
                    // 与教务课程位置冲突的自定义课：整格描红边，并在右下角挂一个感叹号
                    sb.append("<div class='course")
                      .append(clash ? " conflict" : (overlap ? " overlap" : ""))
                      .append("' style='--cc:").append(color)
                      // --cc-rgb 给深色边缘光用：rgba() 只能吃分量，hex 无法带透明度
                      .append(";--cc-rgb:").append(ccRgb(color))
                      .append(";background:var(--cc)' data-detail='")
                      .append(detail).append("' data-keys='").append(keysB).append("' data-day='").append(d).append("' data-row='").append(row).append("'>");
                    if (clash) sb.append("<div class='clash-mark'>!</div>");
                    // 显示所有课程名（多门用换行分隔）
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject cc = arr.optJSONObject(k);
                        String nm = cc.optString("name", "").replaceAll("\\s+", " ").trim();
                        if (k > 0) sb.append("<hr style='border:0;border-top:1px solid rgba(255,255,255,0.3);margin:3px 0'>");
                        sb.append("<div class='name'>").append(esc(nm)).append("</div>");
                    }
                    // 教师不再显示在卡片上（仅详情弹窗保留）；
                    // 地点列在名称下方，让用户不点开就能看到上课地点
                    String location = first.optString("location", "").trim();
                    if (!location.isEmpty()) {
                        if (location.length() > 24) location = location.substring(0, 24) + "...";
                        sb.append("<div class='info'>").append(esc(location)).append("</div>");
                    }
                    sb.append("</div>");
                } else {
                    // 空格也带位置属性：拖动放置时 swapNodes 能正确交换属性，否则卡片位置被清空导致应用不生效
                    // data-empty 供「加课模式」识别可点击的空位
                    sb.append("<div class='empty' data-day='").append(d).append("' data-row='").append(row)
                      .append("' onclick='onEmptyClick(event,").append(d).append(",").append(row).append(")'></div>");
                }
            }
        }
        sb.append("</div>");   // 网格
        // 网格结束标记：Java 侧靠它从缓存的整页 HTML 里切出「网格片段」给相邻面板用，
        // 不用重新拼装整页（成本只是两次 indexOf）。
        sb.append("<!--/grid-->");
        sb.append("</div>");   // .weekpanel
        sb.append("</div></div>");   // .weekstrip + .gridview
        sb.append("</div>");   // .tabview 结束（课表主体：头部 + 表头 + 网格）

        // ===== 底部标签栏（position:fixed，任何页面下都露在底部）=====
        sb.append("<div class='tabbar'>");
        sb.append("<button class='tabitem on' id='tabSchedule' onclick='switchTab(\"schedule\")'>课表</button>");
        sb.append("<button class='tabitem' id='tabMine' onclick='switchTab(\"mine\")'>我的</button>");
        sb.append("</div>");

        // ===== 通用底部选择面板（校区/教学楼/教室/周次/星期/节次共用）=====
        // 放在 body 级：.page 是 z-index:95 的滑动层，面板放进去会被它和标签栏压住。
        sb.append("<div class='pk-mask' id='pkMask' onclick='pkClose()'></div>");
        sb.append("<div class='pk-sheet' id='pkSheet'>");
        sb.append("<div class='pk-head'><div class='pk-title' id='pkTitle'>请选择</div>");
        sb.append("<button class='pk-cancel' onclick='pkClose()'>取消</button></div>");
        sb.append("<div class='pk-search' id='pkSearchWrap' style='display:none'>");
        sb.append("<input id='pkSearch' type='search' placeholder='搜索' oninput='pkRender()'></div>");
        sb.append("<div class='pk-list' id='pkList'></div>");
        sb.append("</div>");

        // 课程详情弹窗：外层 .modal 是「生长的窗口」，内层 .m-inner 是内容（不缩放）
        sb.append("<div class='modal-bg' id='courseModal' onclick='closeModal()'>");
        sb.append("<div class='modal' onclick='event.stopPropagation()'>");
        sb.append("<div class='m-inner'>");
        sb.append("<h2 id='cmTitle'></h2>");
        sb.append("<div id='cmBody'></div>");
        sb.append("<button class='modal-close' onclick='closeModal()'>关闭</button>");
        sb.append("</div></div></div>");

        // 通用详情弹窗（查询教室/找空教室的卡片共用）
        sb.append("<div class='modal-bg' id='cdModal' onclick='cdClose()'>");
        sb.append("<div class='modal' onclick='event.stopPropagation()'>");
        sb.append("<div class='m-inner'>");
        sb.append("<h2 id='cdTitle'></h2>");
        sb.append("<div id='cdBody'></div>");
        sb.append("<div id='cdActions'></div>");
        sb.append("<button class='modal-close' onclick='cdClose()'>关闭</button>");
        sb.append("</div></div></div>");

        // ===== 我的（底部标签之一；右上角齿轮进入设置）=====
        String stuName = store != null ? store.getStudentName() : "";
        String stuId = store != null ? store.getStudentId() : "";
        String stuOwner = store != null ? store.getStudentOwner() : "";
        // 资料必须与当前登录账号对得上：换了账号、或本地是调试时塞进去的测试数据（owner 为空），
        // 一律不展示，避免出现「登录后还挂着别人的姓名与学号」。
        if (savedUser.isEmpty() || !savedUser.equals(stuOwner)) {
            stuName = "";
            stuId = "";
        }
        // 学号兜底用登录账号：资料还没抓到时不至于空着
        if (stuId.isEmpty()) stuId = savedUser;
        String avatarTxt = stuName.isEmpty() ? "我" : stuName.substring(0, 1);
        sb.append("<div class='page' id='pageMine'>");
        sb.append("<div class='page-head'><div class='ph-pad'></div>");
        sb.append("<div class='ph-title'>我的</div>");
        sb.append("<button class='ph-gear' aria-label='设置' onclick='showSettings()'>")
          .append(GEAR_SVG).append("</button></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='me-card'><div class='me-avatar' id='meAvatar'>")
          .append(esc(avatarTxt)).append("</div>");
        sb.append("<div class='me-info'><b id='meName'>")
          .append(esc(stuName.isEmpty() ? "未获取到姓名" : stuName)).append("</b>");
        sb.append("<i id='meId'>")
          .append(esc(stuId.isEmpty() ? "学号未获取" : "学号 " + stuId)).append("</i></div></div>");
        // 功能入口（原「更多功能」页已并入本页）
        sb.append("<div class='nav-card'>");
        sb.append("<div class='nav-item' onclick='openClassroomQuery()'>");
        sb.append("<div class='ni-txt'><b>查询教室</b><i>查看某间教室在指定周次的占用情况</i></div>");
        sb.append("<div class='ni-arrow'>›</div></div>");
        sb.append("<div class='nav-item' onclick='openEmptyRoom()'>");
        sb.append("<div class='ni-txt'><b>找空教室</b><i>按校区/教学楼找某时段没课的教室</i></div>");
        sb.append("<div class='ni-arrow'>›</div></div>");
        // 重新获取资料：与上面同一种行样式，只用红底区分它是「补救动作」而非功能入口
        sb.append("<div class='nav-item danger' onclick='reFetchMe()'>");
        sb.append("<div class='ni-txt'><b>重新获取资料</b><i>姓名学号显示不出来时，到这里重抓一次</i></div>");
        sb.append("<div class='ni-arrow'>›</div></div>");
        sb.append("</div>");
        sb.append("</div></div>");

        // ===== 查询教室（原生表单 + 隐藏 WebView 驱动教务自身的联调逻辑）=====
        sb.append("<div class='page' id='pageClassroom'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>查询教室</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='cr-status' id='crStatus'>正在读取教务端的可选条件…</div>");
        sb.append("<div id='crForm' style='display:none'>");
        sb.append("<label class='cf-label'>校区</label>");
        sb.append("<div class='cr-field ph' id='crCampusF' onclick='crPick(\"crCampus\")'>");
        sb.append("<span class='crf-v' id='crCampusTxt'>请选择校区</span><span class='crf-a'>›</span></div>");
        sb.append("<label class='cf-label'>教学楼</label>");
        sb.append("<div class='cr-field ph' id='crBuildingF' onclick='crPick(\"crBuilding\")'>");
        sb.append("<span class='crf-v' id='crBuildingTxt'>请选择教学楼</span><span class='crf-a'>›</span></div>");
        sb.append("<label class='cf-label'>教室</label>");
        sb.append("<div class='cr-field ph lock' id='crRoomF' onclick='crPick(\"crRoom\")'>");
        sb.append("<span class='crf-v' id='crRoomTxt'>全部教室</span><span class='crf-a'>›</span></div>");
        sb.append("<div class='cf-row'>");
        sb.append("<div><label class='cf-label'>周次</label>");
        sb.append("<div class='cr-field' id='crWeekF' onclick='crPick(\"crWeek\")'>");
        sb.append("<span class='crf-v' id='crWeekTxt'>请选择周次</span><span class='crf-a'>›</span></div></div>");
        sb.append("<div><label class='cf-label'>星期</label>");
        sb.append("<div class='cr-field' id='crWeekdayF' onclick='crPick(\"crWeekday\")'>");
        sb.append("<span class='crf-v' id='crWeekdayTxt'>请选择星期</span><span class='crf-a'>›</span></div></div>");
        sb.append("</div>");
        sb.append("<div class='cf-row'>");
        sb.append("<div><label class='cf-label'>起始节次</label>");
        sb.append("<div class='cr-field' id='crJc1F' onclick='crPick(\"crJc1\")'>");
        sb.append("<span class='crf-v' id='crJc1Txt'>起始节次</span><span class='crf-a'>›</span></div></div>");
        sb.append("<div><label class='cf-label'>结束节次</label>");
        sb.append("<div class='cr-field' id='crJc2F' onclick='crPick(\"crJc2\")'>");
        sb.append("<span class='crf-v' id='crJc2Txt'>结束节次</span><span class='crf-a'>›</span></div></div>");
        sb.append("</div>");
        sb.append("<div class='cf-tip'>教学楼可留空查整校区；不选教室＝看范围内所有教室该时段的占用，选具体教室则只看这一间。</div>");
        sb.append("<button class='cf-save' style='width:100%;margin-top:14px;' id='crSubmitBtn' onclick='crSubmit()'>查询</button>");
        sb.append("<div id='crResult'></div>");
        // 值容器：保留原生 select，让取值/回显逻辑只有一处
        sb.append("<div class='cr-sel'>");
        sb.append("<select id='crCampus' onchange='crPickCampus()'></select>");
        sb.append("<select id='crBuilding' onchange='crPickBuilding()'></select>");
        sb.append("<select id='crRoom'></select>");
        sb.append("<select id='crWeek'></select>");
        sb.append("<select id='crWeekday'></select>");
        sb.append("<select id='crJc1'></select><select id='crJc2'></select>");
        sb.append("</div>");
        sb.append("</div>");
        sb.append("</div></div>");

        // ===== 找空教室（复用同一个隐藏 WebView 与查询通道，只是解析结果取反）=====
        sb.append("<div class='page' id='pageEmpty'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>找空教室</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='cr-status' id='eqStatus'>正在读取教务端的可选条件…</div>");
        sb.append("<div id='eqForm' style='display:none'>");
        sb.append("<label class='cf-label'>校区</label>");
        sb.append("<div class='cr-field ph' id='eqCampusF' onclick='crPick(\"eqCampus\")'>");
        sb.append("<span class='crf-v' id='eqCampusTxt'>请选择校区</span><span class='crf-a'>›</span></div>");
        sb.append("<label class='cf-label'>教学楼</label>");
        sb.append("<div class='cr-field ph' id='eqBuildingF' onclick='crPick(\"eqBuilding\")'>");
        sb.append("<span class='crf-v' id='eqBuildingTxt'>全部教学楼（整校区）</span><span class='crf-a'>›</span></div>");
        sb.append("<div class='cf-row'>");
        sb.append("<div><label class='cf-label'>周次</label>");
        sb.append("<div class='cr-field' id='eqWeekF' onclick='crPick(\"eqWeek\")'>");
        sb.append("<span class='crf-v' id='eqWeekTxt'>请选择周次</span><span class='crf-a'>›</span></div></div>");
        sb.append("<div><label class='cf-label'>星期</label>");
        sb.append("<div class='cr-field' id='eqWeekdayF' onclick='crPick(\"eqWeekday\")'>");
        sb.append("<span class='crf-v' id='eqWeekdayTxt'>请选择星期</span><span class='crf-a'>›</span></div></div>");
        sb.append("</div>");
        sb.append("<div class='cf-row'>");
        sb.append("<div><label class='cf-label'>起始节次</label>");
        sb.append("<div class='cr-field' id='eqJc1F' onclick='crPick(\"eqJc1\")'>");
        sb.append("<span class='crf-v' id='eqJc1Txt'>起始节次</span><span class='crf-a'>›</span></div></div>");
        sb.append("<div><label class='cf-label'>结束节次</label>");
        sb.append("<div class='cr-field' id='eqJc2F' onclick='crPick(\"eqJc2\")'>");
        sb.append("<span class='crf-v' id='eqJc2Txt'>结束节次</span><span class='crf-a'>›</span></div></div>");
        sb.append("</div>");
        sb.append("<div class='cf-tip'>按所选校区/教学楼统计该时段没课的教室；教学楼留空即查整校区"
                + "（范围大，教务返回会慢一些）。</div>");
        sb.append("<button class='cf-save' style='width:100%;margin-top:14px;' id='eqSubmitBtn' onclick='eqSubmit()'>查找空教室</button>");
        sb.append("<div id='eqResult'></div>");
        sb.append("<div class='cr-sel'>");
        sb.append("<select id='eqCampus' onchange='crPickCampusFor(\"eqBuilding\")'></select>");
        sb.append("<select id='eqBuilding' onchange='eqPickBuilding()'></select>");
        sb.append("<select id='eqWeek'></select>");
        sb.append("<select id='eqWeekday'></select>");
        sb.append("<select id='eqJc1'></select><select id='eqJc2'></select>");
        sb.append("</div>");
        sb.append("</div>");
        sb.append("</div></div>");

        // ===== 设置页（独立页面，从右侧滑入）=====
        // 一级：设置列表
        sb.append("<div class='page' id='pageSettings'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>设置</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='nav-card'>");
        sb.append("<div class='nav-item' onclick='openSub(\"account\")'><div class='ni-txt'><b>账号设置</b>");
        sb.append("<i>当前登录账号与退出登录</i></div><div class='ni-arrow'>›</div></div>");
        sb.append("<div class='nav-item' onclick='openSub(\"general\")'><div class='ni-txt'><b>通用设置</b>");
        sb.append("<i>课表背景、开屏动画、桌面小组件、课程布局</i></div><div class='ni-arrow'>›</div></div>");
        sb.append("<div class='nav-item' onclick='openSub(\"custom\")'><div class='ni-txt'><b>自定义课程</b>");
        sb.append("<i>管理自己添加的课程，与教务课表互不影响</i></div><div class='ni-arrow'>›</div></div>");
        sb.append("<div class='nav-item' onclick='openSub(\"update\")'><div class='ni-txt'><b>检查更新</b>");
        sb.append("<i>检查新版本、查看更新内容与公告</i></div><div class='ni-arrow'>›</div></div>");
        sb.append("<div class='nav-item' onclick='openSub(\"about\")'><div class='ni-txt'><b>关于</b>");
        sb.append("<i>开发信息、开源地址与作者联系方式</i></div><div class='ni-arrow'>›</div></div>");
        sb.append("</div>");
        sb.append("</div></div>");

        // 二级：账号设置
        sb.append("<div class='page' id='pageAccount'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>账号设置</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='settings-item'><div><div class='si-label'>当前账号</div>");
        sb.append("<div class='si-desc'>").append(esc(savedUser.isEmpty() ? "未登录" : savedUser)).append("</div></div></div>");
        sb.append("<button class='logout-btn' onclick='Android.onLogout()'>退出登录</button>");
        sb.append("</div></div>");

        // 二级：通用设置
        sb.append("<div class='page' id='pageGeneral'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>通用设置</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        // 通用设置按使用频率从高到低排列：
        // 背景/主题 → 开屏动画 → 桌面小组件 → 冲突优先级 → 重置布局 → 数据诊断
        // 课表背景：深浅主题切换 + 自定义图片
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>课表背景</div>");
        sb.append("<div class='si-desc'>切换浅色/深色，或从相册选一张图片当背景</div></div>");
        sb.append("<div style='display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;'>");
        sb.append("<button class='adv-btn' id='themeLight' onclick='setTheme(\"light\")'>浅色</button>");
        sb.append("<button class='adv-btn' id='themeDark' onclick='setTheme(\"dark\")'>深色</button>");
        sb.append("<button class='adv-btn' onclick='Android.onPickBackground()'>选择图片背景</button>");
        sb.append("<button class='adv-btn' id='clearBgBtn' style='display:").append(hasBg ? "inline-block" : "none")
          .append(";background:#FFF0F0;color:#e55;' ");
        sb.append("onclick='Android.onClearBackground()'>清除图片背景</button>");
        sb.append("</div>");
        sb.append("</div>");
        // 开屏动画：单个可切换开关，与其他设置项保持同一行布局（关闭后下次启动生效）
        boolean splashAnimOn = store == null || store.isSplashAnimEnabled();
        sb.append("<div class='settings-item'><div><div class='si-label'>开屏动画</div>");
        sb.append("<div class='si-desc'>关闭后启动直接显示课表，下次启动生效</div></div>");
        sb.append("<button class='switch").append(splashAnimOn ? " on" : "")
          .append("' id='splashSwitch' onclick='toggleSplash()'><i></i></button>");
        sb.append("</div>");
        // 通知栏常驻：靠前台服务维持，内容由 CourseNotifyService 每分钟校正
        boolean notifyOn = store != null && store.isNotifyPersist();
        sb.append("<div class='settings-item'><div><div class='si-label'>通知栏常驻课程</div>");
        sb.append("<div class='si-desc'>一直挂在通知栏显示当前课程：标题是课程名和时间，");
        sb.append("下面一行是地点与老师。关闭后通知立即撤掉</div></div>");
        sb.append("<button class='switch").append(notifyOn ? " on" : "")
          .append("' id='notifySwitch' onclick='toggleNotify()'><i></i></button>");
        sb.append("</div>");
        // 字体大小：滑块连续调节（实时预览），档位按钮一键回到常用值。
        // 页面字号全部写成 calc(<原字号> * var(--fs))，所以改 --fs 就能整页缩放。
        int fsPct = store == null ? 100 : store.getFontScale();
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div class='fs-head' style='display:flex;align-items:baseline;justify-content:space-between;gap:8px;'>");
        sb.append("<div class='si-label'>字体大小</div>");
        sb.append("<div class='fs-val' id='fsVal'>").append(fsPct).append("%</div></div>");
        sb.append("<div class='si-desc'>调整课表与各页面文字的大小，拖动即刻生效</div>");
        sb.append("<div class='fs-wrap'>");
        sb.append("<span class='fs-a'>A</span>");
        sb.append("<input class='fs-range' id='fsRange' type='range' min='80' max='150' step='5' value='")
          .append(fsPct).append("' oninput='onFontSlide(this.value)'>");
        sb.append("<span class='fs-b'>A</span>");
        sb.append("</div>");
        sb.append("<div class='fs-presets'>");
        sb.append("<button class='adv-btn' id='fsP80' onclick='setFontScale(80)'>小</button>");
        sb.append("<button class='adv-btn' id='fsP100' onclick='setFontScale(100)'>标准</button>");
        sb.append("<button class='adv-btn' id='fsP120' onclick='setFontScale(120)'>大</button>");
        sb.append("<button class='adv-btn' id='fsP140' onclick='setFontScale(140)'>特大</button>");
        sb.append("</div>");
        sb.append("<div class='si-hint'>字放大后课表格子高度不变，长课名可能显示不下；觉得挤可以配合「小」档使用。</div>");
        sb.append("</div>");
        // 桌面小组件：部分 ROM（ColorOS/MIUI 等）组件库里找不到第三方组件，
        // 提供主动添加入口，直接弹系统确认框，绕开组件库
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>桌面小组件</div>");
        sb.append("<div class='si-desc'>在桌面显示下一节课的名称与地点，可自由拉伸大小</div></div>");
        sb.append("<button class='adv-btn' style='margin-top:10px;width:100%;' ");
        sb.append("onclick='Android.onAddWidget()'>添加到桌面</button>");
        sb.append("<div class='si-hint' style='margin-top:6px;'>若未弹出确认框，可长按桌面空白处，");
        sb.append("在「卡片/小组件」里搜索「石大课表」手动添加</div>");
        sb.append("</div>");
        // 位置冲突时的显示优先级（红框提示不受此项影响，始终显示）
        String cp = store != null ? store.getConflictPriority() : "school";
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>位置冲突时优先显示</div>");
        sb.append("<div class='si-desc'>自定义课程与教务课程落在同一格时，卡片主色与地点按这里的顺序取值。");
        sb.append("自己叠加的课用<b style='color:#C1801F'>琥珀色</b>描边，教务同步后撞上的用<b style='color:#E5533D'>红色</b>描边</div></div>");
        sb.append("<div style='display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;'>");
        sb.append("<button class='adv-btn' id='cpSchool' onclick='setConflictPriority(\"school\")'>教务课程优先</button>");
        sb.append("<button class='adv-btn' id='cpCustom' onclick='setConflictPriority(\"custom\")'>自定义课程优先</button>");
        sb.append("</div></div>");
        // 课程布局
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>课程布局</div>");
        sb.append("<div class='si-desc'>如果课表位置看起来不对，可一键恢复课程默认位置</div></div>");
        sb.append("<button class='adv-btn' style='margin-top:10px;background:#FFF0F0;color:#e55;' ");
        sb.append("onclick='Android.onResetLayout()'>重置课程布局</button>");
        sb.append("</div>");
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>课表数据诊断</div>");
        sb.append("<div class='si-desc'>保存最近一次教务系统课表页面及解析结果，便于排查漏课。文件可能包含课程和个人信息，请勿公开传播。</div></div>");
        sb.append("<button class='adv-btn' style='margin-top:10px;width:100%;' onclick='Android.onExportScheduleCapture()'>保存抓取数据</button>");
        sb.append("</div>");
        sb.append("</div></div>");

        // 二级：检查更新
        // 骨架固定、数据由 JS 填：无论首次打开还是切周后重渲染，元素集合都一致，
        // 不会出现「检查到新版本却不显示更新日志/没有立即更新按钮」的情况（v1.4.1 的 bug）。
        String updCurVer = currentVersionName();
        sb.append("<div class='page' id='pageUpdate'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>检查更新</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='settings-item'><div><div class='si-label'>当前版本</div>");
        sb.append("<div class='si-desc'>v").append(esc(updCurVer)).append("</div></div>");
        sb.append("<button class='adv-btn' id='upBtn' onclick='Android.onCheckUpdate()'>检查更新</button></div>");
        // 最新版本行：按钮默认隐藏，检查到新版本后由 renderUpdateInfo 显示
        sb.append("<div class='settings-item'><div><div class='si-label'>最新版本</div>");
        sb.append("<div class='si-desc' id='upLatest'>—</div>");
        sb.append("<div class='si-desc' id='upMeta' style='display:none'></div></div>");
        sb.append("<button class='adv-btn' id='upDlBtn' style='display:none;");
        sb.append("background:#667eea;color:#fff;border-color:#667eea;font-weight:600;' ");
        sb.append("onclick='Android.onDownloadUpdate()'>立即更新</button></div>");
        // 更新内容：默认空态提示，检查到结果后由 JS 填 changelog（换行由 pre-wrap 保留）
        sb.append("<div class='up-block'><div class='up-block-title'>更新内容</div>");
        sb.append("<div class='up-empty' id='upLog'>点击右上角「检查更新」从服务器获取最新版本的更新内容。</div></div>");
        // 公告区：内容为空时整体隐藏
        sb.append("<div class='up-block' id='upContentBox' style='display:none'>");
        sb.append("<div class='up-block-title'>公告</div>");
        sb.append("<div class='up-log' id='upContent'></div></div>");
        // 下载进度条 + 取消按钮
        sb.append("<div class='up-progress' id='upProgBox' style='display:none'>");
        sb.append("<div class='up-bar'><i id='upBar'></i></div>");
        sb.append("<div class='up-prog-txt' id='upProgTxt'>0%</div></div>");
        sb.append("<button class='adv-btn' id='upCancelBtn' style='display:none;margin-top:10px;width:100%;");
        sb.append("background:#FFF0F0;color:#e55;' onclick='Android.onCancelDownload()'>取消下载</button>");
        // 缓存数据不在这里内联渲染：本段 HTML 位于主 <script> 之前，函数还没定义。
        // 回填统一走 openSub('update') → Android.onUpdatePageOpened() 推送。
        sb.append("</div></div>");

        // 二级：自定义课程管理
        sb.append("<div class='page' id='pageCustom'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>自定义课程</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='si-hint' style='margin:0 0 12px;'>自定义课程单独存储，刷新教务课表不会覆盖或清空。");
        sb.append("与教务课程落在同一格时会描红边框提示。</div>");
        sb.append("<div class='nav-card' style='padding:0 16px;'>").append(buildCustomCourseListHtml()).append("</div>");
        sb.append("<button class='adv-btn' style='margin-top:16px;width:100%;background:#667eea;color:#fff;");
        sb.append("border-color:#667eea;font-weight:600;' onclick='Android.onAddCustomFromSettings()'>＋ 添加自定义课程</button>");
        if (store != null && store.getCustomCourseCount() > 0) {
            sb.append("<button class='adv-btn' style='margin-top:10px;width:100%;background:#FFF0F0;color:#e55;");
            sb.append("' onclick='Android.onClearCustomCourses()'>清空全部自定义课程</button>");
        }
        sb.append("</div></div>");

        // 二级：添加/编辑自定义课程（表单）
        sb.append("<div class='page' id='pageCustomEdit'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title' id='cfTitle'>添加课程</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<input type='hidden' id='cfId'>");
        sb.append("<label class='cf-label'>课程名称 <span style='color:#E5533D'>*</span></label>");
        sb.append("<input class='cf-input' id='cfName' maxlength='30' placeholder='例如：自习 / 社团活动'>");
        sb.append("<div class='cf-row'>");
        sb.append("<div><label class='cf-label'>星期</label><select class='cf-input' id='cfDay'>");
        for (int d = 1; d <= 7; d++) {
            sb.append("<option value='").append(d).append("'>").append(WEEKDAY_CN[d]).append("</option>");
        }
        sb.append("</select></div>");
        sb.append("<div><label class='cf-label'>节次</label><select class='cf-input' id='cfRow'>");
        for (int r = 1; r <= MAX_RENDER_ROWS; r++) {
            String pl = r <= PERIOD_LABELS.length
                    ? PERIOD_LABELS[r - 1] : (r * 2 - 1) + "-" + (r * 2) + "节";
            sb.append("<option value='").append(r).append("'>第").append(pl).append("</option>");
        }
        sb.append("</select></div></div>");
        // 目标格已有课时提示「这是叠加加课」，由 openCustomForm 按 occupied 决定是否显示
        sb.append("<div class='cf-overlap' id='cfOverlapTip' style='display:none'></div>");
        // 重复方式：仅本周（单次卡片） / 按周次（可填 1-16、3,5,7 等）
        sb.append("<label class='cf-label'>重复方式</label>");
        sb.append("<div class='cf-seg'><button class='cf-seg-item' id='cfRepWeek' onclick='pickRepeat(\"week\")'>仅本周一次</button>");
        sb.append("<button class='cf-seg-item' id='cfRepCustom' onclick='pickRepeat(\"custom\")'>按周次重复</button></div>");
        sb.append("<div id='cfWeeksBox'>");
        sb.append("<label class='cf-label'>周次</label>");
        sb.append("<input class='cf-input' id='cfWeeks' maxlength='40' placeholder='留空表示每周都有'>");
        sb.append("<div class='cf-tip'>支持 1-16、3,5,7、1-16单 等写法；留空 = 每周</div>");
        sb.append("</div>");
        sb.append("<div class='cf-tip' id='cfOnceTip' style='display:none'>只在本周这一格显示一张卡片，其它周不出现</div>");
        sb.append("<label class='cf-label'>上课地点</label>");
        sb.append("<input class='cf-input' id='cfLoc' maxlength='30' placeholder='选填'>");
        sb.append("<label class='cf-label'>授课教师</label>");
        sb.append("<input class='cf-input' id='cfTeacher' maxlength='20' placeholder='选填'>");
        sb.append("<label class='cf-label'>备注</label>");
        sb.append("<input class='cf-input' id='cfRemark' maxlength='60' placeholder='选填'>");
        sb.append("<label class='cf-label'>卡片颜色</label>");
        sb.append("<div class='cf-colors' id='cfColors'></div>");
        sb.append("<div class='cf-actions'>");
        sb.append("<button class='cf-del' id='cfDelete' style='display:none' onclick='deleteCurrentCustom()'>删除</button>");
        sb.append("<button class='cf-save' onclick='saveCustom()'>保存</button>");
        sb.append("</div>");
        sb.append("</div></div>");

        // 二级：关于
        // 版本号直接从系统读取，避免多处硬编码
        String verName = "1.1.1";
        try {
            verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        sb.append("<div class='page' id='pageAbout'>");
        sb.append("<div class='page-head'><button class='ph-back' onclick='closePage()'>‹</button>");
        sb.append("<div class='ph-title'>关于</div><div class='ph-pad'></div></div>");
        sb.append("<div class='page-body'>");
        sb.append("<div class='about-hero'>");
        sb.append("<div class='ah-name'>石大课表</div>");
        sb.append("<div class='ah-ver'>版本 v").append(esc(verName)).append("</div>");
        sb.append("<div class='ah-slogan'>非官方 · 学生业余作品 · 与学校官方及教务部门无关</div>");
        sb.append("</div>");
        // 开发信息
        sb.append("<div class='about-card'>");
        sb.append("<div class='ac-title'>开发信息</div>");
        sb.append("<div class='kv'><span>开发环境</span><b>Android Studio · Gradle 8.4 · AGP 8.3.2 · JDK 21</b></div>");
        sb.append("<div class='kv'><span>开发语言</span><b>Java（原生逻辑） + HTML / CSS / JavaScript（界面）</b></div>");
        sb.append("<div class='kv'><span>技术架构</span><b>WebView 混合架构：原生负责登录与数据，网页负责课表渲染与交互</b></div>");
        sb.append("<div class='kv'><span>核心逻辑</span><b>CAS 单点登录 → 教务 DOM 解析课表 → 本地离线存储 → 周课表渲染 + 桌面小组件</b></div>");
        sb.append("<div class='kv'><span>数据存储</span><b>课表与配置存本机，密码经 Android Keystore 加密</b></div>");
        sb.append("<div class='kv'><span>兼容版本</span><b>Android 7.0 及以上（API 24+）</b></div>");
        sb.append("<div class='kv'><span>自动更新</span><b>启动后每天检查一次；安装包下载后校验 SHA-256，再交系统安装器</b></div>");
        sb.append("</div>");
        // 作者与开源
        sb.append("<div class='about-card'>");
        sb.append("<div class='ac-title'>开源与作者</div>");
        sb.append("<div class='kv'><span>作者 ID</span><b>m1Su</b></div>");
        sb.append("<div class='kv'><span>QQ</span><b class='tap' onclick='copyTxt(\"196331872\")'>196331872</b></div>");
        sb.append("<div class='kv'><span>微信</span><b class='tap' onclick='copyTxt(\"m1SuCode\")'>m1SuCode</b></div>");
        sb.append("<div class='kv'><span>邮箱</span><b class='tap' onclick='copyTxt(\"19633187@qq.com\")'>19633187@qq.com</b></div>");
        sb.append("<div class='kv'><span>GitHub</span><b class='tap' onclick='copyTxt(\"https://github.com/DoGeMisu/shzu-schedule\")'>DoGeMisu / shzu-schedule</b></div>");
        sb.append("<div class='kv'><span>个人站点</span><b class='tap' onclick='copyTxt(\"https://misuhub.cc.cd\")'>misuhub.cc.cd</b></div>");
        sb.append("<div class='kv'><span>开源协议</span><b>MIT License</b></div>");
        sb.append("</div>");
        sb.append("<div class='about-note'>点击 QQ / 微信 / 邮箱 / GitHub / 个人站点可复制。本项目为在校学生利用课余时间出于学习目的独立开发维护，不收取任何费用，请勿用于商业用途。</div>");
        sb.append("</div></div>");

        // 拖动模式底部操作栏
        sb.append("<div class='dockbar' id='dockbar'>");
        sb.append("<button class='db-btn db-apply' onclick='applyMoves()'>应用</button>");
        sb.append("<button class='db-btn db-cancel' onclick='cancelMoves()'>取消更改</button>");
        sb.append("<button class='db-btn db-undo' id='undoBtn' onclick='undoMove()' disabled>撤销</button>");
        sb.append("</div>");

        // JS
        sb.append("<script>");
        sb.append("var CUR_WEEK=").append(week).append(";");
        sb.append("var TOTAL_WEEKS=").append(totalWeeks).append(";");
        // ===== 切周：网格横条跟手滑动（不重载整页）=====
        //
        // 结构：.gridview(裁切) > .weekstrip(300% 宽) > 三个 .weekpanel（上一周/本周/下一周）。
        // 基准位移是 -33.3333%（横条自身宽度的 1/3 = 一个视口宽），把中间面板对齐屏幕。
        //
        // 关键：整个文档不换。所以标题、按钮、表头、底部标签栏都不会闪，
        // 网格也能实时跟着手指走 —— 而不是等松手才开始播动画。
        sb.append("var strip=null,swDragging=false,swDecided=false,swDx=0,swStartX=0,swStartY=0,swT0=0,swWidth=0;");
        sb.append("function stripEl(){if(!strip)strip=document.getElementById('weekstrip');return strip;}");
        sb.append("function setStripX(px,animate){");
        sb.append("  var s=stripEl();if(!s)return;");
        sb.append("  if(animate)s.classList.remove('dragging');else s.classList.add('dragging');");
        sb.append("  s.style.transform='translateX(calc(-33.3333% + '+px+'px))';}");
        // 该方向还有没有相邻周：没有就加阻尼，滑不动但给一点回弹余量
        sb.append("function hasNeighbor(dir){");
        sb.append("  var t=CUR_WEEK+dir;return t>=1&&t<=TOTAL_WEEKS;}");
        sb.append("function resist(px){");
        sb.append("  var dir=px<0?1:-1;");
        sb.append("  if(hasNeighbor(dir))return px;");
        sb.append("  return px*0.3;}");
        // 面板里新插入的卡片要重新绑事件：内联 onclick 只管空格和加课，
        // 长按浮起、点开详情都是这里用 addEventListener 绑的，新面板必须补绑。
        sb.append("function bindCards(root){");
        sb.append("  (root||document).querySelectorAll('.course').forEach(function(el){bindCard(el);});}");
        sb.append("function bindCard(el){");
        sb.append("  if(el.__bound)return;el.__bound=true;");
        sb.append("  el.addEventListener('touchstart',function(e){");
        sb.append("    if(dragEl)return;if(addMode)return;");
        sb.append("    var card=this;");
        sb.append("    pressX=e.touches[0].clientX;pressY=e.touches[0].clientY;");
        sb.append("    if(dragMode){startLift(card);}");
        sb.append("    else{pressTimer=setTimeout(function(){pressTimer=null;startLift(card);");
        sb.append("      if(window.Android&&Android.onDragStart)Android.onDragStart();},700);}");
        sb.append("  },false);");
        sb.append("  el.addEventListener('touchmove',function(e){");
        sb.append("    if(dragEl===this){e.preventDefault();moveDrag(e.touches[0].clientX,e.touches[0].clientY);}");
        sb.append("    else if(pressTimer){var t=e.touches[0];");
        sb.append("      if(Math.abs(t.clientX-pressX)>12||Math.abs(t.clientY-pressY)>12){");
        sb.append("        clearTimeout(pressTimer);pressTimer=null;}}");
        sb.append("  },false);");
        sb.append("  el.addEventListener('touchend',function(e){");
        sb.append("    if(pressTimer){clearTimeout(pressTimer);pressTimer=null;}");
        sb.append("    if(dragEl===this){endDrag(e.changedTouches[0]);}});");
        sb.append("  el.addEventListener('touchcancel',function(){");
        sb.append("    if(pressTimer){clearTimeout(pressTimer);pressTimer=null;}");
        sb.append("    if(dragEl===this)endDrag(null);});");
        // 用 click 而不是 touchend 开详情：click 天然排除滚动与滑动，误触更少
        sb.append("  el.addEventListener('click',function(){");
        sb.append("    if(didDrag){didDrag=false;return;}");
        sb.append("    if(dragMode)return;");
        sb.append("    if(addMode){onCardPick(this);return;}");
        sb.append("    var d=this.getAttribute('data-detail');");
        // 入场动画没播完就量矩形会拿到缩放后的尺寸，窗口起点会算错；
        // 这里把动画快进到终态（不要删 animation，否则会从头重播一次 popIn）
        sb.append("    if(d){");
        sb.append("      if(this.getAnimations){this.getAnimations().forEach(function(a){try{a.finish();}catch(e){}});}");
        sb.append("      var rc=this.getBoundingClientRect();");
        sb.append("      window.__cardRect={left:rc.left,top:rc.top,width:rc.width,height:rc.height};");
        sb.append("      window.__openedCard=this;Android.onCourseClick(d);}});");
        sb.append("}");
        // ---- 面板管理：固定三块（左/中/右），缺的先放占位块，等 Java 回填 ----
        sb.append("function panelByWeek(w){");
        sb.append("  var s=stripEl();if(!s)return null;");
        sb.append("  var ns=s.querySelectorAll('.weekpanel');");
        sb.append("  for(var i=0;i<ns.length;i++){if(parseInt(ns[i].getAttribute('data-week'),10)===w)return ns[i];}");
        sb.append("  return null;}");
        sb.append("function panelFilled(w){var p=panelByWeek(w);return !!(p&&p.getAttribute('data-filled')==='1');}");
        sb.append("function makePanel(week,html){");
        sb.append("  var p=document.createElement('div');");
        sb.append("  p.className='weekpanel nofx';p.setAttribute('data-week',week);");
        sb.append("  p.setAttribute('data-filled','1');");
        sb.append("  p.innerHTML=html;bindCards(p);return p;}");
        // Java 回传面板片段（异步）：有占位块就直接填，没有就插到对应一侧
        sb.append("window.__applyPanel=function(week,html){");
        sb.append("  var s=stripEl();if(!s||!html)return;");
        sb.append("  if(panelFilled(week))return;");
        sb.append("  var p=panelByWeek(week);");
        sb.append("  if(p){p.innerHTML=html;p.setAttribute('data-filled','1');bindCards(p);}");
        sb.append("  else{p=makePanel(week,html);");
        sb.append("    if(week<CUR_WEEK)s.insertBefore(p,s.firstChild);else s.appendChild(p);}");
        // 用户点过箭头但当时面板还没生成：现在到了就补上这一步，避免"点了没反应"
        sb.append("  var pd=window.__pendingDir;");
        sb.append("  if(pd&&week===CUR_WEEK+pd){window.__pendingDir=0;setTimeout(function(){goWeek(pd);},0);}");
        // 「跳到指定周」的目标面板补齐了：直接落位（动画中则等落位完成再跳）
        sb.append("  var pj=window.__pendingJump;");
        sb.append("  if(pj&&week===pj&&!weekAnimating){window.__pendingJump=0;settleWeek(pj);}");
        sb.append("};");
        sb.append("function requestPanel(w){");
        sb.append("  if(w<1||w>TOTAL_WEEKS)return;");
        sb.append("  if(panelFilled(w))return;");
        sb.append("  if(window.Android&&Android.onRequestPanel)Android.onRequestPanel(w);}");
        // 跳到任意周：面板已生成直接落位；没生成就先要，回来后由 applyPanel 落位
        sb.append("function jumpWeek(w){");
        sb.append("  if(!w||w===CUR_WEEK||w<1||w>TOTAL_WEEKS)return;");
        sb.append("  if(panelFilled(w)){if(weekAnimating)forceSettleWeek();settleWeek(w);return;}");
        sb.append("  window.__pendingJump=w;requestPanel(w);}");
        // 点「第 N 周」打开周次选择弹窗（复用教室查询的底部选择面板）
        sb.append("function openWeekPick(){");
        sb.append("  if(typeof TOTAL_WEEKS==='undefined'||!TOTAL_WEEKS){toast('暂无课表数据');return;}");
        sb.append("  var items=[];for(var i=1;i<=TOTAL_WEEKS;i++)items.push({v:String(i),t:'第'+i+'周'});");
        sb.append("  PK.id='weekPick';PK.items=items;PK.sel=String(CUR_WEEK);");
        sb.append("  var tt=document.getElementById('pkTitle');if(tt)tt.textContent='跳到指定周';");
        sb.append("  var sw=document.getElementById('pkSearchWrap');if(sw)sw.style.display='none';");
        sb.append("  var q=document.getElementById('pkSearch');if(q)q.value='';");
        sb.append("  pkRender();");
        sb.append("  document.getElementById('pkMask').classList.add('show');");
        sb.append("  document.getElementById('pkSheet').classList.add('show');");
        sb.append("  var l=document.getElementById('pkList');if(l)l.scrollTop=0;}");
        // 提交：更新标题区文字（表头不滑动，只能原地改），再补齐两侧面板
        sb.append("window.applyWeekHeader=function(m){");
        sb.append("  if(!m||m.w!==CUR_WEEK)return;");
        sb.append("  var d=document.querySelector('.subline .sdate');");
        sb.append("  if(d)d.textContent=m.date;");
        sb.append("  var wk=document.querySelector('.subline .sweek');");
        sb.append("  if(wk)wk.textContent=m.label;");
        sb.append("  var h=document.getElementById('weekHint');");
        sb.append("  if(h)h.textContent=m.hint?m.hint:'\\u00a0';");
        sb.append("  for(var i=1;i<=7;i++){");
        sb.append("    var el=document.getElementById('ghd'+i);if(!el)continue;");
        sb.append("    el.classList.toggle('today',m.todayDow===i);");
        sb.append("    var dt=el.querySelector('.gh-date');");
        sb.append("    if(dt)dt.textContent=(m.days&&m.days[i-1])?m.days[i-1]:'';}");
        sb.append("  var pb=document.getElementById('btnPrev');if(pb)pb.disabled=!m.canPrev;");
        sb.append("  var nb=document.getElementById('btnNext');if(nb)nb.disabled=!m.canNext;");
        sb.append("};");
        // 落位：把横条重排成「左=week-1 / 中=week / 右=week+1」再归位到中间。
        // 必须重排 DOM：归位是把 transform 打回 -33.3333%，屏幕显示的永远是中间那块。
        sb.append("function settleWeek(week){");
        sb.append("  var s=stripEl();if(!s)return;");
        sb.append("  CUR_WEEK=week;");
        sb.append("  var L=panelByWeek(week-1),M=panelByWeek(week),R=panelByWeek(week+1);");
        sb.append("  s.innerHTML='';");
        sb.append("  var lp=document.createElement('div');lp.className='weekpanel nofx';");
        sb.append("  lp.setAttribute('data-week',week-1);lp.setAttribute('data-filled','0');");
        sb.append("  var rp=document.createElement('div');rp.className='weekpanel nofx';");
        sb.append("  rp.setAttribute('data-week',week+1);rp.setAttribute('data-filled','0');");
        sb.append("  s.appendChild(L||lp);if(M)s.appendChild(M);s.appendChild(R||rp);");
        sb.append("  if(M)M.setAttribute('data-week',week);");
        // 重排后把位移打回居中，必须瞬时完成：
        // 带着 transition 会把刚落到中间的面板再"滑"一次，看起来像回弹。
        sb.append("  s.classList.add('dragging');");
        sb.append("  s.style.transform='translateX(-33.3333%)';");
        sb.append("  void s.offsetWidth;");
        sb.append("  s.classList.remove('dragging');");
        sb.append("  if(window.Android&&Android.onWeekSettled)Android.onWeekSettled(week);");
        sb.append("  requestPanel(week-1);requestPanel(week+1);");
        // 落位完成后若还有排队的「跳到指定周」，接着跳
        sb.append("  var pj=window.__pendingJump;if(pj&&pj!==CUR_WEEK){window.__pendingJump=0;jumpWeek(pj);}}");
        // ◀ ▶ 按钮：与手势同一条提交路径，只是位移由代码补完
        // 快速连续滑动时上一次 300ms 补完动画可能还没跑完：与其把手势整段吞掉
        // （表现为"突然滑不动"），不如立即结算上一次翻周，再接住新手势。
        sb.append("var weekAnimating=false,weekAnimTimer=null,weekAnimTarget=0;");
        sb.append("function startWeekAnim(dir){");
        sb.append("  weekAnimating=true;weekAnimTarget=CUR_WEEK+dir;");
        sb.append("  var s=stripEl();swWidth=s?s.clientWidth/3:0;");
        sb.append("  setStripX(-dir*swWidth,true);");
        sb.append("  weekAnimTimer=setTimeout(function(){weekAnimTimer=null;weekAnimating=false;");
        sb.append("    var t=weekAnimTarget;weekAnimTarget=0;settleWeek(t);},300);}");
        sb.append("function forceSettleWeek(){");
        sb.append("  if(!weekAnimating)return;");
        sb.append("  if(weekAnimTimer){clearTimeout(weekAnimTimer);weekAnimTimer=null;}");
        sb.append("  weekAnimating=false;var t=weekAnimTarget;weekAnimTarget=0;");
        sb.append("  if(t)settleWeek(t);}");
        sb.append("function goWeek(dir){");
        sb.append("  if(weekAnimating)forceSettleWeek();");
        sb.append("  if(!hasNeighbor(dir))return;");
        sb.append("  var s=stripEl();if(!s)return;");
        sb.append("  if(!panelFilled(CUR_WEEK+dir)){");
        sb.append("    window.__pendingDir=dir;requestPanel(CUR_WEEK+dir);return;}");
        sb.append("  startWeekAnim(dir);}");
        // 手势：跟手拖动网格横条
        sb.append("function gridBlocked(){");
        sb.append("  if(dragMode||addMode||dragEl)return true;");
        sb.append("  if(window.__suppressSwipe)return true;");
        sb.append("  if(typeof pageStack!=='undefined'&&pageStack.length)return true;");
        sb.append("  var cm=document.getElementById('courseModal');");
        sb.append("  if(cm&&cm.classList.contains('show'))return true;");
        sb.append("  var cd=document.getElementById('cdModal');");
        sb.append("  if(cd&&cd.classList.contains('show'))return true;");
        sb.append("  return false;}");
        sb.append("function onGridStart(e){");
        // 上一次翻周还在补完动画时：立即结算上一次，再接住本次手势。
        // 原来这里直接 return，快速连滑时手势被整段吞掉，表现为"突然滑不动"。
        sb.append("  if(weekAnimating)forceSettleWeek();");
        sb.append("  if(gridBlocked())return;");
        sb.append("  var t=e.touches[0];");
        sb.append("  swStartX=t.clientX;swStartY=t.clientY;swT0=Date.now();");
        sb.append("  swDx=0;swDecided=false;swDragging=false;");
        sb.append("  var s=stripEl();swWidth=s?s.clientWidth/3:0;}");
        sb.append("function onGridMove(e){");
        sb.append("  if(!swStartX)return;");
        sb.append("  var t=e.touches[0];");
        sb.append("  var dx=t.clientX-swStartX,dy=t.clientY-swStartY;");
        sb.append("  if(!swDecided){");
        // 纵向为主就让页面自己滚，不要在横条上抢手势
        sb.append("    if(Math.abs(dy)>12&&Math.abs(dy)>=Math.abs(dx)){swStartX=0;return;}");
        sb.append("    if(Math.abs(dx)<8)return;");
        sb.append("    if(gridBlocked()){swStartX=0;return;}");
        sb.append("    swDecided=true;swDragging=true;");
        // 手指一动就取消已排队的长按浮起，避免拖到一半卡片突然跳起来
        sb.append("    if(pressTimer){clearTimeout(pressTimer);pressTimer=null;}");
        sb.append("  }");
        sb.append("  if(!swDragging)return;");
        sb.append("  e.preventDefault();");
        sb.append("  swDx=resist(dx);setStripX(swDx,false);}");
        sb.append("function onGridEnd(){");
        sb.append("  if(!swDragging){swStartX=0;return;}");
        sb.append("  swDragging=false;swDecided=false;swStartX=0;");
        sb.append("  var dt=Math.max(1,Date.now()-swT0);");
        sb.append("  var v=Math.abs(swDx)/dt;");
        sb.append("  var far=Math.abs(swDx)>swWidth*0.22;");
        sb.append("  var fast=v>0.35&&Math.abs(swDx)>swWidth*0.06;");
        sb.append("  var dir=swDx<0?1:-1;");
        sb.append("  if((far||fast)&&hasNeighbor(dir)&&panelFilled(CUR_WEEK+dir)){");
        sb.append("    startWeekAnim(dir);");
        sb.append("  }else{");
        sb.append("    setStripX(0,true);");
        sb.append("  }");
        sb.append("  swDx=0;}");
        // 查询教室的默认条件：当前周 / 今天 / 第1大节起（注入 todayDow，免去用户逐项选）
        sb.append("var TODAY_DOW=").append(todayDow).append(";");
        // ===== 轻量 toast（不依赖原生 Toast，错误提示不会打断操作）=====
        sb.append("function toast(msg){");
        sb.append("  var t=document.getElementById('toastBox');");
        sb.append("  if(!t){t=document.createElement('div');t.id='toastBox';t.className='toast';document.body.appendChild(t);}");
        sb.append("  t.textContent=msg;t.classList.add('show');");
        sb.append("  clearTimeout(window.__toastTimer);");
        sb.append("  window.__toastTimer=setTimeout(function(){t.classList.remove('show');},1800);}");
        // ===== 加课模式：点「＋ 加课」后点空白格子 =====
        sb.append("var addMode=false;");
        sb.append("function toggleAddMode(){");
        sb.append("  addMode=!addMode;");
        sb.append("  document.body.classList.toggle('adding',addMode);");
        sb.append("  if(addMode)toast('点任意格子选位置，已有课也能叠加');}");
        sb.append("function onEmptyClick(ev,d,r){");
        sb.append("  if(!addMode)return;");
        sb.append("  ev.stopPropagation();");
        sb.append("  addMode=false;");
        sb.append("  document.body.classList.remove('adding');");
        sb.append("  Android.onPickCell(d,r);}");
        // 已有课的格子：加课模式下点卡片 = 在这一格叠加一门课（不再打开详情）
        sb.append("function onCardPick(el){");
        sb.append("  var d=parseInt(el.getAttribute('data-day')||'0',10);");
        sb.append("  var r=parseInt(el.getAttribute('data-row')||'0',10);");
        sb.append("  if(d<1||r<1)return;");
        sb.append("  addMode=false;");
        sb.append("  document.body.classList.remove('adding');");
        sb.append("  Android.onPickCell(d,r);}");
        // ===== 自定义课程表单 =====
        sb.append("var CUSTOM_COLORS=[").append(joinQuoted(CUSTOM_COLORS)).append("];");
        sb.append("var cfSelColor=CUSTOM_COLORS[0];");
        sb.append("function buildColorPicker(){");
        sb.append("  var box=document.getElementById('cfColors');if(!box)return;");
        sb.append("  var h='';");
        sb.append("  for(var i=0;i<CUSTOM_COLORS.length;i++){");
        sb.append("    var c=CUSTOM_COLORS[i];");
        sb.append("    h+='<button class=\"cf-color\" data-c=\"'+c+'\" style=\"background:'+c+'\" onclick=\"pickColor(\\''+c+'\\')\"></button>';}");
        sb.append("  box.innerHTML=h;}");
        sb.append("function pickColor(c){cfSelColor=c;");
        sb.append("  var bs=document.querySelectorAll('.cf-color');");
        sb.append("  for(var i=0;i<bs.length;i++){bs[i].classList.toggle('sel',bs[i].getAttribute('data-c')===c);}}");
        sb.append("var cfRepeat='custom';");
        // 仅本周 / 按周次 互斥切换：选「仅本周」后隐藏周次输入，保存时自动写当前周
        sb.append("function pickRepeat(v){");
        sb.append("  cfRepeat=(v==='week')?'week':'custom';");
        sb.append("  var box=document.getElementById('cfWeeksBox');");
        sb.append("  var tip=document.getElementById('cfOnceTip');");
        sb.append("  if(box)box.style.display=cfRepeat==='week'?'none':'block';");
        sb.append("  if(tip)tip.style.display=cfRepeat==='week'?'block':'none';");
        sb.append("  var a=document.getElementById('cfRepWeek'),b=document.getElementById('cfRepCustom');");
        sb.append("  if(a)a.classList.toggle('sel',cfRepeat==='week');");
        sb.append("  if(b)b.classList.toggle('sel',cfRepeat==='custom');}");
        sb.append("function openCustomForm(d){");
        sb.append("  d=d||{};");
        sb.append("  var g=function(id){return document.getElementById(id);};");
        sb.append("  g('cfId').value=d.id||'';");
        sb.append("  g('cfTitle').textContent=d.id?'编辑课程':'添加课程';");
        sb.append("  g('cfName').value=d.name||'';");
        sb.append("  g('cfDay').value=String(d.day||1);");
        sb.append("  g('cfRow').value=String(d.row||1);");
        // 目标格已有课 → 说明这是叠加加课（occupied 由 Java 侧按当前周统计后传入）
        sb.append("  var ot=g('cfOverlapTip');");
        sb.append("  if(ot){var oc=parseInt(d.occupied||'0',10)||0;");
        sb.append("    if(oc>0){ot.style.display='block';");
        sb.append("      ot.textContent='该时段已有 '+oc+' 门课，保存后会叠加显示在同一格，并用琥珀色描边标记。';}");
        sb.append("    else{ot.style.display='none';ot.textContent='';}}");
        sb.append("  var wk=(d.weeks||'').trim();");
        sb.append("  g('cfWeeks').value=wk;");
        // 周次恰好是「当前显示周」的单值 → 视为单次卡片，其余走按周次
        sb.append("  pickRepeat(wk===String(CUR_WEEK)?'week':'custom');");
        sb.append("  g('cfLoc').value=d.location||'';");
        sb.append("  g('cfTeacher').value=d.teacher||'';");
        sb.append("  g('cfRemark').value=d.remark||'';");
        sb.append("  g('cfDelete').style.display=d.id?'block':'none';");
        sb.append("  pickColor(d.color||CUSTOM_COLORS[0]);");
        sb.append("  openPage('pageCustomEdit');}");
        sb.append("function saveCustom(){");
        sb.append("  var g=function(id){return document.getElementById(id);};");
        sb.append("  var name=g('cfName').value.trim();");
        sb.append("  if(!name){toast('请填写课程名称');g('cfName').focus();return;}");
        sb.append("  var d={id:g('cfId').value,name:name,");
        sb.append("    day:parseInt(g('cfDay').value||'1',10),row:parseInt(g('cfRow').value||'1',10),");
        // 单次卡片：周次直接写当前显示周，卡片只在这一周出现
        sb.append("    weeks:(cfRepeat==='week'?String(CUR_WEEK):g('cfWeeks').value.trim()),");
        sb.append("    location:g('cfLoc').value.trim(),");
        sb.append("    teacher:g('cfTeacher').value.trim(),remark:g('cfRemark').value.trim(),");
        sb.append("    color:cfSelColor};");
        sb.append("  Android.onSaveCustom(JSON.stringify(d));}");
        sb.append("function deleteCurrentCustom(){");
        sb.append("  var id=document.getElementById('cfId').value;");
        sb.append("  if(id)Android.onDeleteCustom(id);}");
        sb.append("function deleteCustom(ev,id){ev.stopPropagation();Android.onDeleteCustom(id);}");
        // ===== 长按拖动调整课程位置 =====
        sb.append("var dragMode=false,dragEl=null,undoStack=[],pressTimer=null;");
        sb.append("var pressX=0,pressY=0,baseR=null,curHint=null,didDrag=false;");
        sb.append("function swapNodes(a,b){");
        sb.append("  var ph=document.createElement('div');");
        sb.append("  a.parentNode.insertBefore(ph,a);");
        sb.append("  b.parentNode.insertBefore(a,b);");
        sb.append("  ph.parentNode.insertBefore(b,ph);");
        sb.append("  ph.parentNode.removeChild(ph);");
        // 交换 data-day/data-row 属性，确保位置信息随节点一起更新
        sb.append("  var ad=a.getAttribute('data-day'),ar=a.getAttribute('data-row');");
        sb.append("  var bd=b.getAttribute('data-day'),br=b.getAttribute('data-row');");
        sb.append("  a.setAttribute('data-day',bd||'');a.setAttribute('data-row',br||'');");
        sb.append("  b.setAttribute('data-day',ad||'');b.setAttribute('data-row',ar||'');");
        sb.append("}");
        sb.append("function setHint(el){");
        sb.append("  if(curHint===el)return;");
        sb.append("  if(curHint)curHint.classList.remove('drop-hint');");
        sb.append("  curHint=el;");
        sb.append("  if(el)el.classList.add('drop-hint');");
        sb.append("}");
        sb.append("function showDock(){");
        sb.append("  var db=document.getElementById('dockbar');");
        sb.append("  if(db)db.classList.add('show');");
        sb.append("  dragMode=true;");
        sb.append("}");
        sb.append("function updateUndoBtn(){");
        sb.append("  var b=document.getElementById('undoBtn');");
        sb.append("  if(b)b.disabled=undoStack.length===0;");
        sb.append("}");
        // 长按浮起：卡片变大+微透明+深阴影；拖动中底部栏下滑隐藏
        sb.append("function startLift(card){");
        sb.append("  dragEl=card;");
        sb.append("  baseR=card.getBoundingClientRect();");
        sb.append("  card.classList.add('dragging');");
        sb.append("  card.style.pointerEvents='none';");
        sb.append("  showDock();");
        sb.append("  var db=document.getElementById('dockbar');");
        sb.append("  if(db)db.classList.add('hide-drag');");
        sb.append("}");
        // 跟手移动：手指即卡片中心（首帧关闭过渡实现即时跟随）
        sb.append("function moveDrag(x,y){");
        sb.append("  if(!dragEl||!baseR)return;");
        sb.append("  dragEl.style.transition='none';");
        sb.append("  var dx=x-(baseR.left+baseR.width/2);var dy=y-(baseR.top+baseR.height/2);");
        sb.append("  dragEl.style.transform='translate('+dx+'px,'+dy+'px) scale(1.07)';");
        sb.append("  var under=document.elementFromPoint(x,y);");
        sb.append("  var cell=under?under.closest('.course,.empty'):null;");
        sb.append("  setHint(cell&&cell!==dragEl?cell:null);");
        sb.append("  window.__suppressSwipe=true;");
        sb.append("}");
        // 松手结算：目标为空格→放置；有课→对调；格子统一按节点互换处理
        // 注意：必须先用 pointerEvents='none' 状态查 elementFromPoint（此时拖动卡片不参与命中），
        // 再恢复样式；否则卡片自己挡在触点下方会命中自身导致交换失败弹回
        sb.append("function endDrag(t){");
        sb.append("  var el=dragEl;dragEl=null;");
        // 落位期间禁用入场动画，否则复位 transform 会让 popIn 重播，出现"上滑/弹入"特效
        sb.append("  el.classList.add('no-anim');");
        sb.append("  el.classList.remove('dragging');");
        sb.append("  var target=null;");
        sb.append("  if(t){var under=document.elementFromPoint(t.clientX,t.clientY);");
        sb.append("    target=under?under.closest('.course,.empty'):null;}");
        // 直接落位：清掉拖动时的位移/缩放，不做过渡
        sb.append("  el.style.transition='none';");
        sb.append("  el.style.transform='';");
        sb.append("  el.style.pointerEvents='';");
        sb.append("  if(target&&target!==el){");
        sb.append("    swapNodes(el,target);");
        sb.append("    undoStack.push([el,target]);");
        sb.append("    updateUndoBtn();");
        sb.append("  }");
        sb.append("  setHint(null);");
        sb.append("  didDrag=true;");
        sb.append("  var db=document.getElementById('dockbar');");
        sb.append("  if(db)db.classList.remove('hide-drag');");
        // 下一帧恢复 transition 与动画类，避免影响后续正常刷新
        sb.append("  requestAnimationFrame(function(){");
        sb.append("    el.style.transition='';");
        sb.append("    el.classList.remove('no-anim');");
        sb.append("  });");
        sb.append("  setTimeout(function(){window.__suppressSwipe=false;},50);");
        sb.append("}");
        sb.append("function undoMove(){");
        sb.append("  if(!undoStack.length)return;");
        sb.append("  var p=undoStack.pop();");
        // 撤销同样直接落位，不播放入场动画
        sb.append("  p[0].classList.add('no-anim');p[1].classList.add('no-anim');");
        sb.append("  swapNodes(p[0],p[1]);");
        sb.append("  requestAnimationFrame(function(){");
        sb.append("    p[0].classList.remove('no-anim');p[1].classList.remove('no-anim');");
        sb.append("  });");
        sb.append("  updateUndoBtn();");
        sb.append("}");
        sb.append("function applyMoves(){");
        sb.append("  var cards=document.querySelectorAll('.course[data-day]');var moves=[];");
        sb.append("  cards.forEach(function(el){");
        sb.append("    var d=parseInt(el.getAttribute('data-day')||'0');");
        sb.append("    var r=parseInt(el.getAttribute('data-row')||'0');");
        sb.append("    if(d<1||r<1)return;");
        sb.append("    var keys=(el.getAttribute('data-keys')||'').split(';;;');");
        sb.append("    for(var ki=0;ki<keys.length;ki++){");
        sb.append("      if(keys[ki])moves.push({k:keys[ki],d:d,r:r});");
        sb.append("    }");
        sb.append("  });");
        sb.append("  Android.onApplyMoves(JSON.stringify(moves),CUR_WEEK);");
        sb.append("}");
        sb.append("function cancelMoves(){Android.onCancelMoves(CUR_WEEK);}");
        // 初始化：把当前周的面板居中，两侧放占位块并异步向 Java 要网格片段。
        // 占位块先占住宽度，横条几何从一开始就是三块等宽，拉的时候不会错位。
        sb.append("function initStrip(){");
        sb.append("  var s=stripEl();if(!s)return;");
        sb.append("  var mid=panelByWeek(CUR_WEEK);if(!mid)return;");
        sb.append("  mid.setAttribute('data-filled','1');");
        sb.append("  var lp=document.createElement('div');lp.className='weekpanel nofx';");
        sb.append("  lp.setAttribute('data-week',CUR_WEEK-1);lp.setAttribute('data-filled','0');");
        sb.append("  var rp=document.createElement('div');rp.className='weekpanel nofx';");
        sb.append("  rp.setAttribute('data-week',CUR_WEEK+1);rp.setAttribute('data-filled','0');");
        sb.append("  s.innerHTML='';s.appendChild(lp);s.appendChild(mid);s.appendChild(rp);");
        sb.append("  s.style.transform='translateX(-33.3333%)';");
        sb.append("  requestPanel(CUR_WEEK-1);requestPanel(CUR_WEEK+1);}");
        sb.append("bindCards(document);");
        sb.append("initStrip();");
        // 手势只绑在网格区：标题、按钮、底部标签栏都不参与滑动
        // touchmove 必须 passive:false，否则 preventDefault 无效、页面会跟着一起滚
        sb.append("var gvEl=document.querySelector('.gridview');");
        sb.append("if(gvEl){");
        sb.append("  gvEl.addEventListener('touchstart',onGridStart,{passive:true});");
        sb.append("  gvEl.addEventListener('touchmove',onGridMove,{passive:false});");
        sb.append("  gvEl.addEventListener('touchend',onGridEnd);");
        sb.append("  gvEl.addEventListener('touchcancel',onGridEnd);");
        sb.append("}");
        // ===== 课程详情弹窗：窗口生长动画（仿鸿蒙/ColorOS 应用展开） =====
        //
        // 关键点：只动窗口边界(left/top/width/height/border-radius)，不动内容。
        // 卡片与弹窗长宽比差异极大，任何整体 scale 都会把文字拉变形；
        // 这里内容按最终尺寸恒定排版，窗口从卡片矩形平滑长到弹窗矩形。
        //
        // 时间轴：
        //   0ms    窗口 = 卡片矩形（圆角同卡片），背景为卡片色，内容 opacity 0
        //   0-260ms 窗口生长到弹窗矩形，圆角变 18px，背景由卡片色过渡到弹窗底色
        //   90ms   内容开始淡入（让"窗口先成形、内容再浮现"）
        //   260ms  收尾，清掉内联样式
        sb.append("function playOpenAnim(mEl, cardRect, startBg, paleBg){");
        sb.append("  var inner=mEl.querySelector('.m-inner');");
        sb.append("  var r=cardRect;");
        // 先量出弹窗的最终几何：临时归位再测
        sb.append("  mEl.style.transition='none';");
        sb.append("  mEl.style.left='';mEl.style.top='';mEl.style.width='';mEl.style.height='';");
        sb.append("  mEl.style.borderRadius='';mEl.style.background='';");
        sb.append("  var mr=mEl.getBoundingClientRect();");
        sb.append("  if(!r||!r.width){return {left:mr.left,top:mr.top,width:mr.width,height:mr.height};}");
        // 起始：窗口钉在卡片位置，圆角与卡片一致
        sb.append("  mEl.style.left=r.left+'px';mEl.style.top=r.top+'px';");
        sb.append("  mEl.style.width=r.width+'px';mEl.style.height=r.height+'px';");
        sb.append("  mEl.style.borderRadius='10px';");
        sb.append("  mEl.style.background=startBg;");
        sb.append("  if(inner){inner.style.transition='none';inner.style.opacity='0';}");
        sb.append("  mEl.getBoundingClientRect();");
        // 起点已就位。过渡推迟到下一帧启动：
        // 调用方会在本函数返回后才 add('show')，若此刻就启动过渡，
        // 窗口在不可见状态下已经跑掉一部分，露出时动画就不从头开始了。
        sb.append("  requestAnimationFrame(function(){");
        // 生长：用自定义缓动贴近系统"展开"手感（先快后慢、末段微收）
        sb.append("  var ease='cubic-bezier(.22,.9,.28,1)';");
        sb.append("  mEl.style.transition='left 260ms '+ease+',top 260ms '+ease");
        sb.append("    +',width 260ms '+ease+',height 260ms '+ease");
        sb.append("    +',border-radius 260ms '+ease+',background-color 300ms ease';");
        sb.append("  mEl.style.left=mr.left+'px';mEl.style.top=mr.top+'px';");
        sb.append("  mEl.style.width=mr.width+'px';mEl.style.height=mr.height+'px';");
        sb.append("  mEl.style.borderRadius='18px';");
        sb.append("  mEl.style.background=paleBg;");
        sb.append("  if(inner){");
        sb.append("    inner.style.transition='opacity 170ms ease 90ms';");
        sb.append("    inner.style.opacity='1';");
        sb.append("  }");
        sb.append("  });");
        sb.append("  requestAnimationFrame(function(){");
        // 原卡片淡出：与窗口生长同时启动，不留延迟。
        //
        // 窗口起点被钉成与卡片完全相同的矩形（位置/尺寸/圆角/背景色都一致），
        // 所以 t=0 这一帧两者像素级重合，此刻交接是零视觉差异的。
        // 一旦窗口开始变大就会离开卡片原位，若此时卡片还在，那块位置就会
        // 先露出一个空洞再消失 —— 就是"卡片突然消失"。
        sb.append("  var oc=window.__openedCard;");
        sb.append("  if(oc){");
        sb.append("    oc.style.transition='opacity 160ms ease';");
        sb.append("    oc.style.opacity='0';");
        sb.append("  }");
        sb.append("  setTimeout(function(){");
        // 终点即 CSS 默认的居中位置，清空内联样式不会跳位；
        // 但仍先关掉 transition 再清，避免复位这一帧被当作过渡起点
        sb.append("    mEl.style.transition='none';");
        sb.append("    mEl.style.left='';mEl.style.top='';");
        sb.append("    mEl.style.width='';mEl.style.height='';mEl.style.borderRadius='';");
        sb.append("    mEl.style.background='';");
        sb.append("    if(inner){inner.style.transition='';inner.style.opacity='';}");
        sb.append("    mEl.getBoundingClientRect();");
        sb.append("    mEl.style.transition='';");
        sb.append("  },320);");
        sb.append("  });");
        sb.append("  return mr;");
        sb.append("}");
        // 关闭：反向收缩回卡片位置
        sb.append("function playCloseAnim(mEl, cardRect, startBg, done){");
        sb.append("  var inner=mEl.querySelector('.m-inner');");
        sb.append("  var r=cardRect;");
        sb.append("  if(!r||!r.width){if(done)done();return;}");
        sb.append("  var mr=mEl.getBoundingClientRect();");
        sb.append("  mEl.style.transition='none';");
        sb.append("  mEl.style.left=mr.left+'px';mEl.style.top=mr.top+'px';");
        sb.append("  mEl.style.width=mr.width+'px';mEl.style.height=mr.height+'px';");
        sb.append("  mEl.style.borderRadius='18px';");
        sb.append("  if(inner){inner.style.transition='none';inner.style.opacity='1';}");
        sb.append("  mEl.getBoundingClientRect();");
        sb.append("  var ease='cubic-bezier(.4,0,.6,1)';");
        sb.append("  mEl.style.transition='left 210ms '+ease+',top 210ms '+ease");
        sb.append("    +',width 210ms '+ease+',height 210ms '+ease");
        sb.append("    +',border-radius 210ms '+ease+',background-color 210ms ease';");
        sb.append("  if(inner){inner.style.transition='opacity 90ms ease';inner.style.opacity='0';}");
        sb.append("  mEl.style.left=r.left+'px';mEl.style.top=r.top+'px';");
        sb.append("  mEl.style.width=r.width+'px';mEl.style.height=r.height+'px';");
        sb.append("  mEl.style.borderRadius='10px';");
        sb.append("  if(startBg)mEl.style.background=startBg;");
        // 收缩结束后先 done()（移除 .show），此时 .modal 由 CSS 立刻变为不可见，
        // 再清内联几何样式就不会露出一帧「弹回居中大尺寸」。
        sb.append("  setTimeout(function(){");
        sb.append("    if(done)done();");
        sb.append("    mEl.style.transition='none';");
        sb.append("    mEl.style.left='';mEl.style.top='';");
        sb.append("    mEl.style.width='';mEl.style.height='';mEl.style.borderRadius='';");
        sb.append("    mEl.style.background='';mEl.style.opacity='';");
        sb.append("    if(inner){inner.style.transition='';inner.style.opacity='';}");
        sb.append("    mEl.getBoundingClientRect();");
        sb.append("    mEl.style.transition='';");
        sb.append("  },250);");
        sb.append("}");
        // 弹窗（课程详情：窗口收缩回卡片；设置：直接关闭）
        sb.append("function closeModal(){");
        sb.append("  var modal=document.getElementById('courseModal');");
        sb.append("  var mEl=document.querySelector('#courseModal .modal');");
        sb.append("  var r=window.__cardRect;");
        sb.append("  var oc=window.__openedCard;window.__openedCard=null;");
        // 卡片先设为透明（此刻窗口还盖着它，看不出变化），
        // 但要与收缩的「末段」重叠渐显，不能等收缩完全结束才开始：
        // 窗口缩到比卡片小时，卡片原位会先露出空洞，随后才补上 —— 就是"消失再出现"。
        // 120ms 时窗口已接近卡片尺寸，从这里渐显到 250ms 正好衔接。
        sb.append("  if(oc){oc.style.transition='none';oc.style.transform='';oc.style.opacity='0';");
        sb.append("    oc.getBoundingClientRect();");
        sb.append("    setTimeout(function(){");
        sb.append("      oc.style.transition='opacity 130ms ease';oc.style.opacity='1';");
        sb.append("    },120);}");
        // 注意：拼进 JS 的注释必须用 /* */ —— sb.append 不产生换行，
        // 用 // 会把该行之后的所有代码全注释掉，导致 "Unexpected end of input"
        sb.append("  /* 遮罩淡出由 .show 类的 CSS transition 负责，这里只移除类 */");
        sb.append("  playCloseAnim(mEl, r, window.__modalColor, function(){");
        sb.append("    modal.classList.remove('show');");
        // 卡片渐显已在 120ms 时启动、250ms 结束，这里只做收尾清理。
        // 不要再设 opacity/transition —— 会打断正在播放的淡入导致闪跳。
        sb.append("    if(oc){setTimeout(function(){oc.style.transition='';oc.style.transform='';");
        sb.append("      oc.style.opacity='';oc.style.willChange='';},140);}");
        sb.append("    window.__cardRect=null;");
        sb.append("  });");
        sb.append("}");
        // 从详情直接跳到表单/删除时用：立即收起弹窗，不播收缩动画，
        // 否则弹窗（z-index 100）会在表单页（95）之上多压 250ms
        sb.append("function closeModalNow(){");
        sb.append("  var modal=document.getElementById('courseModal');");
        sb.append("  if(modal)modal.classList.remove('show');");
        sb.append("  var mEl=document.querySelector('#courseModal .modal');");
        sb.append("  if(mEl){mEl.style.transition='none';mEl.style.left='';mEl.style.top='';");
        sb.append("    mEl.style.width='';mEl.style.height='';mEl.style.borderRadius='';mEl.style.background='';}");
        sb.append("  var oc=window.__openedCard;");
        sb.append("  if(oc){oc.style.transition='';oc.style.transform='';oc.style.opacity='';}");
        sb.append("  window.__openedCard=null;window.__cardRect=null;");
        sb.append("  if(mEl){mEl.getBoundingClientRect();mEl.style.transition='';}}");
        // ===== 设置页导航（独立页面，从右侧滑入）=====
        sb.append("var pageStack=[];");
        // 标签顺序决定滑动方向：课表(0) → 我的(1)
        // 「更多功能」页已并入「我的」，标签只剩两个。
        sb.append("var TAB_ORDER=['schedule','mine'];");
        sb.append("var curTab='schedule';");
        sb.append("function tabPageEl(t){return document.getElementById(t==='mine'?'pageMine':'');}");
        sb.append("function tabOfPage(id){return id==='pageMine'?'mine':'';}");
        // 课表主体是否让位：只有停在标签级页面（我的）时才左移，
        // 设置、检查更新这类二级页仍然整页盖上来，不推课表。
        sb.append("function applyTabShift(){var tv=document.querySelector('.tabview');");
        sb.append("  if(tv)tv.classList.toggle('shift-out',curTab!=='schedule');}");
        // 把一个页面恢复到「未打开」的干净状态：
        // 必须同时清掉滑动动画类与 display，否则下次进入会从错误的位置起跑。
        sb.append("function resetPage(el){if(!el)return;");
        sb.append("  el.classList.remove('show','leave-left','leave-right','enter-right','enter-left');");
        sb.append("  el.style.display='';}");
        // 有全屏页盖住课表时隐藏课表主体：玻璃页（半透明+blur）叠在课表上，
        // 会透出错位的课表内容；藏掉后玻璃页后面就是背景图，观感干净。
        // 同时通知原生层收起顶部刘海渐变，避免切页面后顶部颜色不一致。
        sb.append("function setTabviewHidden(h){var tv=document.querySelector('.tabview');");
        sb.append("  if(tv)tv.style.display=h?'none':'';");
        sb.append("  document.body.classList.toggle('pagecover',!!h);");
        sb.append("  if(window.Android&&Android.onPageCoverChanged)Android.onPageCoverChanged(h?1:0);}");
        // 恢复栈顶页的显示（打开二级页时它下面的页被藏掉了，关回来要还原）
        sb.append("function showStackTop(){var n=pageStack.length;");
        sb.append("  setTabviewHidden(n>0);");
        sb.append("  if(n){var top=document.getElementById(pageStack[n-1]);");
        sb.append("    if(top){top.style.display='';top.classList.add('show');}}}");
        sb.append("function openPage(id){var p=document.getElementById(id);if(!p)return;");
        // 玻璃页叠玻璃页会把阴影叠深：先把栈里现有的顶页藏掉，关回来时再恢复
        sb.append("  if(pageStack.length){var below=document.getElementById(pageStack[pageStack.length-1]);");
        sb.append("    if(below){below.classList.remove('show');below.style.display='none';}}");
        sb.append("  p.classList.remove('leave-left','leave-right','enter-right','enter-left');");
        sb.append("  p.style.display='';p.classList.add('show');pageStack.push(id);");
        sb.append("  setTabviewHidden(true);");
        sb.append("  var tb=tabOfPage(id);if(tb){curTab=tb;applyTabShift();updateTabs(curTab);}}");
        // 跨功能跳转用：openPage 不会隐藏上一个页面，若目标页在 DOM 里更靠前
        // （查询教室就排在找空教室之前），旧页会盖在上面，看起来像没跳转。
        sb.append("function gotoPage(id){");
        sb.append("  var ps=document.querySelectorAll('.page');");
        sb.append("  for(var i=0;i<ps.length;i++)ps[i].classList.remove('show');");
        sb.append("  pageStack.length=0;openPage(id);}");
        sb.append("function closePage(){var id=pageStack.pop();if(!id)return;");
        sb.append("  resetPage(document.getElementById(id));");
        // 关掉的若正是当前标签页（从「我的」按返回），回到课表
        sb.append("  var tb=tabOfPage(id);if(tb&&tb===curTab)curTab='schedule';");
        sb.append("  updateTabs(curTab);applyTabShift();");
        sb.append("  showStackTop();}");
        // ===== 底部标签栏 =====
        sb.append("function updateTabs(t){");
        sb.append("  var ids={schedule:'tabSchedule',mine:'tabMine'};");
        sb.append("  for(var k in ids){var el=document.getElementById(ids[k]);");
        sb.append("    if(el)el.classList.toggle('on',k===t);}}");
        // 兼容旧调用点（拖动/刷新等会调它）：统一按 curTab 同步标签与课表让位
        sb.append("function syncTabWithStack(){updateTabs(curTab);applyTabShift();}");
        // ===== 标签切换：旧页滑走、新页同时滑入，中间不再「收回再弹出」=====
        // 所有非目标页立刻归位（无动画）：切标签时不该看到残留的二级页
        sb.append("function hideAllPages(except){");
        sb.append("  var ps=document.querySelectorAll('.page');");
        sb.append("  for(var i=0;i<ps.length;i++){var p=ps[i];if(p===except)continue;");
        sb.append("    resetPage(p);p.style.display='none';}}");
        sb.append("function switchTab(t){");
        // 已在目标标签：只把二级页（设置等）收掉，不播滑动动画
        sb.append("  if(t===curTab){");
        sb.append("    var keep=tabPageEl(t);");
        sb.append("    while(pageStack.length){");
        sb.append("      var topId=pageStack[pageStack.length-1];");
        sb.append("      if(keep&&topId===keep.id)break;");
        sb.append("      resetPage(document.getElementById(pageStack.pop()));}");
        sb.append("    var ps=document.querySelectorAll('.page');");
        sb.append("    for(var i=0;i<ps.length;i++){if(keep&&ps[i]===keep)continue;");
        sb.append("      resetPage(ps[i]);ps[i].style.display='none';}");
        sb.append("    if(keep){keep.style.display='';keep.classList.add('show');}");
        sb.append("    setTabviewHidden(pageStack.length>0);");
        sb.append("    applyTabShift();updateTabs(curTab);return;}");
        sb.append("  var from=TAB_ORDER.indexOf(curTab),to=TAB_ORDER.indexOf(t);");
        sb.append("  var dir=to>from?1:-1;");
        sb.append("  var prev=tabPageEl(curTab),next=tabPageEl(t);");
        // 二级页（设置/检查更新/查询教室…）直接收起，不参与滑动
        sb.append("  hideAllPages(prev);");
        sb.append("  pageStack.length=0;");
        sb.append("  setTabviewHidden(t!=='schedule');");
        sb.append("  curTab=t;applyTabShift();");
        // 旧页：向左（正向）或向右（反向）滑走，动画结束再彻底隐藏，
        // 否则它会带着 leave 类停在半路，下次进入时从错误的位置开始。
        sb.append("  if(prev){");
        sb.append("    prev.style.display='';prev.classList.remove('show');");
        sb.append("    prev.classList.add(dir>0?'leave-left':'leave-right');");
        sb.append("    prev.addEventListener('animationend',function h(){");
        sb.append("      prev.removeEventListener('animationend',h);");
        sb.append("      prev.classList.remove('leave-left','leave-right');");
        sb.append("      if(!prev.classList.contains('show'))prev.style.display='none';},false);}");
        // 新页：从另一侧滑入；动画结束后保留 .show 作为最终静止态
        sb.append("  if(next){");
        sb.append("    next.style.display='';next.classList.remove('show','leave-left','leave-right');");
        sb.append("    next.classList.add(dir>0?'enter-right':'enter-left');");
        sb.append("    next.addEventListener('animationend',function h(){");
        sb.append("      next.removeEventListener('animationend',h);");
        sb.append("      next.classList.remove('enter-right','enter-left');");
        sb.append("      next.classList.add('show');},false);");
        sb.append("    pageStack.push(next.id);}");
        sb.append("  curTab=t;");
        // 打开「我的」时用本地最新资料刷新卡片：
        // 启动时可能还没抓到姓名（要等教务首页），之后课表页不再重渲染，卡片会停在空态。
        sb.append("  if(t==='mine'&&window.Android&&Android.onMineTabOpened)Android.onMineTabOpened();");
        sb.append("  updateTabs(t);}");
        // 我的页「重新获取资料」：先回课表再触发刷新，避免用户停在覆盖页看不到进度
        // 「重新获取资料」：直接去教务首页抓姓名/学号（不必刷新整个课表，也不必退出重登）
        sb.append("function reFetchMe(){Android.onRefreshStudentInfo();}");
        // 更多功能 → 查询教室
        sb.append("function openClassroomQuery(){openPage('pageClassroom');");
        sb.append("  if(window.Android&&Android.onClassroomQueryOpened)Android.onClassroomQueryOpened();}");
        // ===== 查询教室：表单渲染（数据由 Java 侧的隐藏 WebView 从教务取回）=====
        sb.append("function crEsc(s){return String(s==null?'':s).replace(/&/g,'&amp;')");
        sb.append("  .replace(/</g,'&lt;').replace(/>/g,'&gt;');}");
        sb.append("function crFill(id,arr,ph){var s=document.getElementById(id);if(!s)return;");
        sb.append("  var h=ph?\"<option value=''>\"+crEsc(ph)+\"</option>\":'';");
        sb.append("  for(var i=0;i<(arr||[]).length;i++){h+=\"<option value='\"+crEsc(arr[i].v)+\"'>\"+crEsc(arr[i].t)+\"</option>\";}");
        sb.append("  s.innerHTML=h;crSync(id);}");
        // 选择行只是「显示器」，值仍存在原生 select 里：显示与取值永远同步，避免两边各存一份状态
        sb.append("function crSync(id){var s=document.getElementById(id),t=document.getElementById(id+'Txt');");
        sb.append("  if(!s||!t)return;var i=s.selectedIndex,txt='',ph=true;");
        sb.append("  if(i>=0&&s.options[i]){txt=(s.options[i].text||'').replace(/\\s+/g,' ').trim();ph=!s.value;}");
        sb.append("  if(!txt)txt=(id==='crRoom'?'全部教室':'请选择');");
        sb.append("  t.textContent=txt;var f=document.getElementById(id+'F');");
        sb.append("  if(f){if(ph)f.classList.add('ph');else f.classList.remove('ph');}}");
        // ===== 底部选择面板（替代系统下拉）=====
        sb.append("var PK={id:'',items:[],sel:''};");
        sb.append("var PK_TITLE={crCampus:'选择校区',crBuilding:'选择教学楼',crRoom:'选择教室',");
        sb.append("crWeek:'选择周次',crWeekday:'选择星期',crJc1:'起始节次',crJc2:'结束节次',");
        sb.append("eqCampus:'选择校区',eqBuilding:'选择教学楼',eqWeek:'选择周次',");
        sb.append("eqWeekday:'选择星期',eqJc1:'起始节次',eqJc2:'结束节次'};");
        // 占位项（请选择/读取中/请先选择）不进可选列表；
        // 「全部教室」「全部教学楼（整校区）」这类空值选项是真实选择，必须保留。
        sb.append("function pkPlaceholder(t){return /请选择|读取中|请先选择/.test(t||'');}");
        sb.append("function crPick(id){");
        sb.append("  var s=document.getElementById(id);if(!s)return;");
        sb.append("  var f=document.getElementById(id+'F');");
        sb.append("  if(f&&f.classList.contains('lock')){toast('正在读取教室清单，请稍候');return;}");
        sb.append("  var items=[];for(var i=0;i<s.options.length;i++){var o=s.options[i];");
        // 空值项是占位符（「请选择校区」「读取中…」），不该出现在可选列表里；
        // 只有教室的「全部教室（整栋楼）」是真正的空值选项，要保留。
        sb.append("    if(!o.value&&pkPlaceholder(o.text))continue;items.push({v:o.value,t:o.text});}");
        sb.append("  if(!items.length){toast('暂无可选项');return;}");
        sb.append("  PK.id=id;PK.items=items;PK.sel=s.value;");
        sb.append("  var tt=document.getElementById('pkTitle');if(tt)tt.textContent=PK_TITLE[id]||'请选择';");
        sb.append("  var sw=document.getElementById('pkSearchWrap');if(sw)sw.style.display=items.length>8?'block':'none';");
        sb.append("  var q=document.getElementById('pkSearch');if(q)q.value='';");
        sb.append("  pkRender();");
        sb.append("  document.getElementById('pkMask').classList.add('show');");
        sb.append("  document.getElementById('pkSheet').classList.add('show');");
        sb.append("  var l=document.getElementById('pkList');if(l)l.scrollTop=0;}");
        sb.append("function pkRender(){");
        sb.append("  var box=document.getElementById('pkList');if(!box)return;");
        sb.append("  var q=(document.getElementById('pkSearch').value||'').trim().toLowerCase();");
        sb.append("  var h='',n=0;");
        sb.append("  for(var i=0;i<PK.items.length;i++){var it=PK.items[i];");
        sb.append("    if(q&&(String(it.t)+' '+it.v).toLowerCase().indexOf(q)<0)continue;n++;");
        sb.append("    h+=\"<div class='pk-item\"+(it.v===PK.sel?' on':'')+\"' onclick='pkChoose(\"+i+\")'>\"");
        sb.append("      +\"<span>\"+crEsc(it.t)+\"</span><span class='pk-ck'>✓</span></div>\";}");
        sb.append("  box.innerHTML=n?h:\"<div class='pk-empty'>没有匹配的选项</div>\";}");
        sb.append("function pkChoose(i){var it=PK.items[i];if(!it)return;");
        sb.append("  var id=PK.id,s=document.getElementById(id);");
        sb.append("  pkClose();");
        // 周次跳转不走表单 select：直接落位到所选周
        sb.append("  if(id==='weekPick'){jumpWeek(parseInt(it.v,10)||0);return;}");
        sb.append("  if(!s)return;s.value=it.v;crSync(id);");
        // 选完要按字段类型做后续动作：校区→拉教学楼；教学楼→拉教室（仅查询教室页需要）
        sb.append("  if(id==='crCampus')crPickCampus();");
        sb.append("  else if(id==='crBuilding')crPickBuilding();");
        sb.append("  else if(id==='eqCampus')crPickCampusFor('eqBuilding');}");
        sb.append("function pkClose(){var m=document.getElementById('pkMask'),sh=document.getElementById('pkSheet');");
        sb.append("  if(m)m.classList.remove('show');if(sh)sh.classList.remove('show');");
        // 搜索框保持焦点会把软键盘留到面板关闭之后，必须主动失焦
        sb.append("  var q=document.getElementById('pkSearch');if(q&&q.blur)q.blur();");
        sb.append("  var a=document.activeElement;if(a&&a.blur&&a!==document.body)a.blur();}");
        sb.append("function pkOpen(){var sh=document.getElementById('pkSheet');");
        sb.append("  return !!(sh&&sh.classList.contains('show'));}");
        // ===== 选项填充：两个页面共用教务返回的同一份选项 =====
        sb.append("function crSetOptions(o){");
        sb.append("  var g=function(id){return document.getElementById(id);};");
        sb.append("  var st=g('crStatus'),f=g('crForm'),es=g('eqStatus'),ef=g('eqForm');");
        sb.append("  if(!o||!o.campus||!o.campus.length){");
        sb.append("    var m='读取失败：请先在课表页点一次「刷新课表」完成登录，再回来查询。';");
        sb.append("    if(st)st.textContent=m;if(es)es.textContent=m;return;}");
        // 选项只填一次：两个页面共用同一份教务选项，重复填充会把用户已选的
        // 校区/教学楼/周次全部清回占位符（切页面就丢条件）。
        sb.append("  if(window.__clsOpts){");
        sb.append("    if(st)st.style.display='none';if(f)f.style.display='block';");
        sb.append("    if(es)es.style.display='none';if(ef)ef.style.display='block';return;}");
        sb.append("  window.__clsOpts=1;");
        sb.append("  crFill('crCampus',o.campus,'请选择校区');");
        sb.append("  crFill('crWeek',o.week,'请选择周次');");
        sb.append("  crFill('crWeekday',o.weekday,'请选择星期');");
        sb.append("  crFill('crJc1',o.jc,'起始节次');crFill('crJc2',o.jc,'结束节次');");
        sb.append("  crSetRoom([]);");
        sb.append("  crFill('eqCampus',o.campus,'请选择校区');");
        sb.append("  crFill('eqWeek',o.week,'请选择周次');");
        sb.append("  crFill('eqWeekday',o.weekday,'请选择星期');");
        sb.append("  crFill('eqJc1',o.jc,'起始节次');crFill('eqJc2',o.jc,'结束节次');");
        sb.append("  eqSetBuildings([]);");
        // 默认：当前显示周 / 今天 / 第 1 大节（用户通常只需选校区）
        sb.append("  var pref=[['crWeek','crWeekday','crJc1','crJc2'],['eqWeek','eqWeekday','eqJc1','eqJc2']];");
        sb.append("  for(var pi=0;pi<pref.length;pi++){var ids=pref[pi];");
        sb.append("    var w=g(ids[0]);if(w&&window.CUR_WEEK)w.value=String(CUR_WEEK);");
        sb.append("    var wd=g(ids[1]);if(wd&&window.TODAY_DOW)wd.value=String(TODAY_DOW);");
        sb.append("    var j1=g(ids[2]);if(j1&&j1.options.length>1)j1.value=j1.options[1].value;");
        sb.append("    var j2=g(ids[3]);if(j2&&j2.options.length>1)j2.value=j2.options[1].value;");
        sb.append("    crSync(ids[0]);crSync(ids[1]);crSync(ids[2]);crSync(ids[3]);}");
        sb.append("  if(st)st.style.display='none';if(f)f.style.display='block';");
        sb.append("  if(es)es.style.display='none';if(ef)ef.style.display='block';}");
        sb.append("function crPickCampus(){crPickCampusFor('crBuilding');}");
        // 选校区 → 拉教学楼。targetId 决定填到哪个页面的下拉：
        // 两个功能共用一个隐藏 WebView，不能让结果一律写回查询教室页。
        sb.append("function crPickCampusFor(targetId){");
        sb.append("  var cid=(targetId==='eqBuilding')?'eqCampus':'crCampus';");
        sb.append("  var cv=document.getElementById(cid).value;");
        sb.append("  if(targetId==='crBuilding')crSetRoom([]);");
        sb.append("  var b=document.getElementById(targetId);");
        sb.append("  if(cv){if(b)b.innerHTML=\"<option value=''>读取中…</option>\";crSync(targetId);");
        sb.append("    Android.onClassroomCampus(cv,targetId);}");
        sb.append("  else if(b){b.innerHTML=\"<option value=''>请先选择校区</option>\";crSync(targetId);}}");
        // 教学楼列表：首项是「整校区」（value 为空），与整栋楼查询共用同一套表单
        sb.append("function crSetBuildings(a,targetId){");
        sb.append("  var id=targetId||'crBuilding',s=document.getElementById(id);if(!s)return;");
        sb.append("  var h=\"<option value=''>全部教学楼（整校区）</option>\";");
        sb.append("  for(var i=0;i<(a||[]).length;i++){");
        sb.append("    h+=\"<option value='\"+crEsc(a[i].v)+\"'>\"+crEsc(a[i].t)+\"</option>\";}");
        sb.append("  s.innerHTML=h;crSync(id);}");
        sb.append("function eqSetBuildings(a){crSetBuildings(a,'eqBuilding');}");
        sb.append("function crPickBuilding(){");
        sb.append("  var v=document.getElementById('crBuilding').value;");
        sb.append("  crSetRoom([]);");
        sb.append("  if(v){crRoomLoading();Android.onClassroomRooms(v);}}");
        sb.append("function crRoomLoading(){var s=document.getElementById('crRoom');if(!s)return;");
        sb.append("  s.innerHTML=\"<option value=''>读取中…</option>\";crSync('crRoom');");
        sb.append("  var f=document.getElementById('crRoomF');if(f)f.classList.add('lock');}");
        sb.append("function crSetRoom(a){var s=document.getElementById('crRoom');if(!s)return;");
        sb.append("  var bs=document.getElementById('crBuilding');");
        sb.append("  var scope=(bs&&bs.value)?'全部教室（本教学楼）':'全部教室（整校区）';");
        sb.append("  var h=\"<option value=''>\"+scope+\"</option>\";");
        sb.append("  for(var i=0;i<(a||[]).length;i++){");
        sb.append("    h+=\"<option value='\"+crEsc(a[i].v)+\"'>\"+crEsc(a[i].t)+\"</option>\";}");
        sb.append("  s.innerHTML=h;crSync('crRoom');");
        // 即使这栋楼没列出教室，也要允许打开（面板里还有「全部教室」一项）
        sb.append("  var f=document.getElementById('crRoomF');if(f)f.classList.remove('lock');}");
        sb.append("function crSubmit(){");
        sb.append("  var g=function(id){return document.getElementById(id);};");
        sb.append("  var campus=g('crCampus').value,bld=g('crBuilding').value;");
        sb.append("  var wk=g('crWeek').value,wd=g('crWeekday').value,j1=g('crJc1').value,j2=g('crJc2').value;");
        sb.append("  if(!campus){toast('请选择校区');return;}");
        sb.append("  if(!wk){toast('请选择周次');return;}");
        sb.append("  var ot=function(id){var e=g(id);return e&&e.selectedIndex>=0?e.options[e.selectedIndex].text:'';};");
        sb.append("  var room=g('crRoom')?g('crRoom').value:'',roomName=g('crRoom')?ot('crRoom'):'';");
        sb.append("  window.__crPeriod=ot('crJc1')+'-'+ot('crJc2');");
        sb.append("  window.__crQuery=(ot('crWeek')+' '+ot('crWeekday')+' 节次'+window.__crPeriod)");
        sb.append("    .replace(/\\s+/g,' ').trim();");
        sb.append("  window.__crQuery+=(bld?(' · '+ot('crBuilding')):' · 整校区');");
        sb.append("  if(room)window.__crQuery+=' · '+roomName;");
        sb.append("  var btn=g('crSubmitBtn');if(btn){btn.disabled=true;btn.textContent='查询中…';}");
        sb.append("  var r=g('crResult');if(r)r.innerHTML=\"<div class='cr-status'>正在向教务系统查询…</div>\";");
        sb.append("  Android.onClassroomQuery(campus,bld,wk,wd,j1,j2,room,roomName);}");
        sb.append("function crShowResult(o){");
        sb.append("  var btn=document.getElementById('crSubmitBtn');");
        sb.append("  if(btn){btn.disabled=false;btn.textContent='查询';}");
        sb.append("  var r=document.getElementById('crResult');if(!r)return;");
        sb.append("  if(!o){r.innerHTML=\"<div class='cr-status'>没有查询到结果</div>\";return;}");
        sb.append("  var list=o.list||[];window.__crList=list;");
        sb.append("  if(list.length){");
        sb.append("    var cond=window.__crQuery?crEsc(window.__crQuery)+' · ':'';");
        sb.append("    var h=\"<div class='cr-status'>\"+cond+\"共 \"+list.length+\" 条占用记录（点卡片看详情）</div>\";");
        sb.append("    for(var i=0;i<list.length;i++){var it=list[i];");
        sb.append("      h+=\"<div class='cr-card' onclick='crCardInfo(\"+i+\")'><div class='ch'><b>\"");
        sb.append("        +crEsc(it.room)+\"</b>\";");
        sb.append("      h+=\"<span>\"+crEsc(it.day)+\"</span></div><div class='cb'>\";");
        sb.append("      for(var j=0;j<it.lines.length;j++){");
        sb.append("        h+=(j===0?\"<em>\"+crEsc(it.lines[j])+\"</em>\":crEsc(it.lines[j]));");
        sb.append("        if(j<it.lines.length-1)h+='<br>';}");
        sb.append("      h+=\"</div></div>\";}");
        sb.append("    r.innerHTML=h;return;}");
        sb.append("  if(o.html){");
        sb.append("    r.innerHTML=\"<div class='cr-status'>未识别出卡片，以下为教务原始结果</div>\"");
        sb.append("      +\"<div class='cr-raw'>\"+o.html+\"</div>\";return;}");
        sb.append("  r.innerHTML=\"<div class='cr-status'>\"+crEsc(o.msg||'没有查询到结果')+\"</div>\";}");
        // ===== 卡片详情（两个教室功能共用同一个弹窗）=====
        sb.append("function cdShow(title,rows,actionHtml){");
        sb.append("  var m=document.getElementById('cdModal');if(!m)return;");
        sb.append("  var tt=document.getElementById('cdTitle');if(tt)tt.textContent=title||'详情';");
        sb.append("  var h='';");
        sb.append("  for(var i=0;i<rows.length;i++){var row=rows[i];");
        sb.append("    if(!row||row[1]===undefined||row[1]===null||row[1]==='')continue;");
        sb.append("    h+=\"<div class='cd-row'><span>\"+crEsc(row[0])+\"</span><b>\"+crEsc(row[1])+\"</b></div>\";}");
        sb.append("  var b=document.getElementById('cdBody');if(b)b.innerHTML=h;");
        sb.append("  var a=document.getElementById('cdActions');if(a)a.innerHTML=actionHtml||'';");
        sb.append("  m.classList.add('show');}");
        sb.append("function cdClose(){var m=document.getElementById('cdModal');if(m)m.classList.remove('show');}");
        // 教务的格子文本是「课程 / 教师 / (周次) / 班级」四段，按特征拆开比整段堆着清楚
        sb.append("function crSplit(lines){");
        sb.append("  var name=lines[0]||'',weeks='',rest=[];");
        sb.append("  for(var i=1;i<lines.length;i++){var s=lines[i];");
        sb.append("    if(!weeks&&/\\(\\s*\\d+\\s*(-\\s*\\d+)?\\s*周/.test(s)){weeks=s;continue;}");
        sb.append("    rest.push(s);}");
        sb.append("  var teacher=rest.length>1?rest[0]:'';");
        sb.append("  var classes=rest.length>1?rest.slice(1).join(' '):(rest[0]||'');");
        sb.append("  return {name:name,teacher:teacher,weeks:weeks,classes:classes};}");
        sb.append("function crCardInfo(i){");
        sb.append("  var it=(window.__crList||[])[i];if(!it)return;");
        sb.append("  var p=crSplit(it.lines||[]);");
        sb.append("  cdShow(it.room,[['教室',it.room],['星期',it.day],['节次',window.__crPeriod||''],");
        sb.append("    ['课程',p.name],['教师',p.teacher],['周次',p.weeks],['班级',p.classes]],'');}");
        // ===== 找空教室 =====
        sb.append("function openEmptyRoom(){openPage('pageEmpty');");
        sb.append("  if(window.Android&&Android.onEmptyPageOpened)Android.onEmptyPageOpened();}");
        sb.append("function eqSubmit(){");
        sb.append("  var g=function(id){return document.getElementById(id);};");
        sb.append("  var campus=g('eqCampus').value,bld=g('eqBuilding').value;");
        sb.append("  var wk=g('eqWeek').value,wd=g('eqWeekday').value,j1=g('eqJc1').value,j2=g('eqJc2').value;");
        sb.append("  if(!campus){toast('请选择校区');return;}");
        sb.append("  if(!wk){toast('请选择周次');return;}");
        sb.append("  var ot=function(id){var e=g(id);return e&&e.selectedIndex>=0?e.options[e.selectedIndex].text:'';};");
        sb.append("  window.__eqDay=ot('eqWeekday');");
        sb.append("  window.__eqParams=[campus,bld,wk,wd,j1,j2];");
        sb.append("  window.__eqQuery=(ot('eqWeek')+' '+ot('eqWeekday')+' 节次'+ot('eqJc1')+'-'+ot('eqJc2')");
        sb.append("    +(bld?(' · '+ot('eqBuilding')):' · 整校区')).replace(/\\s+/g,' ').trim();");
        sb.append("  var btn=g('eqSubmitBtn');if(btn){btn.disabled=true;btn.textContent='查询中…';}");
        sb.append("  var r=g('eqResult');if(r)r.innerHTML=\"<div class='cr-status'>正在向教务系统查询…</div>\";");
        sb.append("  Android.onEmptyQuery(campus,bld,wk,wd,j1,j2);}");
        // 房号前缀当楼栋名：会4-213→会4，博学A-203→博学A，绿9-204XX→绿9
        sb.append("function eqPrefix(n){var i=n.indexOf('-');");
        sb.append("  if(i>0&&i<=8)return n.slice(0,i);");
        sb.append("  var m=n.match(/^[\\u4e00-\\u9fa5A-Za-z]{1,4}/);return m?m[0]:n;}");
        sb.append("function eqShowResult(o){");
        sb.append("  var btn=document.getElementById('eqSubmitBtn');");
        sb.append("  if(btn){btn.disabled=false;btn.textContent='查找空教室';}");
        sb.append("  var r=document.getElementById('eqResult');if(!r)return;");
        sb.append("  if(!o){r.innerHTML=\"<div class='cr-status'>没有查询到结果</div>\";return;}");
        sb.append("  var day=window.__eqDay||'',free=(o.free&&o.free[day])||[],rooms=o.rooms||[];");
        sb.append("  if(!rooms.length){");
        sb.append("    if(o.html){r.innerHTML=\"<div class='cr-status'>未识别出教室，以下为教务原始结果</div>\"");
        sb.append("      +\"<div class='cr-raw'>\"+o.html+\"</div>\";return;}");
        sb.append("    r.innerHTML=\"<div class='cr-status'>\"+crEsc(o.msg||'没有取到教室清单')+\"</div>\";return;}");
        sb.append("  window.__eqFree=free;");
        sb.append("  var cond=window.__eqQuery?crEsc(window.__eqQuery)+' · ':'';");
        sb.append("  var h=\"<div class='cr-status'>\"+cond+\"空闲 \"+free.length+\" / 共 \"+rooms.length");
        sb.append("    +\" 间教室（点教室看详情）</div>\";");
        sb.append("  if(!free.length){");
        sb.append("    h+=\"<div class='cr-status'>该时段没有空教室，换个节次或周次试试。</div>\";");
        sb.append("    r.innerHTML=h;return;}");
        sb.append("  var order=[],grp={};");
        sb.append("  for(var i=0;i<free.length;i++){var pf=eqPrefix(free[i]);");
        sb.append("    if(!grp[pf]){grp[pf]=[];order.push(pf);}grp[pf].push({n:free[i],i:i});}");
        sb.append("  order.sort();");
        sb.append("  for(var k=0;k<order.length;k++){");
        sb.append("    var p=order[k],arr=grp[p];");
        sb.append("    h+=\"<div class='eq-group'><div class='eq-gh'><b>\"+crEsc(p)+\"</b><i>\"+arr.length+\" 间</i></div>\";");
        sb.append("    h+=\"<div class='eq-rooms'>\";");
        sb.append("    for(var m=0;m<arr.length;m++){");
        sb.append("      h+=\"<div class='eq-room' onclick='eqRoomInfo(\"+arr[m].i+\")'>\"+crEsc(arr[m].n)+\"</div>\";}");
        sb.append("    h+=\"</div></div>\";}");
        sb.append("  r.innerHTML=h;}");
        sb.append("function eqRoomInfo(i){");
        sb.append("  var free=window.__eqFree||[],name=free[i];if(!name)return;");
        sb.append("  cdShow(name,[['教室',name],['状态','该时段空闲'],['时段',window.__eqQuery||'']],");
        sb.append("    \"<button class='cd-act' onclick='eqToRoom(\"+i+\")'>看这间教室的课表</button>\");}");
        // 跳到查询教室并预填同一条件：从空教室点进去就该直接看到这间的课表
        sb.append("function eqToRoom(i){");
        sb.append("  var free=window.__eqFree||[],name=free[i];cdClose();if(!name)return;");
        sb.append("  var p=window.__eqParams||[];");
        sb.append("  window.__crWantRoom=name;");
        sb.append("  gotoPage('pageClassroom');");
        sb.append("  if(window.Android&&Android.onClassroomQueryOpened)Android.onClassroomQueryOpened();");
        sb.append("  crPreselect(name,p);}");
        // 轮询等待：教务处要先把校区→教学楼→教室三级联动跑完，才能选中具体教室
        sb.append("function crPreselect(room,p){");
        sb.append("  if(!p||p.length<6){return;}");
        sb.append("  var tries=0;");
        sb.append("  var t=setInterval(function(){");
        sb.append("    tries++;if(tries>50){clearInterval(t);window.__crWantRoom='';return;}");
        sb.append("    var cs=document.getElementById('crCampus');if(!cs||cs.options.length<2)return;");
        sb.append("    if(cs.value!==p[0]){cs.value=p[0];crSync('crCampus');crPickCampus();return;}");
        sb.append("    if(!p[1]){clearInterval(t);window.__crWantRoom='';");
        sb.append("      toast('已定位到校区，选择教学楼后即可选到该教室');return;}");
        sb.append("    var bs=document.getElementById('crBuilding');if(!bs||bs.options.length<2)return;");
        sb.append("    if(bs.value!==p[1]){bs.value=p[1];crSync('crBuilding');crPickBuilding();return;}");
        sb.append("    var w=document.getElementById('crWeek');if(w&&p[2])w.value=p[2];");
        sb.append("    var wd=document.getElementById('crWeekday');if(wd&&p[3])wd.value=p[3];");
        sb.append("    var j1=document.getElementById('crJc1');if(j1&&p[4])j1.value=p[4];");
        sb.append("    var j2=document.getElementById('crJc2');if(j2&&p[5])j2.value=p[5];");
        sb.append("    crSync('crWeek');crSync('crWeekday');crSync('crJc1');crSync('crJc2');");
        sb.append("    var rs=document.getElementById('crRoom');if(!rs)return;");
        sb.append("    var val=null;");
        sb.append("    for(var k=0;k<rs.options.length;k++){");
        sb.append("      if((rs.options[k].text||'').trim()===room){val=rs.options[k].value;break;}}");
        sb.append("    if(val===null)return;");
        sb.append("    clearInterval(t);window.__crWantRoom='';");
        sb.append("    rs.value=val;crSync('crRoom');},300);}");
        // 我的页资料卡实时回填（Java 侧读到学生信息后调用）
        sb.append("function setMeInfo(n,sid){");
        sb.append("  var a=document.getElementById('meAvatar');");
        sb.append("  var b=document.getElementById('meName');");
        sb.append("  var c=document.getElementById('meId');");
        sb.append("  if(n){if(b)b.textContent=n;if(a)a.textContent=n.substring(0,1);}");
        sb.append("  if(sid){if(c)c.textContent='学号 '+sid;}}");
        // 系统返回键统一入口：优先关设置页，再关课程详情弹窗，返回'1'表示已消费
        sb.append("function handleBack(){");
        sb.append("  if(typeof pkOpen==='function'&&pkOpen()){pkClose();return '1';}");
        // 详情弹窗要优先于页面关闭，否则按返回会直接退出当前页
        sb.append("  var cd=document.getElementById('cdModal');");
        sb.append("  if(cd&&cd.classList.contains('show')){cdClose();return '1';}");
        sb.append("  if(typeof pageStack!=='undefined'&&pageStack.length){closePage();return '1';}");
        sb.append("  var cm=document.getElementById('courseModal');");
        sb.append("  if(cm&&cm.classList.contains('show')){closeModal();return '1';}");
        sb.append("  return '0';}");
        sb.append("function openSub(name){");
        sb.append("  var map={account:'pageAccount',general:'pageGeneral',custom:'pageCustom',");
        sb.append("update:'pageUpdate',about:'pageAbout'};");
        sb.append("  if(map[name])openPage(map[name]);");
        // 检查更新页：回填内存里已有的更新信息（切周后 JS 状态会丢）
        sb.append("  if(name==='update'&&window.Android&&Android.onUpdatePageOpened)Android.onUpdatePageOpened();}");
        sb.append("function showSettings(){openPage('pageSettings');}");
        sb.append("function copyTxt(t){if(window.Android&&Android.onCopy)Android.onCopy(t);}");
        // ===== 检查更新页 =====
        // 公告/更新日志里的【前缀】着色（任务书第五章：前缀可加粗或着色）
        sb.append("function upEsc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}");
        sb.append("function upSize(n){");
        sb.append("  if(n>=1048576)return (n/1048576).toFixed(1)+' MB';");
        sb.append("  if(n>=1024)return (n/1024).toFixed(0)+' KB';");
        sb.append("  return n+' B';}");
        sb.append("function upTagged(txt){");
        sb.append("  var lines=String(txt||'').split('\\n');");
        sb.append("  var out='';");
        sb.append("  for(var i=0;i<lines.length;i++){");
        sb.append("    var m=lines[i].match(/^(\\[[^\\]\\n]{1,12}\\])([\\s\\S]*)$/);");
        sb.append("    if(m){out+='<b class=\"up-tag\">'+m[1]+'</b>'+upEsc(m[2]);}");
        sb.append("    else{out+=upEsc(lines[i]);}");
        sb.append("    if(i<lines.length-1)out+='<br>';}");
        sb.append("  return out;}");
        sb.append("function renderUpdateInfo(o){");
        sb.append("  var latest=document.getElementById('upLatest');");
        sb.append("  var meta=document.getElementById('upMeta');");
        sb.append("  var log=document.getElementById('upLog');");
        sb.append("  var box=document.getElementById('upContentBox');");
        sb.append("  var cbox=document.getElementById('upContent');");
        sb.append("  var dl=document.getElementById('upDlBtn');");
        sb.append("  if(!o)return;");
        // 兼容两种注入形式：对象字面量（现用）与 JSON 字符串（旧实现用 quote 包了一层）
        sb.append("  if(typeof o==='string'){try{o=JSON.parse(o);}catch(e){return;}}");
        sb.append("  window.__upVer=o.latest_version||'';");
        sb.append("  if(latest)latest.textContent='v'+(o.latest_version||'—');");
        // 版本元信息：发布时间 + 包大小
        sb.append("  if(meta){");
        sb.append("    var bits=[];");
        sb.append("    if(o.released_at)bits.push('发布于 '+o.released_at);");
        sb.append("    if(o.size&&o.size>0)bits.push('安装包 '+upSize(o.size));");
        sb.append("    if(bits.length){meta.textContent=bits.join(' · ');meta.style.display='block';}");
        sb.append("    else{meta.style.display='none';}}");
        // 更新日志：检查到新版本后必须展示
        sb.append("  if(log){");
        sb.append("    log.className=(o.changelog&&o.changelog.length)?'up-log':'up-empty';");
        sb.append("    log.innerHTML=(o.changelog&&o.changelog.length)?upTagged(o.changelog)");
        sb.append("      :'（服务端未提供更新内容）';}");
        sb.append("  if(cbox)cbox.innerHTML=upTagged(o.content||'');");
        sb.append("  if(box)box.style.display=(o.content&&o.content.length)?'block':'none';");
        sb.append("  if(dl){");
        sb.append("    dl.style.display=o.update_available?'inline-block':'none';");
        sb.append("    dl.textContent=o.update_available?('立即更新到 v'+(o.latest_version||'')):'立即更新';}}");
        // 只更新公告区（内容接口刷新用，不影响更新信息）
        sb.append("function renderUpdateContent(c){");
        sb.append("  var box=document.getElementById('upContentBox');");
        sb.append("  var cbox=document.getElementById('upContent');");
        sb.append("  if(!box||!cbox)return;");
        sb.append("  cbox.innerHTML=upTagged(c||'');");
        sb.append("  box.style.display=(c&&c.length)?'block':'none';}");
        sb.append("function setUpdateLoading(on){");
        sb.append("  var b=document.getElementById('upBtn');");
        sb.append("  if(!b)return;b.disabled=on;b.textContent=on?'检查中…':'检查更新';}");
        sb.append("function setUpdateDownloading(on){");
        sb.append("  var b=document.getElementById('upDlBtn');");
        sb.append("  if(b){b.disabled=on;");
        sb.append("    b.textContent=on?'下载中…':(window.__upVer?('立即更新到 v'+window.__upVer):'立即更新');}");
        sb.append("  var p=document.getElementById('upProgBox');");
        sb.append("  var cb=document.getElementById('upCancelBtn');");
        sb.append("  if(p)p.style.display=on?'block':'none';");
        sb.append("  if(cb)cb.style.display=on?'block':'none';");
        sb.append("  if(!on)setUpdateProgress(0);}");
        sb.append("function setUpdateProgress(p){");
        sb.append("  var bar=document.getElementById('upBar');");
        sb.append("  if(bar)bar.style.width=p+'%';");
        sb.append("  var t=document.getElementById('upProgTxt');");
        sb.append("  if(t)t.textContent=p+'%';}");
        // 主题切换（带背景过渡动画）
        sb.append("function setTheme(m){");
        sb.append("  document.body.classList.toggle('dark',m==='dark');");
        sb.append("  document.body.classList.toggle('light',m!=='dark');");
        sb.append("  updateThemeBtn(m);applyGlass();");
        sb.append("  Android.onSetTheme(m);}");
        sb.append("function updateThemeBtn(m){");
        sb.append("  var l=document.getElementById('themeLight'),d=document.getElementById('themeDark');");
        sb.append("  if(l){l.style.background=m==='dark'?'':'#667eea';l.style.color=m==='dark'?'':'#fff';}");
        sb.append("  if(d){d.style.background=m==='dark'?'#667eea':'';d.style.color=m==='dark'?'#fff':'';}}");
        // ===== 玻璃卡片（只跟主题走，没有开关）=====
        // 切主题时必须就地换类，否则要等下一次重渲染才有玻璃卡片。
        // 判定口径与 Java 生成 body 时一致：只有深色主题挂 .glass。
        sb.append("function applyGlass(){");
        sb.append("  var b=document.body;");
        sb.append("  b.classList.toggle('glass',b.classList.contains('dark'));}");
        // 开屏动画：单个开关（写入后下次启动生效）
        sb.append("var splashOn=").append(splashAnimOn ? "true" : "false").append(";");
        sb.append("function toggleSplash(){");
        sb.append("  splashOn=!splashOn;");
        sb.append("  var s=document.getElementById('splashSwitch');");
        sb.append("  if(s)s.classList.toggle('on',splashOn);");
        sb.append("  Android.onSetSplashAnim(splashOn?1:0);}");
        // 通知栏常驻：真实状态由 Java 决定（可能因为没给通知权限而回滚），
        // 所以这里先乐观翻转，收到 setNotifySwitch 回调再以 Java 的结果为准。
        sb.append("var notifyOn=").append(notifyOn ? "true" : "false").append(";");
        sb.append("function toggleNotify(){");
        sb.append("  notifyOn=!notifyOn;");
        sb.append("  var s=document.getElementById('notifySwitch');");
        sb.append("  if(s)s.classList.toggle('on',notifyOn);");
        sb.append("  Android.onSetPersistentNotify(notifyOn?1:0);}");
        sb.append("window.setNotifySwitch=function(on){");
        sb.append("  notifyOn=!!on;");
        sb.append("  var s=document.getElementById('notifySwitch');");
        sb.append("  if(s)s.classList.toggle('on',notifyOn);};");
        // ===== 字体大小 =====
        // 整页字号都是 calc(<原字号> * var(--fs))，所以改 :root 上的 --fs 就能立刻缩放，
        // 不需要重载页面（重载会把用户从设置页弹回课表）。
        // --fsg 是课表网格内的温和系数：格子高度固定，字放太大地点会被裁掉。
        sb.append("var FONT_PCT=").append(fsPct).append(";");
        sb.append("function setFontCss(p){");
        sb.append("  var r=document.documentElement.style;");
        sb.append("  r.setProperty('--fs',(p/100).toFixed(2));");
        sb.append("  r.setProperty('--fsg',(1+(p/100-1)*0.45).toFixed(2));}");
        // 拖动滑块：只在停顿后落盘，避免一次拖动写几十次配置
        sb.append("function onFontSlide(v){");
        sb.append("  var p=parseInt(v,10)||100;");
        sb.append("  FONT_PCT=p;setFontCss(p);fontUi(p);");
        sb.append("  clearTimeout(window.__fsTimer);");
        sb.append("  window.__fsTimer=setTimeout(function(){Android.onSetFontScale(p);},180);}");
        // 档位按钮：直接落盘并同步滑块
        sb.append("function setFontScale(p){");
        sb.append("  p=Math.max(80,Math.min(150,p));");
        sb.append("  FONT_PCT=p;setFontCss(p);fontUi(p);");
        sb.append("  var r=document.getElementById('fsRange');if(r)r.value=p;");
        sb.append("  Android.onSetFontScale(p);}");
        // 同步数值文字、滑块位置与档位高亮（只有恰好等于档位值时那个按钮才点亮）
        sb.append("function fontUi(p){");
        sb.append("  var v=document.getElementById('fsVal');if(v)v.textContent=p+'%';");
        sb.append("  var presets={80:'fsP80',100:'fsP100',120:'fsP120',140:'fsP140'};");
        sb.append("  for(var k in presets){var b=document.getElementById(presets[k]);if(!b)continue;");
        sb.append("    var on=(parseInt(k,10)===p);");
        sb.append("    b.style.background=on?'#667eea':'';b.style.color=on?'#fff':'';");
        sb.append("    b.style.borderColor=on?'#667eea':'';}}");
        sb.append("fontUi(FONT_PCT);");
        // 冲突优先显示
        sb.append("function setConflictPriority(v){");
        sb.append("  updateConflictBtn(v);");
        sb.append("  Android.onSetConflictPriority(v);}");
        sb.append("function updateConflictBtn(v){");
        sb.append("  var s=document.getElementById('cpSchool'),c=document.getElementById('cpCustom');");
        sb.append("  if(s){s.style.background=v==='school'?'#667eea':'';s.style.color=v==='school'?'#fff':'';}");
        sb.append("  if(c){c.style.background=v==='custom'?'#667eea':'';c.style.color=v==='custom'?'#fff':'';}}");
        // 自定义图片背景
        sb.append("function applyBg(url){");
        sb.append("  document.body.classList.toggle('hasbg',!!url);");
        sb.append("  var b=document.getElementById('clearBgBtn');");
        sb.append("  if(b)b.style.display=url?'inline-block':'none';}");
        sb.append("updateThemeBtn('").append("dark".equals(theme) ? "dark" : "light").append("');");
        sb.append("updateConflictBtn('").append(cp).append("');");
        sb.append("buildColorPicker();");
        sb.append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 单个课程的详情对象 */
    private JSONObject buildCourseDetailObj(JSONObject c, int week, String periodLabel,
                                           String periodTime, String color, boolean clash) {
        try {
            JSONObject d = new JSONObject();
            String id = c.optString("id", "");
            d.put("id", id);
            // src 决定详情弹窗里是否给出「编辑 / 删除」入口
            d.put("src", id.isEmpty() ? "school" : "custom");
            d.put("clash", clash);
            d.put("name", c.optString("name", ""));
            d.put("teacher", c.optString("teacher", ""));
            d.put("location", c.optString("location", ""));
            d.put("weeks", c.optString("weeks", ""));
            d.put("period", periodLabel);
            d.put("time", periodTime);
            d.put("week", "第" + week + "周");
            int day = c.optInt("day", 0);
            d.put("weekday", day >= 1 && day <= 7 ? WEEKDAY_CN[day] : "");
            d.put("count", c.optString("count", ""));
            d.put("cls", c.optString("cls", ""));
            d.put("remark", c.optString("remark", ""));
            d.put("color", color == null ? "" : color);
            return d;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 该格所有课程的详情数组（同一时段可能重叠多门课） */
    private String buildCourseDetailArray(JSONArray arr, int week, String periodLabel,
                                          String periodTime, String color, boolean clash) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            // 冲突提示只在第一门课里出现一次，避免同一格重复刷 3 条同样的提示
            out.put(buildCourseDetailObj(c, week, periodLabel, periodTime, color, clash && i == 0));
        }
        return out.toString().replace("'", "\\'");
    }

    /** 是否为本校受信任地址（*.shzu.edu.cn） */
    private static boolean isTrustedUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.trim().toLowerCase();
        if (u.startsWith("javascript:") || u.startsWith("about:") || u.startsWith("data:")) return true;
        String host;
        try {
            host = android.net.Uri.parse(u).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals("shzu.edu.cn") || host.endsWith(".shzu.edu.cn");
    }

    /** 日志里隐去 query 参数，避免敏感信息进日志 */
    private static String maskUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) + "?…" : url;
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ====== UI 辅助 ======

    private void showLoading(String text) {
        loadingText.setText(text);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void showError() {
        hideLoading();
        errorOverlay.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }

    // ====== JavaScript 接口 ======

    private class JsInterface {

        @JavascriptInterface
        public void onLoginSubmitted(String user, String pass) {
            // 先存内存，登录成功到达教务系统后才落盘
            pendingUser = user;
            pendingPass = pass;
            Log.d(TAG, "login form filled");
        }

        @JavascriptInterface
        public void onScheduleParsed(String json) {
            handler.post(() -> displaySchedule(json));
        }

        /** 打开「我的」页：用本地最新资料刷新姓名/学号卡片 */
        @JavascriptInterface
        public void onMineTabOpened() {
            handler.post(() -> {
                if (store == null) return;
                String n = store.getStudentName();
                String sid = store.getStudentId();
                // 归属不对的一律不显示（换号 / 调试数据）
                if (!savedUser.isEmpty() && !sid.isEmpty() && !sid.equals(savedUser)) {
                    n = "";
                    sid = "";
                }
                evalJs("setMeInfo(" + jsStr(n) + "," + jsStr(sid) + ")");
            });
        }

        /** 「重新获取资料」：强制去教务首页再抓一次姓名/学号 */
        @JavascriptInterface
        public void onRefreshStudentInfo() {
            handler.post(() -> {
                studentInfoManual = true;
                ensureStudentInfo(true);
            });
        }

        /**
         * 有全屏页（我的/设置等）盖住课表时由 JS 通知：covered=1 收起顶部刘海渐变。
         * 页面自身已是浅色玻璃底（0.76 白 + blur），再叠一层 scrim 会比课表页深一截，
         * 表现为「切页面后刘海区域颜色不统一」。盖住时藏 scrim、露出时淡回来。
         */
        @JavascriptInterface
        public void onPageCoverChanged(int covered) {
            handler.post(() -> {
                float a = covered == 1 ? 0f : 1f;
                if (topScrim != null) {
                    topScrim.animate().alpha(a).setDuration(180).start();
                }
                // 页面盖住课表时遮罩淡入（盖住手势条后面的照片），露出时淡出
                if (bottomCover != null) {
                    bottomCover.animate().alpha(covered == 1 ? 1f : 0f).setDuration(180).start();
                }
            });
        }

        /** 教务页里读到的姓名/学号：只写本机，不上传任何服务器 */
        @JavascriptInterface
        public void onStudentInfo(String json) {
            handler.post(() -> {
                try {
                    JSONObject o = new JSONObject(json == null ? "{}" : json);
                    String name = o.optString("name", "").trim();
                    String sid = o.optString("sid", "").trim();
                    if (name.isEmpty() && sid.isEmpty()) {
                        Log.d(TAG, "student info: not found on this page");
                        return;
                    }
                    if (store == null) store = new ScheduleStore(MainActivity.this);
                    // owner 记成当前登录账号：换号后旧资料会自动失效，不再被展示
                    store.setStudentInfo(name, sid, savedUser);
                    // 我的页面可能正开着，实时刷新卡片
                    evalJs("setMeInfo(" + jsStr(name) + "," + jsStr(sid) + ")");
                    Log.d(TAG, "student info: name=" + (name.isEmpty() ? "-" : "ok")
                            + " sid=" + (sid.isEmpty() ? "-" : "ok"));
                    releaseStudentWv();
                    if (studentInfoManual) {
                        studentInfoManual = false;
                        Toast.makeText(MainActivity.this,
                                name.isEmpty() ? "没抓到姓名，退出账号重新登录一次试试"
                                        : "资料已更新",
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "onStudentInfo failed", e);
                }
            });
        }

        @JavascriptInterface
        public void onScheduleLinkFound(String url) {
            final String link = url == null ? "" : url;
            handler.post(() -> {
                if ("TRANSITION".equals(link)) {
                    // 过渡跳板页，等重定向，由新页面重新驱动流程
                    Log.d(TAG, "link scan: transition page, waiting");
                    return;
                }
                if (!link.isEmpty()) {
                    Log.d(TAG, "schedule link found: " + link);
                    onSchedulePage = true;
                    cameFromLink = true;
                    parseRetries = 0;
                    emptyScans = 0;
                    navToken++;
                    showLoading(getString(R.string.loading));
                    loadJwgl(link);
                    return;
                }
                // 空结果：复扫一次，两次都空 → 转入URL猜测
                if (emptyScans == 0) {
                    emptyScans = 1;
                    Log.d(TAG, "link scan: empty, rescanning in 1.6s");
                    final int token = navToken;
                    handler.postDelayed(() -> {
                        if (token == navToken && !onSchedulePage) findScheduleLink();
                    }, 1600);
                } else {
                    Log.d(TAG, "link scan: empty twice, starting guesses");
                    startGuessing();
                }
            });
        }

        @JavascriptInterface
        public void onWeekChanged(int week) {
            handler.post(() -> renderWeekSchedule(week));
        }

        /**
         * 前端要一块相邻周的网格（横条左右两侧的面板）。
         *
         * 只回传网格片段而不是整页，页面不重载 —— 底部标签栏、标题、按钮都不会闪。
         * 片段来自缓存整页里的切取，第一次访问某周时才真正拼一次。
         */
        @JavascriptInterface
        public void onRequestPanel(int week) {
            handler.post(() -> {
                try {
                    if (store == null) store = new ScheduleStore(MainActivity.this);
                    String html = gridFragment(week);
                    if (html.isEmpty()) return;
                    String js = "window.__applyPanel(" + week + "," + jsStr(html) + ")";
                    webView.evaluateJavascript(js, null);
                } catch (Exception e) {
                    Log.e(TAG, "request panel error", e);
                }
            });
        }

        /**
         * 横条已经停到某一周：记下当前周并回填标题区文字。
         *
         * 这里**不重渲染**：整页重载会把刚做完的手势动画全部抹掉，也会让标签栏闪一下。
         */
        @JavascriptInterface
        public void onWeekSettled(int week) {
            handler.post(() -> {
                try {
                    if (store == null) store = new ScheduleStore(MainActivity.this);
                    int total = store.getTotalWeeks();
                    int w = week;
                    if (w < 1) w = 1;
                    if (total > 0 && w > total) w = total;
                    currentDisplayWeek = w;
                    Log.d(TAG, "week settled: " + w);
                    webView.evaluateJavascript(
                            "window.applyWeekHeader(" + weekMetaJson(w) + ")", null);
                } catch (Exception e) {
                    Log.e(TAG, "week settled error", e);
                }
            });
        }

        @JavascriptInterface
        public void onRefreshRequested() {
            handler.post(() -> {
                Log.d(TAG, "manual refresh requested");
                onSchedulePage = false;
                cameFromLink = false;
                guessIdx = 0;
                parseRetries = 0;
                emptyScans = 0;
                showingDisplay = false;
                navToken++;
                parseResultReceived = false;
                if (savedUser.isEmpty() || savedPass.isEmpty()) {
                    loadLoginPage();
                } else {
                    showLoading(getString(R.string.fetching_schedule));
                    loadJwgl(JWGL_BASE);
                }
            });
        }

        @JavascriptInterface
        public void onCourseClick(String detailJson) {
            handler.post(() -> showCourseDetail(detailJson));
        }

        @JavascriptInterface
        public void onApplyMoves(String movesJson, final int week) {
            handler.post(() -> {
                try {
                    JSONArray arr = new JSONArray(movesJson);
                    if (store == null) store = new ScheduleStore(MainActivity.this);
                    store.saveOverrides(arr);
                    Log.d(TAG, "overrides applied: " + arr.length() + " courses, re-render week " + week);
                    renderWeekSchedule(week);
                    // 位置变了，小组件的"下一节课"可能也变了
                    NextCourseWidgetProvider.refreshAll(MainActivity.this);
                } catch (Exception e) {
                    Log.e(TAG, "applyMoves error", e);
                }
            });
        }

        @JavascriptInterface
        public void onCancelMoves(final int week) {
            handler.post(() -> {
                Log.d(TAG, "moves cancelled, re-render week " + week);
                renderWeekSchedule(week);
            });
        }

        @JavascriptInterface
        public void onAddWidget() {
            handler.post(() -> requestAddWidget());
        }

        // ====== 查询教室（页面 → 隐藏 WebView 驱动）======

        @JavascriptInterface
        public void onClassroomQueryOpened() {
            handler.post(MainActivity.this::classroomOpenOptions);
        }

        @JavascriptInterface
        public void onClassroomCampus(String campusId, String target) {
            final String t = (target == null || target.isEmpty()) ? "crBuilding" : target;
            handler.post(() -> classroomSendJs(clsPickCampusJs(campusId, t)));
        }

        @JavascriptInterface
        public void onClassroomRooms(String building) {
            handler.post(() -> classroomSendJs(clsRoomsJs(building)));
        }

        @JavascriptInterface
        public void onClassroomQuery(String campus, String building, String week,
                                     String weekday, String jc1, String jc2,
                                     String roomId, String roomName) {
            handler.post(() -> {
                classroomMode = "room";
                classroomSeq++;
                classroomSendJs(clsQueryJs(campus, building, week, weekday, jc1, jc2,
                        roomId, roomName, classroomSeq));
            });
        }

        /** 找空教室：同一次查询通道，只是不限定教室，由页面取 free 列表 */
        @JavascriptInterface
        public void onEmptyQuery(String campus, String building, String week,
                                 String weekday, String jc1, String jc2) {
            handler.post(() -> {
                classroomMode = "empty";
                classroomSeq++;
                classroomSendJs(clsQueryJs(campus, building, week, weekday, jc1, jc2,
                        "", "", classroomSeq));
            });
        }

        @JavascriptInterface
        public void onEmptyPageOpened() {
            handler.post(MainActivity.this::classroomOpenOptions);
        }

        @JavascriptInterface
        public void onResetLayout() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (store == null) store = new ScheduleStore(MainActivity.this);
                        store.clearOverrides();
                        renderWeekSchedule(currentDisplayWeek);
                        Toast.makeText(MainActivity.this,
                            "已重置课程布局，恢复默认位置", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "course layout overrides cleared");
                    } catch (Exception e) {
                        Log.e(TAG, "reset layout error", e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onSetTheme(String mode) {
            if (store == null) store = new ScheduleStore(MainActivity.this);
            store.setTheme(mode);
            applyAppBackground();
            Log.d(TAG, "theme set to " + mode);
        }

        /** 开屏动画开关：只写配置，本次启动已在播放/已跳过，下次启动生效 */
        @JavascriptInterface
        public void onSetSplashAnim(int on) {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                final boolean enabled = on == 1;
                store.setSplashAnimEnabled(enabled);
                Log.d(TAG, "splash animation " + (enabled ? "enabled" : "disabled"));
                Toast.makeText(MainActivity.this,
                    enabled ? "开屏动画已开启，下次启动生效" : "开屏动画已关闭，下次启动直接进入课表",
                    Toast.LENGTH_SHORT).show();
            });
        }

        /** 通知栏常驻开关：可能要临时申请通知权限，所以不能直接落盘 */
        @JavascriptInterface
        public void onSetPersistentNotify(int on) {
            handler.post(() -> applyPersistentNotify(on == 1, true));
        }

        /** 位置冲突时优先显示哪一路课程 */
        @JavascriptInterface
        public void onSetConflictPriority(String mode) {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                store.setConflictPriority(mode);
                Log.d(TAG, "conflict priority = " + store.getConflictPriority());
                renderWeekSchedule(currentDisplayWeek);
            });
        }

        /**
         * 界面字体缩放（80~150）。
         *
         * 前端已经就地改好了 --fs，这里**不再重渲染**：重载整页会把用户从设置页
         * 弹回课表，调档时体验很跳。只负责落盘 + 让缓存失效，下次切周/重开自然带上。
         * 但内存里的旧缓存是用旧字号拼的，必须清掉，否则切周回来又变回原大小。
         */
        @JavascriptInterface
        public void onSetFontScale(int percent) {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                if (store.getFontScale() == percent) return;
                store.setFontScale(percent);
                weekHtmlCache.clear();
                weekHtmlStateSig = "";
                Log.d(TAG, "font scale = " + store.getFontScale() + "%");
            });
        }

        /** 加课模式选中了格子（空格或已有课的格子）：打开表单并预填位置 */
        @JavascriptInterface
        public void onPickCell(int day, int row) {
            handler.post(() -> {
                try {
                    int d = Math.max(1, Math.min(7, day));
                    int r = Math.max(1, Math.min(MAX_RENDER_ROWS, row));
                    JSONObject blank = new JSONObject();
                    blank.put("day", d);
                    blank.put("row", r);
                    // 该格已有几门课：>0 时表单里提示「这是叠加加课」
                    blank.put("occupied", countOccupied(currentDisplayWeek, d, r, null));
                    showCustomForm(blank);
                } catch (Exception e) {
                    Log.e(TAG, "pick cell error", e);
                }
            });
        }

        /** 设置页里的「添加自定义课程」：没有预选位置，默认周一第 1 大节 */
        @JavascriptInterface
        public void onAddCustomFromSettings() {
            handler.post(() -> {
                try {
                    JSONObject blank = new JSONObject();
                    blank.put("day", 1);
                    blank.put("row", 1);
                    blank.put("occupied", countOccupied(currentDisplayWeek, 1, 1, null));
                    showCustomForm(blank);
                } catch (Exception e) {
                    Log.e(TAG, "add custom error", e);
                }
            });
        }

        // ====== 自动更新 ======

        /** 检查更新页：手动向服务端查询最新版本与更新内容 */
        @JavascriptInterface
        public void onCheckUpdate() {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                checkUpdate(UPD_MANUAL);
            });
        }

        /** 立即更新：下载 + SHA-256 校验 + 唤起系统安装器 */
        @JavascriptInterface
        public void onDownloadUpdate() {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                UpdateManager.Info info = lastUpdateInfo;
                if (info == null) {
                    // 页面刚打开还没检查过：先查一次，查到就直接续上下载
                    pendingAutoDownload = true;
                    Toast.makeText(MainActivity.this, "正在获取更新信息…", Toast.LENGTH_SHORT).show();
                    checkUpdate(UPD_MANUAL);
                    return;
                }
                if (!info.updateAvailable) {
                    Toast.makeText(MainActivity.this, "当前已是最新版本", Toast.LENGTH_SHORT).show();
                    return;
                }
                startDownloadUpdate(info);
            });
        }

        /** 取消正在进行的下载 */
        @JavascriptInterface
        public void onCancelDownload() {
            handler.post(MainActivity.this::cancelDownloadUpdate);
        }

        /**
         * 「检查更新」页被打开：先把内存里已有的信息回填（切周重渲染会丢 JS 状态），
         * 再自动向服务端检查一次——每次进入这个页面都查，保证看到的是最新状态。
         */
        @JavascriptInterface
        public void onUpdatePageOpened() {
            handler.post(() -> {
                if (lastUpdateInfo != null) {
                    evalJs("renderUpdateInfo(" + updateInfoJson(lastUpdateInfo) + ")");
                }
                checkUpdate(UPD_PAGE);
            });
        }

        /** 只刷新服务端公共内容（公告/维护通知），不查版本 */
        @JavascriptInterface
        public void onRefreshContent() {
            handler.post(() -> {
                if (updateManager == null) return;
                Toast.makeText(MainActivity.this, "正在获取公告…", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    final String content = updateManager.fetchContent();
                    handler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (store != null) store.setUpdateLastContent(content);
                        evalJs("renderUpdateContent(" + jsString(content) + ")");
                    });
                }, "update-content").start();
            });
        }

        /** 编辑已存在的自定义课程 */
        @JavascriptInterface
        public void onEditCustom(String id) {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                JSONObject c = store.getCustomCourse(id);
                if (c == null) { Toast.makeText(MainActivity.this, "课程不存在", Toast.LENGTH_SHORT).show(); return; }
                // 编辑时同样提示叠加情况，但要把自己排除在外
                try {
                    c.put("occupied", countOccupied(currentDisplayWeek,
                            c.optInt("day", 0), c.optInt("row", 0), id));
                } catch (Exception ignored) {
                }
                showCustomForm(c);
            });
        }

        /** 保存自定义课程（新增或编辑） */
        @JavascriptInterface
        public void onSaveCustom(String json) {
            handler.post(() -> {
                try {
                    if (store == null) store = new ScheduleStore(MainActivity.this);
                    JSONObject input = new JSONObject(json == null ? "{}" : json);
                    boolean isNew = input.optString("id", "").isEmpty();
                    // 按「保存时该格是否已有其它课」重算叠加标记：
                    // 用户可能在表单里改了星期/节次，不能沿用点格子那一刻的判断。
                    // 这一标记只决定描边颜色（琥珀=主动叠加 / 红=同步撞车），不拦截保存。
                    int sd = input.optInt("day", 0), sr = input.optInt("row", 0);
                    String sid = input.optString("id", "");
                    if (sd >= 1 && sd <= 7 && sr >= 1 && sr <= MAX_RENDER_ROWS) {
                        input.put("overlap", countOccupied(currentDisplayWeek, sd, sr,
                                sid.isEmpty() ? null : sid) > 0);
                    }
                    JSONObject saved = store.saveCustomCourse(input);
                    if (saved == null) {
                        Toast.makeText(MainActivity.this, "保存失败：请检查课程名称与位置",
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 表单里刚选的位置必须生效：清掉这门课历史拖动留下的覆盖
                    store.removeOverride(ScheduleStore.courseKey(saved));
                    Log.d(TAG, "custom course " + (isNew ? "added" : "updated") + ": "
                        + saved.optString("name", ""));
                    NextCourseWidgetProvider.refreshAll(MainActivity.this);
                    renderWeekSchedule(currentDisplayWeek);
                    String base = isNew ? "已添加自定义课程" : "已保存修改";
                    if (saved.optBoolean("overlap", false)) base += "（与已有课程叠加）";
                    Toast.makeText(MainActivity.this, base, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "save custom error", e);
                }
            });
        }

        /** 删除单条自定义课程 */
        @JavascriptInterface
        public void onDeleteCustom(String id) {
            handler.post(() -> {
                try {
                    if (store == null) store = new ScheduleStore(MainActivity.this);
                    JSONObject c = store.getCustomCourse(id);
                    if (c == null) return;
                    String name = c.optString("name", "");
                    String key = ScheduleStore.courseKey(c);
                    // 拖动覆盖是按 key 存的，删课要一并清掉，避免留下孤儿数据
                    store.removeOverride(key);
                    store.removeCustomCourse(id);
                    Log.d(TAG, "custom course removed: " + name);
                    NextCourseWidgetProvider.refreshAll(MainActivity.this);
                    renderWeekSchedule(currentDisplayWeek);
                    Toast.makeText(MainActivity.this, "已删除「" + name + "」", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "delete custom error", e);
                }
            });
        }

        /** 清空全部自定义课程（二次确认，避免误触） */
        @JavascriptInterface
        public void onClearCustomCourses() {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                final int n = store.getCustomCourseCount();
                if (n == 0) return;
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("清空自定义课程")
                    .setMessage("将删除全部 " + n + " 门自定义课程，教务课表不受影响。此操作不可恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (d, w) -> {
                        store.clearCustomCourses();
                        NextCourseWidgetProvider.refreshAll(MainActivity.this);
                        renderWeekSchedule(currentDisplayWeek);
                        Toast.makeText(MainActivity.this, "已清空自定义课程", Toast.LENGTH_SHORT).show();
                    })
                    .show();
            });
        }

        @JavascriptInterface
        public void onExportScheduleCapture() {
            handler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE, "SHZUSchedule-diagnostic-" + System.currentTimeMillis() + ".zip");
                try { startActivityForResult(intent, REQ_EXPORT_CAPTURE); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "无法打开文件保存器", Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface
        public void onPickBackground() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    pickBackgroundImage();
                }
            });
        }

        @JavascriptInterface
        public void onClearBackground() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (store == null) store = new ScheduleStore(MainActivity.this);
                        String cur = store.getBgImage();
                        if (!cur.isEmpty()) {
                            java.io.File f = new java.io.File(getFilesDir(), cur);
                            if (f.exists()) f.delete();
                        }
                        // 清理历史遗留的背景图文件
                        java.io.File[] olds = getFilesDir().listFiles();
                        if (olds != null) {
                            for (java.io.File f : olds) {
                                String n = f.getName();
                                if (n.startsWith("user_bg") && n.endsWith(".jpg")) f.delete();
                            }
                        }
                        store.setBgImage("");
                        applyAppBackground();
                        webView.evaluateJavascript("applyBg('')", null);
                        Toast.makeText(MainActivity.this, "已清除图片背景", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e(TAG, "clear bg error", e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onDragStart() {
            // 长按进入拖动模式：轻震一下给用户明确反馈
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                        if (v != null && v.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= 26) {
                                v.vibrate(android.os.VibrationEffect.createOneShot(30, 80));
                            } else {
                                v.vibrate(30);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void onCopy(String text) {
            if (text == null || text.isEmpty()) return;
            final String t = text;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("shzu", t));
                            Toast.makeText(MainActivity.this, "已复制：" + t, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "copy failed", e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onLogout() {
            handler.post(() -> {
                Log.d(TAG, "logout requested");
                // 清除凭据（password_enc 也要删：否则重启后会从 Keystore 解回来，等于没退出）
                savedUser = "";
                savedPass = "";
                prefs.edit().remove("username").remove("password").remove("password_enc").apply();
                // 清除Cookie
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                // 清除课表数据
                if (store != null) store.clear();
                // 学生资料属于账号隐私，与凭据一起清掉
                if (store != null) store.clearStudentInfo();
                // 重置状态
                onSchedulePage = false;
                cameFromLink = false;
                guessIdx = 0;
                showingDisplay = false;
                navToken++;
                showLoading("正在退出登录...");
                loadLoginPage();
            });
        }
    }

    private void showCourseDetail(String detailJson) {
        try {
            String raw = detailJson == null ? "" : detailJson.trim();
            JSONArray list = new JSONArray();
            if (raw.startsWith("[")) {
                list = new JSONArray(raw);
            } else if (!raw.isEmpty()) {
                list.put(new JSONObject(raw));
            }
            if (list.length() == 0) return;

            JSONObject first = list.optJSONObject(0);
            String firstName = first.optString("name", "");
            String color = first.optString("color", "");
            // 弹窗背景跟随主题：浅色=课程色+白，深色=课程色+深底，有自定义背景图=半透明
            boolean dark = "dark".equals(store != null ? store.getTheme() : "light");
            String bgNameCur = store != null ? store.getBgImage() : "";
            boolean hasBgCur = !bgNameCur.isEmpty()
                && new java.io.File(getFilesDir(), bgNameCur).exists();
            String paleBg;
            String startBg;
            // 弹窗就是“被放大的课程卡片”：背景由卡片原色淡出到纯白（深色主题为纯黑）
            if (hasBgCur) {
                paleBg = dark ? "rgba(0,0,0,0.72)" : "rgba(255,255,255,0.72)";
            } else {
                paleBg = dark ? "#000000" : "#FFFFFF";
            }
            // 起点色必须等于「卡片此刻的实际底色」，否则弹窗一开就跳色：
            // 浅色主题卡片是 --cc 实色，直接用课程色；
            // 深色主题卡片是灰底 + 8% 色相，这里算同一个混合结果。
            if (dark) {
                // 深色卡片是统一的灰，弹窗起点也用同一个灰，动画才从卡片里"长"出来
                startBg = DARK_CARD_BG;
            } else {
                startBg = color.isEmpty() ? paleBg : color;
            }
            String title = list.length() > 1 ? firstName + " 等" + list.length() + "门" : firstName;

            StringBuilder js = new StringBuilder();
            js.append("javascript:(function(){");
            js.append("document.getElementById('cmTitle').textContent='").append(jss(title)).append("';");
            js.append("var b=document.getElementById('cmBody');");
            js.append("var html='';");

            // 同一时段可能重叠多门课：全部列出
            for (int i = 0; i < list.length(); i++) {
                JSONObject d = list.optJSONObject(i);
                if (d == null) continue;
                String cName = d.optString("name", "");
                String teacher = d.optString("teacher", "");
                String location = d.optString("location", "");
                String weeks = d.optString("weeks", "");
                String period = d.optString("period", "");
                String time = d.optString("time", "");
                String weekStr = d.optString("week", "");
                String weekday = d.optString("weekday", "");
                String count = d.optString("count", "");
                String cls = d.optString("cls", "");
                String remark = d.optString("remark", "");

                String src = d.optString("src", "school");
                String cid = d.optString("id", "");
                boolean clash = d.optBoolean("clash", false);
                boolean isCustom = "custom".equals(src);

                js.append("html+='<div class=\"cblock\">';");
                if (isCustom) {
                    // 自定义课程始终标出来源，避免和教务课程混淆
                    js.append("html+='<div style=\"margin-bottom:8px\"><span class=\"cb-src\" ");
                    js.append("style=\"margin-left:0\">自定义课程</span></div>';");
                } else if (list.length() > 1) {
                    // 单门课时弹窗标题已显示课名；多门课（重叠）时才分别列出每门
                    js.append("html+='<div class=\"cb-name\">").append(jss(cName));
                    js.append("<span class=\"cb-tag\">第").append(i + 1).append("门</span>");
                    js.append("</div>';");
                }
                js.append("html+='<div class=\"row\"><span class=\"label\">上课时间</span><span class=\"val\">")
                  .append(jss(weekStr)).append(weekday.isEmpty() ? "" : " " + jss(weekday)).append(" ")
                  .append(jss(period)).append(time.isEmpty() ? "" : " " + jss(time)).append("</span></div>';");
                String weeksShow = weeks.isEmpty() ? (isCustom ? "每周" : "") : weeks;
                js.append("html+='<div class=\"row\"><span class=\"label\">教学周次</span><span class=\"val\">")
                  .append(jss(weeksShow)).append("</span></div>';");
                js.append("html+='<div class=\"row\"><span class=\"label\">上课地点</span><span class=\"val\">")
                  .append(location.isEmpty() ? "\u2014" : jss(location)).append("</span></div>';");
                js.append("html+='<div class=\"row\"><span class=\"label\">授课教师</span><span class=\"val\">")
                  .append(teacher.isEmpty() ? "\u2014" : jss(teacher)).append("</span></div>';");
                // 教学班级 / 选课人数只有教务系统才有，自定义课程不显示空行
                if (!isCustom) {
                    js.append("html+='<div class=\"row\"><span class=\"label\">教学班级</span><span class=\"val\">")
                      .append(cls.isEmpty() ? "\u2014" : jss(cls)).append("</span></div>';");
                    if (!count.isEmpty()) {
                        js.append("html+='<div class=\"row\"><span class=\"label\">选课人数</span><span class=\"val\">")
                          .append(jss(count)).append("人</span></div>';");
                    }
                }
                js.append("html+='<div class=\"row\"><span class=\"label\">备注</span><span class=\"val\">")
                  .append(remark.isEmpty() ? "无内容" : jss(remark)).append("</span></div>';");
                if (clash) {
                    js.append("html+='<div class=\"cb-clash\">⚠ 这个时段与另一路课程重叠，卡片已描红边框。")
                      .append("可在「设置 → 通用设置 → 位置冲突时优先显示」调整卡片主色与地点的取值顺序。</div>';");
                }
                if (isCustom && !cid.isEmpty()) {
                    js.append("html+='<div style=\"display:flex;gap:10px;margin-top:14px\">';");
                    js.append("html+='<button class=\"adv-btn\" style=\"flex:1\" onclick=\"closeModalNow();Android.onEditCustom(\\'")
                      .append(jss(cid)).append("\\')\">编辑</button>';");
                    js.append("html+='<button class=\"adv-btn\" style=\"flex:1;background:#FFF0F0;color:#e55\" ");
                    js.append("onclick=\"closeModalNow();Android.onDeleteCustom(\\'")
                      .append(jss(cid)).append("\\')\">删除</button>';");
                    js.append("html+='</div>';");
                }
                js.append("html+='</div>';");
            }

            js.append("b.innerHTML=html;");
            js.append("var modal=document.getElementById('courseModal');");
            js.append("var mEl=document.querySelector('#courseModal .modal');");
            js.append("window.__modalColor='").append(startBg).append("';");
            // 先让窗口就位（钉到卡片矩形、内容透明），再显示遮罩并开始生长。
            // 顺序不能反：若先 add('show')，窗口会以默认/上次残留的几何被画出一帧，
            // 采样中表现为"弹窗先出现在居中大尺寸，再突然跳到卡片位置"。
            js.append("playOpenAnim(mEl, window.__cardRect, '").append(startBg).append("', '").append(paleBg).append("');");
            js.append("modal.classList.add('show');");
            js.append("})()");

            webView.evaluateJavascript(js.toString(), null);
        } catch (Exception e) {
            Log.e(TAG, "showCourseDetail error", e);
        }
    }

    /**
     * 把 #RRGGBB（兼容 #RGB）转成 "r,g,b"，供 CSS 的 rgba(var(--cc-rgb),a) 使用。
     *
     * 深色卡片的边缘光要用课程色，而内联只能塞 hex，hex 带不了透明度，
     * 所以额外烘一个分量串。解析失败就退回中性灰分量，不会让整条规则失效。
     */
    private static String ccRgb(String color) {
        try {
            String h = color == null ? "" : color.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1)
                        + h.charAt(2) + h.charAt(2);
            }
            if (h.length() < 6) return "150,160,175";
            int c = Integer.parseInt(h.substring(0, 6), 16);
            return ((c >> 16) & 0xFF) + "," + ((c >> 8) & 0xFF) + "," + (c & 0xFF);
        } catch (Exception e) {
            return "150,160,175";
        }
    }

    /** 深色主题卡片底色（与 CSS 里 body.dark.glass .course 的 #1A1B1D 保持一致） */
    private static final String DARK_CARD_BG = "#1A1B1D";
    /** 把字符串数组拼成 JS 数组字面量内容：'#a','#b' */
    private static String joinQuoted(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(arr[i]).append("'");
        }
        return sb.toString();
    }

    /** 拼进 JS 单引号字符串：先 HTML 转义，再转义反斜杠/单引号/换行 */
    /**
     * 拼成 **JS 字符串字面量**（带双引号并转义），用于 evaluateJavascript 的参数。
     *
     * 注意别用 jss()：它是给「嵌在单引号里」用的（只转义、不加引号），
     * 直接拼进函数参数会变成 setMeInfo(张三,"") 这种非法 JS，页面直接报语法错误、什么都不更新。
     */
    private String jsStr(String s) {
        if (s == null) return "\"\"";
        String t = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", "");
        return "\"" + t + "\"";
    }

    private String jss(String s) {
        if (s == null) return "";
        return esc(s).replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", " ").replace("\r", "");
    }
}
