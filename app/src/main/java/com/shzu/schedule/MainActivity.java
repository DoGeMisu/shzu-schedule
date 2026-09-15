package com.shzu.schedule;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.http.SslError;

import com.shzu.schedule.reminder.ReminderOverlay;
import com.shzu.schedule.reminder.ReminderReceiver;
import com.shzu.schedule.reminder.ReminderService;
import com.shzu.schedule.util.CryptoHelper;
import com.shzu.schedule.util.PermissionHelper;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

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

    // ====== UI ======
    private WebView webView;
    private android.widget.ImageView bgImageView;
    private View loadingOverlay;
    private View errorOverlay;
    private View splashOverlay;
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
    private boolean parseResultReceived = false; // parseSchedule JS是否已回调结果
    private ScheduleStore store;               // 课表本地存储

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
        // WebView 透明：露出下层自定义背景图，切周重载时不会闪白
        webView.setBackgroundColor(0x00000000);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        loadingText = findViewById(R.id.loadingText);
        btnRetry = findViewById(R.id.btnRetry);
        splashOverlay = findViewById(R.id.splashOverlay);

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

        // 通知渠道与权限
        ReminderReceiver.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33
                && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        setupWebView();
        playSplashAnimation();
        // 启动课程提醒前台服务（自建定时器，不依赖系统闹钟）
        ReminderService.start(this);
        startApp();
    }

    /** 开屏动画：logo淡入放大 → 书法字展开 → 整体淡出 */
    private void playSplashAnimation() {
        try {
            android.widget.ImageView logo = findViewById(R.id.splashLogo);
            android.widget.ImageView title = findViewById(R.id.splashTitle);
            android.widget.TextView subtitle = findViewById(R.id.splashSubtitle);

            // Logo: 从小到大 + 淡入
            logo.setAlpha(0f);
            logo.setScaleX(0.6f);
            logo.setScaleY(0.6f);
            logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(700).setStartDelay(100)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();

            // 书法字：延迟入场 + 淡入右移
            title.setAlpha(0f);
            title.setTranslationY(30f);
            title.animate().alpha(1f).translationY(0f)
                .setDuration(600).setStartDelay(500)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

            // 副标题淡入
            subtitle.setAlpha(0f);
            subtitle.animate().alpha(1f)
                .setDuration(500).setStartDelay(800)
                .start();

            // 整体开屏 1.8s 后淡出
            splashOverlay.animate().alpha(0f)
                .setDuration(500).setStartDelay(1800)
                .withEndAction(() -> splashOverlay.setVisibility(View.GONE))
                .start();
        } catch (Exception e) {
            Log.e(TAG, "splash animation error", e);
            splashOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAppBackground();
        // 从系统权限页返回后刷新设置页权限状态
        refreshPermissionUi();
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
            if (!appliedOnce || dark != appliedDark) {
                try {
                    getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(bgColor));
                } catch (Exception ignored) {
                }
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

            appliedBgName = bgName;
            appliedDark = dark;
            appliedOnce = true;
        } catch (Exception e) {
            Log.e(TAG, "applyAppBackground error", e);
        }
    }

    /** 把悬浮窗/后台运行权限状态同步到设置页 */
    private void refreshPermissionUi() {
        final boolean overlay = ReminderOverlay.canDraw(this);
        final boolean battery = PermissionHelper.isIgnoringBatteryOptimizations(this);
        final boolean notify = PermissionHelper.hasNotificationPermission(this);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                try {
                    webView.evaluateJavascript(
                        "if(window.updatePermUi){updatePermUi(" + overlay + "," + battery + "," + notify + ")}",
                        null);
                } catch (Exception ignored) {
                }
            }
        }, 250);
    }

    // ====== 自定义图片背景（相册选图） ======

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
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
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

        // 本地已存整学期课表 → 直接渲染今天所在周（离线秒开）；
        // 课表更新由用户手动点「刷新课表」触发，不再自动按窗口刷新
        if (store.hasData()) {
            int tw = store.todayWeek();
            Log.d(TAG, "startApp: local data ok, rendering week " + tw);
            renderWeekSchedule(tw > 0 ? tw : 1);
            ReminderService.start(this);
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
        String js = "(function(){"
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
        webView.evaluateJavascript(js, value -> {
            String type = value == null ? "unknown" : value.replace("\"", "").trim();
            Log.d(TAG, "CAS page type: " + type);
            handleCasPageType(type);
        });
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
        String js = "javascript:(function(){"
            + "var d=document.getElementById('pwdLoginDiv');"
            + "if(d)d.style.display='block';"
            + "if(typeof showTabHeadAndDiv==='function')showTabHeadAndDiv('userNameLogin',1);"
            + "setTimeout(function(){"
            + "var u=document.getElementById('username');"
            + "if(u)u.value=" + org.json.JSONObject.quote(savedUser) + ";"
            + "var p=document.getElementById('password');"
            + "if(p){p.removeAttribute('readonly');p.value=" + org.json.JSONObject.quote(savedPass) + ";}"
            + "var r=document.getElementById('rememberMe');"
            + "if(r)r.checked=true;"
            + "var b=document.getElementById('login_submit');"
            + "if(b)b.click();"
            + "},800);"
            + "})()";
        webView.evaluateJavascript(js, null);

        // 超时仅做UI提示；不重置isAutoLogin，防止在短信MFA验证页重复自动提交密码
        handler.postDelayed(() -> {
            String currentUrl = webView.getUrl();
            if (currentUrl != null && currentUrl.contains("authserver.shzu.edu.cn")) {
                Log.d(TAG, "auto-login timeout, hiding loading");
                hideLoading();
            }
        }, 10000);
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
    private void findScheduleLink() {
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
            + "    Android.onScheduleLinkFound('TRANSITION');return;"
            + "  }"
            + "}"
            + "console.log('SCHED_SCAN: links='+linkCount+' found='+(found||'none')+' body='+(document.body.innerText||'').trim().length);"
            + "Android.onScheduleLinkFound(found);"
            + "}catch(e){console.log('SCHED_SCAN error: '+(e&&e.message?e.message:String(e)));Android.onScheduleLinkFound('');}"
            + "})()";
        webView.evaluateJavascript(js, null);
    }

    // ====== 课表解析 ======

    private void parseSchedule() {
        String js = "javascript:(function(){"
             + "var result={courses:[],error:null,currentWeek:0,totalWeeks:0,week1Monday:0,periodTimes:null};"
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
            + "    var pageTimes=(document.body.innerText||'').match(/\\d{1,2}:\\d{2}\\s*[-–]\\s*\\d{1,2}:\\d{2}/g);"
            + "    if(pageTimes&&pageTimes.length>=5)ptRes=pageTimes.slice(0,10);"
            + "  }"
            + "  if(ptRes.length>=3)result.periodTimes=ptRes;"
            + "}catch(e){}"
            + "var table=document.querySelector('table.kbtable')"
            + "||document.querySelector('#kbtable')"
            + "||document.querySelector('#xskb_table')"
            + "||document.querySelector('.kbcjtable');"
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
            + "  Android.onScheduleParsed(JSON.stringify(result));"
            + "  return;"
            + "}"
            + "var rows=table.querySelectorAll('tr');"
            + "for(var i=1;i<rows.length;i++){"
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
            + "      var name='',teacher='',loc='',weeks='',remark='',cnt='',clsInfo='';"
            + "      var inCourse=false;"
            + "      for(var f=0;f<fonts.length;f++){"
            + "        var ft=fonts[f];"
            + "        var title=ft.getAttribute('title')||'';"
            + "        var txt=(ft.textContent||'').trim();"
            + "        if(!txt)continue;"
            // 属性类标签：教师/教室/周次/备注等
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
            + "        if(/楼|室|区|馆|场|苑|厅|号/.test(txt)&&!loc&&title===''){loc=txt;continue;}"
            // 非属性类：可能是课程名或学时信息
            // 学时信息以(开头，如(讲课:56)，不是课程名
            + "        if(txt.charAt(0)==='('||txt.indexOf('讲课')>=0||txt.indexOf('实践')>=0||txt.indexOf('实验')>=0||txt.indexOf('线上')>=0||txt.indexOf('其它')>=0){continue;}"
            // 新课程名：如果已有课程名，先保存当前课程
            + "        if(name){"
            + "          result.courses.push({"
            + "            day:j+1,row:i,name:name,teacher:teacher,"
            + "            location:loc,weeks:weeks,remark:remark,count:cnt,cls:clsInfo,"
            + "            text:kbcs[kc].textContent.replace(/\\s+/g,' ').trim().substring(0,80)"
            + "          });"
            + "          name=txt;teacher='';loc='';weeks='';remark='';cnt='';clsInfo='';inCourse=true;"
            + "          continue;"
            + "        }"
            + "        name=txt;inCourse=true;"
            + "      }"
            // 保存最后一门课程
            + "      if(name&&inCourse){"
            + "        result.courses.push({"
            + "          day:j+1,row:i,name:name,teacher:teacher,"
            + "          location:loc,weeks:weeks,remark:remark,count:cnt,cls:cls,"
            + "          text:kbcs[kc].textContent.replace(/\\s+/g,' ').trim().substring(0,80)"
            + "        });"
            + "      }"
            + "    }"
            // 兜底：无kbcontent时用旧方式提取
            + "    if(kbcs.length===0){"
            + "      var text=cell.textContent.trim();"
            + "      if(text&&text.length>2&&text!=='\\u00a0'){"
            + "        result.courses.push({"
            + "          day:j+1,row:i,"
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
            + "Android.onScheduleParsed(JSON.stringify(result));"
            + "}catch(e){"
            + "result.error='js_exception:'+(e&&e.message?e.message:String(e));"
            + "console.log('SCHED_PARSE exception: '+result.error);"
            + "Android.onScheduleParsed(JSON.stringify(result));"
            + "}"
            + "})()";
        webView.evaluateJavascript(js, null);
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

    private void displaySchedule(String json) {
        parseResultReceived = true;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray courses = obj.optJSONArray("courses");
            // 节次时间表（从教务页面提取的作息时间，全部合法才存）
            JSONArray ptArr = obj.optJSONArray("periodTimes");
            String[] parsedTimes = null;
            if (ptArr != null && ptArr.length() >= 3) {
                String[] cand = new String[ptArr.length()];
                boolean ok = true;
                for (int i = 0; i < ptArr.length(); i++) {
                    String t = ptArr.optString(i, "");
                    if (!t.matches("\\d{1,2}:\\d{2}\\s*[-–]\\s*\\d{1,2}:\\d{2}")) { ok = false; break; }
                    cand[i] = t.replaceAll("\\s+", "");
                }
                if (ok) {
                    parsedTimes = mergePeriodTimes(cand);
                    Log.d(TAG, "parsed periodTimes: " + parsedTimes.length + " slots: " + String.join(",", parsedTimes));
                }
            }
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
                // 解析成功 → 保存整学期课表（覆盖旧数据）→ 设提醒 → 渲染今天所在周
                if (store == null) store = new ScheduleStore(this);
                store.save(this, parsedWeek, parsedTotal, parsedWeek1Monday, parsedTimes, courses);
                Log.d(TAG, "saved window " + store.getWindowStart() + "-" + store.getWindowEnd()
                    + " week1Monday=" + store.getWeek1Monday() + " todayWeek=" + store.todayWeek());
                // 课表数据更新 → 重算课程提醒时刻表
                ReminderService.reload(this);
                int tw = store.todayWeek();
                renderWeekSchedule(tw > 0 ? tw : 1);
                // 新数据落地后同步刷新桌面小组件
                NextCourseWidgetProvider.refreshAll(this);
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

    /** 课程排课记录唯一标识（用于拖动位置覆盖）：名称|教师|周次|原day|原row
     *  必须编入教务原始位置：同名称同周次的多条记录（如晚自习排周一~周五及周日5条）key 各不相同，
     *  可独立拖动互不覆盖；而同一门课跨周渲染时原始位置恒定，key 不变，拖动位置对所有周同步生效。
     *  旧格式 override（不含位置后缀）自然失配失效，无需清除数据 */
    private static String courseKey(JSONObject c) {
        return (c.optString("name", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("teacher", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("weeks", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optInt("day", 0)
            + "|" + c.optInt("row", 0));
    }

    // ====== 按周渲染课表 ======

    private int currentDisplayWeek = 1;

    private void renderWeekSchedule(int week) {
        if (store == null) store = new ScheduleStore(this);
        if (!store.hasData()) return;
        if (week < 1) week = 1;
        int total = store.getTotalWeeks();
        if (total > 0 && week > total) week = total;
        currentDisplayWeek = week;
        JSONArray courses = store.coursesForWeek(week);
        Log.d(TAG, "renderWeekSchedule: week=" + week + " courses=" + courses.length());
        String html = generateWeekHTML(week, courses);
        hideLoading();
        showingDisplay = true;
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String generateWeekHTML(int week, JSONArray courses) {
        int todayWeek = store.todayWeek();
        int totalWeeks = store.getTotalWeeks();
        if (totalWeeks <= 0) totalWeeks = week;
        long monday = store.getWeek1Monday() > 0 ? store.mondayOf(week) : 0;

        Calendar cal = Calendar.getInstance();
        int dw = cal.get(Calendar.DAY_OF_WEEK);
        int todayDow = dw == Calendar.SUNDAY ? 7 : dw - 1;

        // 同课同色：课程名→颜色索引
        Map<String, Integer> colorMap = new HashMap<>();
        int colorIdx = 0;

        // 用户拖动产生的位置覆盖（长按拖动课表后保存的自定义布局）
        JSONObject ov = store.getOverrides();

        // (节次行,星期) → 课程列表；同格多课合并
        Map<String, JSONArray> cellMap = new HashMap<>();
        int maxRow = 0;
        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            int row = c.optInt("row", 0);
            int day = c.optInt("day", 0);
            // 优先使用用户拖动定义的位置
            if (ov != null) {
                JSONObject o = ov.optJSONObject(courseKey(c));
                if (o != null) {
                    int nd = o.optInt("d", 0), nr = o.optInt("r", 0);
                    if (nd >= 1 && nd <= 7 && nr >= 1 && nr <= 5) { day = nd; row = nr; }
                }
            }
            if (row <= 0 || day < 1 || day > 7) continue;
            if (row > 5) continue; // 最多5节(9-10节)，不显示11-14节
            String key = row + "_" + (day - 1);
            JSONArray arr = cellMap.get(key);
            if (arr == null) { arr = new JSONArray(); cellMap.put(key, arr); }
            arr.put(c);
            if (row > maxRow) maxRow = row;
            // 分配颜色
            String name = c.optString("name", "");
            if (!colorMap.containsKey(name)) colorMap.put(name, colorIdx++);
        }

        // 节次时间表（从教务作息数据获取，失败时用新疆高校常见作息兜底）
        String[] periodLabels = {"1-2节", "3-4节", "5-6节", "7-8节", "9-10节"};
        String[] periodTimes = store != null ? store.getPeriodTimes() : null;
        String[] defaultTimes = {"10:00-11:50", "12:00-13:50", "14:00-15:50", "16:00-17:50", "18:00-19:50"};
        if (periodTimes == null) periodTimes = defaultTimes;
        // 课程颜色（柔和pastel，白底适配）
        String[] colors = {
            "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFA07A",
            "#DDA0DD", "#87CEEB", "#F0E68C", "#FFB6C1", "#98D8C8",
            "#B0C4DE", "#DEB887", "#87CEFA", "#90EE90", "#FFD700"
        };

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1.0'>");
        sb.append("<style>");
        sb.append("*{margin:0;padding:0;box-sizing:border-box;}");
        // 主题变量：浅色（默认）/ 深色，背景与文字带过渡动画
        sb.append(":root{--bg:#FFFFFF;--fg:#333;--sub:#999;--card:#F0F0F5;--card2:#F8F8FA;");
        sb.append("--line:#F0F0F5;--empty:#FAFAFC;--modal:#FFFFFF;}");
        sb.append("body.dark{--bg:#0F1016;--fg:#E9E9F2;--sub:#A9A9BE;--card:#1E1F2A;--card2:#161722;");
        sb.append("--line:#2A2B38;--empty:#191A24;--modal:#1A1B26;}");
        sb.append("body{font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;");
        sb.append("background-color:var(--bg);height:100vh;color:var(--fg);padding:12px 8px 0 8px;");
        sb.append("transition:background-color 0.35s ease,color 0.35s ease;");
        sb.append("display:flex;flex-direction:column;overflow:hidden;}");
        // 有自定义背景图时页面背景透明，露出 Activity 层图片（切周不闪）
        sb.append("body.hasbg{background-color:transparent;}");
        sb.append(".header{text-align:center;margin-bottom:10px;}");
        sb.append(".header h1{font-size:20px;font-weight:700;color:var(--fg);}");
        sb.append(".toolbar{display:flex;justify-content:center;align-items:center;gap:12px;margin-top:8px;}");
        sb.append(".wbtn{background:var(--card);border:none;color:var(--fg);");
        sb.append("font-size:16px;border-radius:20px;width:36px;height:36px;}");
        sb.append(".wbtn:disabled{opacity:0.3;}");
        sb.append(".wlabel{font-size:16px;font-weight:700;min-width:64px;color:var(--fg);}");
        sb.append(".wdate{font-size:12px;color:#999;margin-top:4px;}");
        sb.append(".hint{text-align:center;font-size:12px;color:#888;margin-top:4px;}");
        sb.append(".btns{display:flex;justify-content:center;gap:10px;margin-top:8px;}");
        sb.append(".btn{background:var(--card);border:none;color:var(--fg);font-size:12px;");
        sb.append("border-radius:16px;padding:6px 16px;}");
        // 课程卡片右上角提醒铃铛：点击即开关该课提醒
        sb.append(".course .bell{position:absolute;top:5px;right:5px;width:24px;height:24px;");
        sb.append("border-radius:50%;display:flex;align-items:center;justify-content:center;");
        sb.append("background:rgba(255,255,255,0.22);z-index:12;");
        sb.append("transition:transform 0.18s ease,background 0.18s ease;}");
        sb.append(".course .bell svg{width:15px;height:15px;fill:rgba(255,255,255,0.62);");
        sb.append("transition:fill 0.18s ease;}");
        sb.append(".course .bell.on{background:#FFECA8;}");
        sb.append(".course .bell.on svg{fill:#D99400;}");
        sb.append(".course .bell:active{transform:scale(0.85);}");
        sb.append(".course.remind-on{box-shadow:inset 0 0 0 2px rgba(255,214,71,0.92);}");
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
        sb.append(".gh{text-align:center;font-weight:700;font-size:12px;padding:8px 2px;");
        sb.append("background:var(--card);border-radius:8px;color:var(--fg);}");
        sb.append(".gh-date{font-size:10px;font-weight:400;color:#999;margin-top:2px;}");
        sb.append(".gh.today{background:#667eea;color:#fff;}");
        sb.append(".gh.today .gh-date{color:#ddd;}");
        sb.append(".ts{font-size:10px;text-align:center;color:#777;");
        sb.append("display:flex;flex-direction:column;align-items:center;justify-content:center;");
        sb.append("background:var(--card);border-radius:8px;padding:4px 2px;line-height:1.15;}");
        sb.append(".ts .ts-time{font-size:8px;color:#aaa;margin-top:2px;}");
        sb.append(".course{border-radius:10px;padding:9px 7px;font-size:11px;");
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
        sb.append("font-size:12px;color:#fff;}");
        sb.append(".course .info{font-size:10px;opacity:0.95;line-height:1.35;color:#fff;");
        sb.append("font-weight:600;letter-spacing:0.2px;}");
        sb.append(".empty{background:var(--empty);border-radius:8px;}");
        sb.append(".grid-head{display:grid;grid-template-columns:34px repeat(7,1fr);gap:3px;margin-bottom:4px;padding:0 8px;}");
        // 拖动模式底部操作栏（横向拉长、低高度；拖动中下滑隐藏）
        sb.append(".dockbar{position:fixed;left:50%;bottom:10px;transform:translate(-50%,150%);");
        sb.append("background:#fff;border-radius:16px;box-shadow:0 8px 32px rgba(0,0,0,0.2);");
        sb.append("display:flex;gap:6px;padding:6px 8px;z-index:80;opacity:0;");
        sb.append("width:calc(100% - 24px);max-width:420px;");
        sb.append("transition:transform 0.3s cubic-bezier(.22,.68,.36,1),opacity 0.3s ease;}");
        sb.append(".dockbar.show{transform:translate(-50%,0);opacity:1;}");
        sb.append(".dockbar.hide-drag{transform:translate(-50%,150%);opacity:0;}");
        sb.append(".db-btn{border:none;border-radius:12px;padding:9px 10px;font-size:12px;font-weight:600;}");
        sb.append(".db-btn:disabled{opacity:0.4;}");
        sb.append(".db-apply{background:#667eea;color:#fff;flex:2.2;order:2;font-size:14px;}");
        sb.append(".db-cancel{background:#F0F0F5;color:#555;flex:1;order:1;}");
        sb.append(".db-undo{background:#F0F0F5;color:#555;flex:1;order:3;}");
        // 课程卡片入场动画：由小变大（缩放淡入）
        // 仅用于课表刷新场景（切周 / 首次加载 / 手动刷新导入）
        //
        // fill-mode 必须是 backwards：
        //   - backwards：动画延迟期间就应用起始帧(opacity:0,scale:.72)，
        //     否则卡片会先以最终样式整块闪出来、等延迟结束后再从头播，视觉上
        //     就是"先直接显示 → 消失 → 再放大"
        //   - 不能用 both/forwards：那会在结束后锁死 transform，
        //     导致 .dragging 的 scale(1.07) 与 :active 的 scale(0.96) 全部失效
        sb.append("@keyframes popIn{from{opacity:0;transform:scale(0.72);}");
        sb.append("to{opacity:1;transform:scale(1);}}");
        sb.append(".course{animation:popIn 0.22s cubic-bezier(.34,1.4,.64,1) backwards;}");
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

        sb.append(".modal h2{font-size:16px;font-weight:700;margin-bottom:12px;color:var(--fg);}");
        sb.append(".modal .row{display:flex;padding:8px 0;border-bottom:1px solid var(--line);}");
        sb.append(".modal .row .label{color:var(--sub);font-size:13px;min-width:70px;}");
        sb.append(".modal .row .val{color:var(--fg);font-size:13px;flex:1;}");
        sb.append(".modal #cmBody{max-height:58vh;overflow-y:auto;-webkit-overflow-scrolling:touch;}");
        sb.append(".modal .cblock{margin-top:2px;}");
        sb.append(".modal .cblock+.cblock{border-top:1px solid var(--line);margin-top:14px;padding-top:14px;}");
        sb.append(".modal .cb-name{font-size:15px;font-weight:700;color:var(--fg);margin-bottom:4px;}");
        sb.append(".modal .cb-tag{font-size:11px;color:var(--sub);font-weight:400;margin-left:6px;}");
        sb.append(".modal-close{display:block;margin:16px auto 0;background:#667eea;color:#fff;");
        sb.append("border:none;border-radius:12px;padding:8px 32px;font-size:14px;}");
        // 设置弹窗
        sb.append(".settings-modal{background:var(--modal);border-radius:16px;width:88%;max-width:360px;");
        sb.append("overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,0.3);");
        sb.append("animation:slideUp 0.25s ease-out;max-height:82vh;");
        sb.append("display:flex;flex-direction:column;transition:background-color 0.3s ease;}");
        // 标题与标签栏固定，仅内容区滚动
        sb.append(".settings-modal h2{font-size:18px;font-weight:700;padding:20px 20px 12px;color:var(--fg);");
        sb.append("flex:0 0 auto;}");
        sb.append(".tab-bar{display:flex;border-bottom:1px solid var(--line);flex:0 0 auto;}");
        sb.append(".tab{flex:1;text-align:center;padding:10px;font-size:14px;color:var(--sub);");
        sb.append("border-bottom:2px solid transparent;}");
        sb.append(".tab.active{color:#667eea;border-bottom-color:#667eea;font-weight:600;}");
        sb.append(".tab-content{padding:16px 20px;overflow-y:auto;-webkit-overflow-scrolling:touch;flex:1 1 auto;}");
        sb.append("@keyframes tabIn{from{opacity:0;transform:translateX(14px);}to{opacity:1;transform:translateX(0);}}");
        sb.append(".tab-in{animation:tabIn 0.22s ease-out;}");
        sb.append(".settings-item{display:flex;justify-content:space-between;align-items:center;");
        sb.append("padding:14px 0;border-bottom:1px solid var(--line);}");
        sb.append(".settings-item .si-label{font-size:15px;color:var(--fg);}");
        sb.append(".settings-item .si-desc{font-size:12px;color:var(--sub);margin-top:2px;}");
        sb.append(".logout-btn{display:block;width:100%;margin-top:20px;background:#FF4444;color:#fff;");
        sb.append("border:none;border-radius:12px;padding:12px;font-size:15px;font-weight:600;}");
        // 权限清单样式（一眼看出缺哪项）
        sb.append(".perm-list{margin-top:10px;}");
        sb.append(".perm-row{display:flex;align-items:flex-start;gap:10px;padding:10px 12px;");
        sb.append("border-radius:10px;background:var(--card);margin-bottom:8px;cursor:pointer;}");
        sb.append(".perm-row:active{opacity:0.75;}");
        sb.append(".perm-row .perm-st{font-size:15px;font-weight:700;width:18px;text-align:center;flex:0 0 auto;}");
        sb.append(".perm-row.ok .perm-st{color:#3BB273;}");
        sb.append(".perm-row.bad .perm-st{color:#E5533D;}");
        sb.append(".perm-row.warn .perm-st{color:#E8A200;}");
        sb.append(".perm-row .perm-tx{flex:1;font-size:13px;color:var(--fg);line-height:1.35;}");
        sb.append(".perm-row .perm-tx i{display:block;font-style:normal;font-size:11px;color:var(--sub);margin-top:3px;}");
        // 设置页按钮统一样式（跟随主题，带按压反馈）
        sb.append(".adv-btn{background:var(--card);border:1px solid var(--line);");
        sb.append("border-radius:10px;padding:9px 14px;font-size:13px;color:var(--fg);");
        sb.append("cursor:pointer;transition:background-color 0.2s ease,border-color 0.2s ease;}");
        sb.append(".adv-btn:active{opacity:0.75;}");
        sb.append(".adv-btn,.perm-btn{background:var(--card);border:1px solid var(--line);");
        sb.append("border-radius:10px;padding:9px 14px;font-size:13px;color:var(--fg);");
        sb.append("cursor:pointer;transition:background-color 0.2s ease,border-color 0.2s ease;}");
        sb.append(".adv-btn:active,.perm-btn:active{opacity:0.75;}");
        sb.append(".perm-btn.perm-ok{background:#E8F7EE;border-color:#3BB273;color:#2E8B57;font-weight:600;}");
        sb.append(".si-hint{font-size:12px;color:#999;margin-top:8px;line-height:1.5;}");
        // 空课周提示
        sb.append(".emptyweek{grid-column:1/-1;text-align:center;color:var(--sub);font-size:13px;padding:40px 0;}");
        // 自定义图片背景：卡片/控件半透明，文字保持原色
        sb.append("body.hasbg .grid{background:transparent;}");
        sb.append("body.hasbg .gh{background:rgba(240,240,245,0.62);}");
        sb.append("body.hasbg .ts{background:rgba(240,240,245,0.62);}");
        sb.append("body.hasbg .empty{background:transparent;}");
        sb.append("body.hasbg .btn{background:rgba(240,240,245,0.62);}");
        sb.append("body.hasbg .wbtn{background:rgba(240,240,245,0.62);}");
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
        sb.append("body.hasbg.dark .wbtn{background:rgba(30,31,42,0.62);}");
        sb.append("body.hasbg.dark .course::before{opacity:0.88;}");
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
        // 应用主题与自定义背景图
        String theme = store != null ? store.getTheme() : "light";
        String bgName = store != null ? store.getBgImage() : "";
        // ===== 独立设置页样式 =====
        sb.append(".page{position:fixed;top:0;left:0;right:0;bottom:0;background:var(--bg);z-index:95;");
        sb.append("display:flex;flex-direction:column;visibility:hidden;transform:translateX(100%);");
        sb.append("transition:transform 0.26s cubic-bezier(.22,.68,.36,1),visibility 0.26s ease;}");
        sb.append(".page.show{visibility:visible;transform:translateX(0);}");
        sb.append(".page-head{display:flex;align-items:center;padding:8px;border-bottom:1px solid var(--line);flex:0 0 auto;}");
        sb.append(".ph-back{background:transparent;border:none;font-size:26px;color:var(--fg);width:48px;height:48px;cursor:pointer;}");
        sb.append(".ph-title{flex:1;text-align:center;font-size:17px;font-weight:700;color:var(--fg);}");
        sb.append(".ph-pad{width:48px;flex:0 0 auto;}");
        sb.append(".page-body{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:16px;}");
        sb.append(".nav-card{background:var(--card);border-radius:14px;overflow:hidden;}");
        sb.append(".nav-item{display:flex;align-items:center;padding:16px;border-bottom:1px solid var(--line);cursor:pointer;}");
        sb.append(".nav-item:last-child{border-bottom:none;}");
        sb.append(".nav-item:active{background:rgba(102,126,234,0.12);}");
        sb.append(".ni-txt{flex:1;}");
        sb.append(".ni-txt b{display:block;font-size:15px;font-weight:600;color:var(--fg);}");
        sb.append(".ni-txt i{display:block;font-style:normal;font-size:12px;color:var(--sub);margin-top:4px;}");
        sb.append(".ni-arrow{color:var(--sub);font-size:20px;margin-left:8px;}");
        sb.append(".about-hero{text-align:center;padding:26px 0 22px;}");
        sb.append(".about-hero .ah-name{font-size:26px;font-weight:800;color:var(--fg);letter-spacing:2px;}");
        sb.append(".about-hero .ah-ver{margin-top:8px;font-size:13px;color:#667eea;font-weight:600;}");
        sb.append(".about-hero .ah-slogan{margin-top:10px;font-size:12px;color:var(--sub);line-height:1.6;}");
        sb.append(".about-card{background:var(--card);border-radius:14px;padding:2px 16px;margin-bottom:14px;}");
        sb.append(".about-card .ac-title{font-size:12px;color:#667eea;font-weight:700;padding:14px 0 6px;letter-spacing:1px;}");
        sb.append(".about-card .kv{display:flex;padding:11px 0;border-top:1px solid var(--line);font-size:13px;}");
        sb.append(".about-card .kv:first-of-type{border-top:none;}");
        sb.append(".about-card .kv span{color:var(--sub);min-width:74px;flex:0 0 auto;}");
        sb.append(".about-card .kv b{color:var(--fg);font-weight:500;flex:1;word-break:break-all;line-height:1.5;}");
        sb.append(".about-card .kv b.tap{color:#667eea;cursor:pointer;}");
        sb.append(".about-card .kv b.tap:active{opacity:0.6;}");
        sb.append(".about-note{font-size:12px;color:var(--sub);line-height:1.7;padding:0 4px 24px;}");

        boolean hasBg = !bgName.isEmpty() && new java.io.File(getFilesDir(), bgName).exists();
        sb.append("</style></head><body class='").append("dark".equals(theme) ? "dark" : "light")
          .append(hasBg ? " hasbg" : "")
          .append("'>");

        // 头部
        sb.append("<div class='header'>");
        sb.append("<h1>石大课表</h1>");
        sb.append("<div class='toolbar'>");
        if (week > 1) {
            sb.append("<button class='wbtn' onclick='Android.onWeekChanged(").append(week - 1).append(")'>◀</button>");
        } else {
            sb.append("<button class='wbtn' disabled>◀</button>");
        }
        sb.append("<span class='wlabel'>第").append(week).append("周</span>");
        if (week < totalWeeks) {
            sb.append("<button class='wbtn' onclick='Android.onWeekChanged(").append(week + 1).append(")'>▶</button>");
        } else {
            sb.append("<button class='wbtn' disabled>▶</button>");
        }
        sb.append("</div>");
        if (monday > 0) {
            sb.append("<div class='wdate'>").append(ScheduleStore.fmtDate(monday)).append(" 起</div>");
        }
        if (week == todayWeek) {
            sb.append("<div class='hint'>本周 · 今天 ")
              .append(ScheduleStore.fmtDate(System.currentTimeMillis())).append(" ")
              .append(ScheduleStore.fmtWeekday(System.currentTimeMillis())).append("</div>");
        }
        sb.append("<div class='btns'>");
        sb.append("<button class='btn' onclick='Android.onRefreshRequested()'>刷新课表</button>");
        sb.append("<button class='btn' onclick='showSettings()'>设置</button>");
        sb.append("</div>");
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
                dateStr = "<div class='gh-date'>" + ScheduleStore.fmtDate(dayDate) + "</div>";
            }
            String cls = "gh" + (today ? " today" : "");
            sb.append("<div class='").append(cls).append("'>")
              .append(days[d - 1]).append(dateStr).append("</div>");
        }
        sb.append("</div>");

        // 课程网格
        sb.append("<div class='grid'>");

        int numRows = Math.min(maxRow, 5);
        // 空课周：只显示提示，不渲染空空格子
        if (courses.length() == 0) numRows = 0;
        if (numRows == 0) {
            sb.append("<div class='emptyweek'>这一周没有课程</div>");
        }
        for (int row = 1; row <= numRows; row++) {
            String label = row <= periodLabels.length ? periodLabels[row - 1] : row + "节";
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
                JSONArray arr = cellMap.get(row + "_" + (d - 1));
                if (arr != null && arr.length() > 0) {
                    JSONObject first = arr.optJSONObject(0);
                    String firstName = first.optString("name", "").replaceAll("\\s+", " ").trim();
                    int ci = colorMap.getOrDefault(firstName, 0);
                    String color = colors[ci % colors.length];
                    // 详情包含该格所有课程（同一时段可能有多门重叠课）
                    String detail = buildCourseDetailArray(arr, week, label, time, color);
                    // 格内所有课程的标识（拖动/提醒均按整格处理）
                    StringBuilder keysB = new StringBuilder();
                    boolean allOn = true;
                    java.util.Set<String> rk = store.getReminderKeys();
                    for (int k = 0; k < arr.length(); k++) {
                        if (k > 0) keysB.append(";;;");
                        String ck = courseKey(arr.optJSONObject(k));
                        keysB.append(ck.replace("'", "\\'"));
                        if (!rk.contains(ck)) allOn = false;
                    }
                    sb.append("<div class='course" + (allOn ? " remind-on" : "") + "' style='--cc:").append(color)
                      .append(";background:var(--cc);animation-delay:").append((row + d) * 0.05).append("s' data-detail='")
                      .append(detail).append("' data-keys='").append(keysB).append("' data-day='").append(d).append("' data-row='").append(row).append("'>");
                    // 右上角铃铛：一键开关这一格所有课程的提醒
                    sb.append("<div class='bell").append(allOn ? " on" : "").append("' onclick='toggleBell(event,this)'>")
                      .append("<svg viewBox='0 0 24 24'><path d='M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z'/></svg>")
                      .append("</div>");
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
                    sb.append("<div class='empty' data-day='").append(d).append("' data-row='").append(row).append("'></div>");
                }
            }
        }
        sb.append("</div>");

        // 课程详情弹窗：外层 .modal 是「生长的窗口」，内层 .m-inner 是内容（不缩放）
        sb.append("<div class='modal-bg' id='courseModal' onclick='closeModal()'>");
        sb.append("<div class='modal' onclick='event.stopPropagation()'>");
        sb.append("<div class='m-inner'>");
        sb.append("<h2 id='cmTitle'></h2>");
        sb.append("<div id='cmBody'></div>");
        sb.append("<button class='modal-close' onclick='closeModal()'>关闭</button>");
        sb.append("</div></div></div>");

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
        sb.append("<i>课表背景、提醒提前时间、提醒权限、课程布局</i></div><div class='ni-arrow'>›</div></div>");
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
        // 提醒提前时间选择器
        int curAdvance = store != null ? store.getAdvanceMinutes() : 15;
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>提醒提前时间</div>");
        sb.append("<div class='si-desc'>上课前提前多久通知</div></div>");
        sb.append("<div style='display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;'>");
        int[] options = {5, 10, 15, 20, 30, 45, 60};
        for (int opt : options) {
            String sel = (opt == curAdvance) ? "style='background:#667eea;color:#fff;'" : "";
            sb.append("<button class='adv-btn adv-min' onclick='setAdvance(").append(opt).append(")' ")
              .append(sel).append(">").append(opt).append("分钟</button>");
        }
        sb.append("</div></div>");
        // 提醒权限：清单式，一眼看出缺哪项，点即跳转
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>提醒权限</div>");
        sb.append("<div class='si-desc'>开启后退出应用、锁屏也能准时弹提醒；点任一项可直接去开启</div></div>");
        sb.append("<div class='perm-list' id='permList'></div>");
        sb.append("<div class='si-hint' id='permHint'>权限状态检测中…</div>");
        sb.append("<button class='adv-btn' style='margin-top:10px;width:100%;' onclick='toggleGuide()'>查看本机设置指引</button>");
        sb.append("<div class='si-hint' id='permGuide' style='display:none;white-space:pre-line;text-align:left;'></div>");
        sb.append("</div>");
        // 课程布局
        sb.append("<div class='settings-item' style='flex-direction:column;align-items:stretch;'>");
        sb.append("<div><div class='si-label'>课程布局</div>");
        sb.append("<div class='si-desc'>如果课表位置看起来不对，可一键恢复课程默认位置</div></div>");
        sb.append("<button class='adv-btn' style='margin-top:10px;background:#FFF0F0;color:#e55;' ");
        sb.append("onclick='Android.onResetLayout()'>重置课程布局</button>");
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
        sb.append("<div class='kv'><span>技术架构</span><b>WebView 混合架构：原生负责登录、数据与提醒，网页负责课表渲染与交互</b></div>");
        sb.append("<div class='kv'><span>核心逻辑</span><b>CAS 单点登录 → 教务 DOM 解析课表 → 本地离线存储 → 前台服务定时提醒（悬浮窗 + 响铃）</b></div>");
        sb.append("<div class='kv'><span>数据存储</span><b>课表与配置存本机，密码经 Android Keystore 加密</b></div>");
        sb.append("<div class='kv'><span>兼容版本</span><b>Android 7.0 及以上（API 24+）</b></div>");
        sb.append("</div>");
        // 作者与开源
        sb.append("<div class='about-card'>");
        sb.append("<div class='ac-title'>开源与作者</div>");
        sb.append("<div class='kv'><span>作者 ID</span><b>m1Su</b></div>");
        sb.append("<div class='kv'><span>QQ</span><b class='tap' onclick='copyTxt(\"196331872\")'>196331872</b></div>");
        sb.append("<div class='kv'><span>微信</span><b class='tap' onclick='copyTxt(\"m1SuCode\")'>m1SuCode</b></div>");
        sb.append("<div class='kv'><span>邮箱</span><b class='tap' onclick='copyTxt(\"19633187@qq.com\")'>19633187@qq.com</b></div>");
        sb.append("<div class='kv'><span>GitHub</span><b class='tap' onclick='copyTxt(\"https://github.com/DoGeMisu/shzu-schedule\")'>DoGeMisu / shzu-schedule</b></div>");
        sb.append("<div class='kv'><span>开源协议</span><b>MIT License</b></div>");
        sb.append("</div>");
        sb.append("<div class='about-note'>点击 QQ / 微信 / 邮箱 / GitHub 可复制。本项目为在校学生利用课余时间出于学习目的独立开发维护，不收取任何费用，请勿用于商业用途。</div>");
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
        sb.append("var PERM_GUIDE=").append(org.json.JSONObject.quote(PermissionHelper.guideText())).append(";");
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
        // 课程卡事件：长按500ms浮起/拖动模式即时浮起；拖动后抑制click
        sb.append("document.querySelectorAll('.course').forEach(function(el){");
        sb.append("  el.addEventListener('touchstart',function(e){");
        sb.append("    if(dragEl)return;");
        sb.append("    var card=this;");
        sb.append("    pressX=e.touches[0].clientX;pressY=e.touches[0].clientY;");
        sb.append("    if(dragMode){startLift(card);}");
        sb.append("    else{pressTimer=setTimeout(function(){pressTimer=null;startLift(card);if(window.Android&&Android.onDragStart)Android.onDragStart();},700);}");
        sb.append("  },false);");
        sb.append("  el.addEventListener('touchmove',function(e){");
        sb.append("    if(dragEl===this){");
        sb.append("      e.preventDefault();");
        sb.append("      moveDrag(e.touches[0].clientX,e.touches[0].clientY);");
        sb.append("    }else if(pressTimer){");
        sb.append("      var t=e.touches[0];");
        sb.append("      if(Math.abs(t.clientX-pressX)>12||Math.abs(t.clientY-pressY)>12){clearTimeout(pressTimer);pressTimer=null;}");
        sb.append("    }");
        sb.append("  },false);");
        sb.append("  el.addEventListener('touchend',function(e){");
        sb.append("    if(pressTimer){clearTimeout(pressTimer);pressTimer=null;}");
        sb.append("    if(dragEl===this){e.preventDefault();endDrag(e.changedTouches[0]);}");
        sb.append("  },false);");
        sb.append("  el.addEventListener('touchcancel',function(){");
        sb.append("    if(pressTimer){clearTimeout(pressTimer);pressTimer=null;}");
        sb.append("    if(dragEl===this)endDrag(null);");
        sb.append("  },false);");
        sb.append("  el.addEventListener('click',function(){");
        sb.append("    if(didDrag){didDrag=false;return;}");
        sb.append("    if(dragMode)return;");
        sb.append("    var d=this.getAttribute('data-detail');");
        // 取 rect 时若入场动画(popIn)还在播，scale 会让 getBoundingClientRect
        // 返回缩放后的矩形，窗口起点就算错了。
        //
        // 但不能用「禁用动画再恢复」的办法：恢复的那一刻元素重新匹配 CSS 的
        // animation 规则，popIn 会连 animation-delay 一起从头播放，首帧 opacity:0 —— 
        // 表现为「点一下卡片消失」或「卡片又播了一次入场动画」。
        // 这里改成把动画直接快进到终态并保持，不动 animation 属性。
        sb.append("    if(d){");
        sb.append("      if(this.getAnimations){");
        sb.append("        this.getAnimations().forEach(function(a){try{a.finish();}catch(e){}});");
        sb.append("      }");
        sb.append("      var rc=this.getBoundingClientRect();");
        sb.append("      window.__cardRect={left:rc.left,top:rc.top,width:rc.width,height:rc.height};");
        sb.append("      window.__openedCard=this;Android.onCourseClick(d);}");
        sb.append("  });");
        sb.append("});");
        // 触摸滑动切周（拖动中/拖动模式下禁用）
        sb.append("var touchStartX=0,touchEndX=0;");
        sb.append("document.addEventListener('touchstart',function(e){touchStartX=e.changedTouches[0].screenX;},false);");
        sb.append("document.addEventListener('touchend',function(e){");
        sb.append("  if(window.__suppressSwipe||dragEl){window.__suppressSwipe=false;return;}");
        sb.append("  if(dragMode)return;");
        // 详情/设置弹窗打开时不响应滑动切周，避免误触跳周
        sb.append("  var cm=document.getElementById('courseModal');");
        sb.append("  if(cm&&cm.classList.contains('show'))return;");
        sb.append("  if(pageStack.length)return;");
        sb.append("  touchEndX=e.changedTouches[0].screenX;");
        sb.append("  var dx=touchEndX-touchStartX;");
        sb.append("  if(Math.abs(dx)>80){Android.onWeekChanged(").append(week + "+(dx>0?-1:1)").append(");}");
        sb.append("},false);");
        // ===== 课程卡片铃铛：点击开关该课提醒 =====
        sb.append("function toggleBell(ev,el){");
        sb.append("  ev.stopPropagation();");
        sb.append("  var card=el.closest('.course');");
        sb.append("  if(!card)return;");
        sb.append("  var keys=(card.getAttribute('data-keys')||'').split(';;;').filter(function(k){return k;});");
        sb.append("  if(!keys.length)return;");
        sb.append("  var on=!el.classList.contains('on');");
        sb.append("  el.classList.toggle('on',on);");
        sb.append("  card.classList.toggle('remind-on',on);");
        sb.append("  Android.onToggleCourseReminder(keys.join(';;;'),on);");
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
        sb.append("function updatePermUi(overlay,battery,notify){");
        sb.append("  var list=[");
        sb.append("    {n:'悬浮窗权限',ok:overlay,t:'到点把提醒弹在其他应用之上（最关键）',a:'Android.onRequestOverlay()'},");
        sb.append("    {n:'通知权限',ok:notify,t:'权限不足时用通知兜底提醒',a:'Android.onRequestNotification()'},");
        sb.append("    {n:'后台运行',ok:battery,t:'不受系统省电限制，避免被系统冻结',a:'Android.onRequestBattery()'},");
        sb.append("    {n:'自启动',ok:null,t:'重启手机后自动恢复提醒（系统无接口，需手动确认）',a:'Android.onRequestAutoStart()'}");
        sb.append("  ];");
        sb.append("  var h='';");
        sb.append("  for(var i=0;i<list.length;i++){var it=list[i];");
        sb.append("    var cls=it.ok===null?'warn':(it.ok?'ok':'bad');");
        sb.append("    var st=it.ok===null?'!':(it.ok?'\\u2713':'\\u2715');");
        sb.append("    h+='<div class=\"perm-row '+cls+'\" onclick=\"'+it.a+'\">';");
        sb.append("    h+='<span class=\"perm-st\">'+st+'</span>';");
        sb.append("    h+='<span class=\"perm-tx\"><b>'+it.n+'</b><i>'+it.t+'</i></span>';");
        sb.append("    h+='</div>';}");
        sb.append("  var pl=document.getElementById('permList');");
        sb.append("  if(pl)pl.innerHTML=h;");
        sb.append("  var hint=document.getElementById('permHint');");
        sb.append("  if(hint){");
        sb.append("    if(overlay&&notify)hint.textContent='权限已就绪，退出应用也能准时提醒';");
        sb.append("    else hint.textContent='还差关键权限未开，此时提醒会降级为普通通知（点上方任一项去开启）';");
        sb.append("  }}");
        sb.append("function toggleGuide(){var g=document.getElementById('permGuide');if(!g)return;");
        sb.append("  if(g.style.display==='none'){g.textContent=PERM_GUIDE;g.style.display='block';}");
        sb.append("  else g.style.display='none';}");
        // ===== 设置页导航（独立页面，从右侧滑入）=====
        sb.append("var pageStack=[];");
        sb.append("function openPage(id){var p=document.getElementById(id);if(!p)return;");
        sb.append("  p.classList.add('show');pageStack.push(id);}");
        sb.append("function closePage(){var id=pageStack.pop();if(!id)return;");
        sb.append("  var p=document.getElementById(id);if(p)p.classList.remove('show');}");
        // 系统返回键统一入口：优先关设置页，再关课程详情弹窗，返回'1'表示已消费
        sb.append("function handleBack(){");
        sb.append("  if(typeof pageStack!=='undefined'&&pageStack.length){closePage();return '1';}");
        sb.append("  var cm=document.getElementById('courseModal');");
        sb.append("  if(cm&&cm.classList.contains('show')){closeModal();return '1';}");
        sb.append("  return '0';}");
        sb.append("function openSub(name){");
        sb.append("  var map={account:'pageAccount',general:'pageGeneral',about:'pageAbout'};");
        sb.append("  if(map[name])openPage(map[name]);}");
        sb.append("function showSettings(){openPage('pageSettings');");
        sb.append("  if(window.Android&&Android.onSettingsOpened)Android.onSettingsOpened();}");
        sb.append("function copyTxt(t){if(window.Android&&Android.onCopy)Android.onCopy(t);}");
        sb.append("function setAdvance(m){Android.onSetAdvance(m);");
        sb.append("document.querySelectorAll('.adv-min').forEach(function(b){");
        sb.append("b.style.background='';b.style.color='';});");
        sb.append("event.target.style.background='#667eea';event.target.style.color='#fff';}");
        // 主题切换（带背景过渡动画）
        sb.append("function setTheme(m){");
        sb.append("  document.body.classList.toggle('dark',m==='dark');");
        sb.append("  updateThemeBtn(m);");
        sb.append("  Android.onSetTheme(m);}");
        sb.append("function updateThemeBtn(m){");
        sb.append("  var l=document.getElementById('themeLight'),d=document.getElementById('themeDark');");
        sb.append("  if(l){l.style.background=m==='dark'?'':'#667eea';l.style.color=m==='dark'?'':'#fff';}");
        sb.append("  if(d){d.style.background=m==='dark'?'#667eea':'';d.style.color=m==='dark'?'#fff':'';}}");
        // 自定义图片背景
        sb.append("function applyBg(url){");
        sb.append("  document.body.classList.toggle('hasbg',!!url);");
        sb.append("  var b=document.getElementById('clearBgBtn');");
        sb.append("  if(b)b.style.display=url?'inline-block':'none';}");
        sb.append("updateThemeBtn('").append("dark".equals(theme) ? "dark" : "light").append("');");
        sb.append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 单个课程的详情对象 */
    private JSONObject buildCourseDetailObj(JSONObject c, int week, String periodLabel, String periodTime, String color) {
        try {
            JSONObject d = new JSONObject();
            d.put("name", c.optString("name", ""));
            d.put("teacher", c.optString("teacher", ""));
            d.put("location", c.optString("location", ""));
            d.put("weeks", c.optString("weeks", ""));
            d.put("period", periodLabel);
            d.put("time", periodTime);
            d.put("week", "第" + week + "周");
            String[] wd = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            int day = c.optInt("day", 0);
            d.put("weekday", day >= 1 && day <= 7 ? wd[day] : "");
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
    private String buildCourseDetailArray(JSONArray arr, int week, String periodLabel, String periodTime, String color) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            out.put(buildCourseDetailObj(c, week, periodLabel, periodTime, color));
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
        public void onToggleCourseReminder(String keyList, final boolean on) {
            if (keyList == null || keyList.isEmpty()) return;
            final String[] ks = keyList.split(";;;");
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (store == null) store = new ScheduleStore(MainActivity.this);
                        java.util.Set<String> keys = store.getReminderKeys();
                        for (String k : ks) {
                            if (k == null || k.isEmpty()) continue;
                            if (on) keys.add(k); else keys.remove(k);
                        }
                        store.saveReminderKeys(keys);
                        ReminderService.reload(MainActivity.this);
                        String label = ks.length > 1 ? (ks.length + " 门课程") : ks[0].split("\\|")[0];
                        Toast.makeText(MainActivity.this,
                            on ? "已开启「" + label + "」上课提醒（共 " + keys.size() + " 门）"
                               : "已关闭「" + label + "」上课提醒",
                            Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "toggle reminder " + (on ? "on" : "off") + " x" + ks.length);
                    } catch (Exception e) {
                        Log.e(TAG, "toggle reminder error", e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onAddWidget() {
            handler.post(() -> requestAddWidget());
        }

        @JavascriptInterface
        public void onSetAdvance(int minutes) {
            handler.post(() -> {
                if (store == null) store = new ScheduleStore(MainActivity.this);
                store.setAdvanceMinutes(minutes);
                Log.d(TAG, "advance set to " + minutes + " minutes");
                // 提前量变了 → 重算提醒时刻表
                ReminderService.reload(MainActivity.this);
                Toast.makeText(MainActivity.this,
                    "提醒提前量已设为 " + minutes + " 分钟",
                    Toast.LENGTH_SHORT).show();
            });
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
                        ReminderService.reload(MainActivity.this);
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
        public void onSettingsOpened() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    refreshPermissionUi();
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
        public void onRequestNotification() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                    } else {
                        PermissionHelper.openAppDetails(MainActivity.this);
                    }
                }
            });
        }

        @JavascriptInterface
        public void onRequestOverlay() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    PermissionHelper.openOverlaySettings(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public void onRequestAutoStart() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    // 小米/红米优先直达权限编辑页（“后台弹出界面”是提醒能否弹出的关键）
                    boolean ok = PermissionHelper.isMiui()
                        ? PermissionHelper.openMiuiPopupPermission(MainActivity.this)
                        : PermissionHelper.openAutoStartSettings(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                        ok ? "如列表中有「石大课表」请允许自启动；找不到说明本机无此开关"
                           : "未能打开自启动设置，请到系统设置里手动允许",
                        Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void onRequestBattery() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    PermissionHelper.openBatterySettings(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public void onDebugReminder(final int seconds) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    int sec = seconds <= 0 ? 10 : seconds;
                    if (!ReminderOverlay.canDraw(MainActivity.this)) {
                        Toast.makeText(MainActivity.this,
                            "悬浮窗权限未开启，提醒将以通知形式显示",
                            Toast.LENGTH_LONG).show();
                    }
                    ReminderService.debug(MainActivity.this, sec);
                    Toast.makeText(MainActivity.this,
                        sec + " 秒后弹出测试提醒，可退回桌面查看",
                        Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void onLogout() {
            handler.post(() -> {
                Log.d(TAG, "logout requested");
                // 清除凭据
                savedUser = "";
                savedPass = "";
                prefs.edit().remove("username").remove("password").apply();
                // 清除Cookie
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                // 清除课表数据
                if (store != null) store.clear();
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
            startBg = color.isEmpty() ? paleBg : color;
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

                js.append("html+='<div class=\"cblock\">';");
                // 单门课时弹窗标题已显示课名，不再重复；多门课（重叠）时才分别列出每门
                if (list.length() > 1) {
                    js.append("html+='<div class=\"cb-name\">").append(jss(cName));
                    js.append("<span class=\"cb-tag\">第").append(i + 1).append("门</span>");
                    js.append("</div>';");
                }
                js.append("html+='<div class=\"row\"><span class=\"label\">上课时间</span><span class=\"val\">")
                  .append(jss(weekStr)).append(weekday.isEmpty() ? "" : " " + jss(weekday)).append(" ")
                  .append(jss(period)).append(time.isEmpty() ? "" : " " + jss(time)).append("</span></div>';");
                js.append("html+='<div class=\"row\"><span class=\"label\">教学周次</span><span class=\"val\">").append(jss(weeks)).append("</span></div>';");
                js.append("html+='<div class=\"row\"><span class=\"label\">上课地点</span><span class=\"val\">")
                  .append(location.isEmpty() ? "\u2014" : jss(location)).append("</span></div>';");
                js.append("html+='<div class=\"row\"><span class=\"label\">授课教师</span><span class=\"val\">")
                  .append(teacher.isEmpty() ? "\u2014" : jss(teacher)).append("</span></div>';");
                js.append("html+='<div class=\"row\"><span class=\"label\">教学班级</span><span class=\"val\">")
                  .append(cls.isEmpty() ? "\u2014" : jss(cls)).append("</span></div>';");
                if (!count.isEmpty()) {
                    js.append("html+='<div class=\"row\"><span class=\"label\">选课人数</span><span class=\"val\">").append(jss(count)).append("人</span></div>';");
                }
                js.append("html+='<div class=\"row\"><span class=\"label\">教学备注</span><span class=\"val\">")
                  .append(remark.isEmpty() ? "无内容" : jss(remark)).append("</span></div>';");
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

    /** 拼进 JS 单引号字符串：先 HTML 转义，再转义反斜杠/单引号/换行 */
    private String jss(String s) {
        if (s == null) return "";
        return esc(s).replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", " ").replace("\r", "");
    }
}
