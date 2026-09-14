package com.shzu.schedule;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.http.SslError;
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
    private View loadingOverlay;
    private View errorOverlay;
    private View splashOverlay;
    private TextView loadingText;
    private Button btnRetry;

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
    private ScheduleStore store;               // 五周课表窗口存储

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
        loadingOverlay = findViewById(R.id.loadingOverlay);
        errorOverlay = findViewById(R.id.errorOverlay);
        loadingText = findViewById(R.id.loadingText);
        btnRetry = findViewById(R.id.btnRetry);
        splashOverlay = findViewById(R.id.splashOverlay);

        prefs = getSharedPreferences("shzu_schedule", MODE_PRIVATE);
        savedUser = prefs.getString("username", "");
        savedPass = prefs.getString("password", "");

        btnRetry.setOnClickListener(v -> startApp());

        // 通知渠道与权限（五周窗口末尾三天提醒）
        ReminderReceiver.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33
                && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        setupWebView();
        playSplashAnimation();
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
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
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
        // 移动端UA，确保CAS登录页按手机版布局渲染
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 16; Pixel 6) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new JsInterface(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
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
                // 教务系统可能用自签名证书，放行
                handler.proceed();
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

        // 本地有五周数据且未到更新期 → 直接渲染今天所在周（离线秒开）
        if (store.hasData() && !store.needsRefresh()) {
            int tw = store.todayWeek();
            Log.d(TAG, "startApp: local data ok, rendering week " + tw);
            renderWeekSchedule(tw > 0 ? tw : 1);
            setupReminders();
            return;
        }
        if (store.hasData()) {
            Log.d(TAG, "startApp: window expired/ending, refreshing from server");
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
                       .putString("password", savedPass).apply();
                pendingUser = null;
                pendingPass = null;
                Log.d(TAG, "credentials persisted");
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
            + "if(u)u.value='" + savedUser + "';"
            + "var p=document.getElementById('password');"
            + "if(p){p.removeAttribute('readonly');p.value='" + savedPass + "';}"
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
            int n = courses == null ? 0 : courses.length();
            int parsedWeek = obj.optInt("currentWeek", 0);
            int parsedTotal = obj.optInt("totalWeeks", 0);
            long parsedWeek1Monday = obj.optLong("week1Monday", 0);
            Log.d(TAG, "parse result: courses=" + n + " error=" + errObj
                + " currentWeek=" + parsedWeek + " totalWeeks=" + parsedTotal
                + " week1Monday=" + parsedWeek1Monday);

            if (!hasError && n > 0) {
                // 解析成功 → 保存五周窗口数据（覆盖旧数据）→ 设提醒 → 渲染今天所在周
                boolean ending = store != null && store.isEndingWindow();
                int oldStart = store != null ? store.getWindowStart() : 0;
                int oldEnd = store != null ? store.getWindowEnd() : 0;
                if (store == null) store = new ScheduleStore(this);
                store.save(this, parsedWeek, parsedTotal, parsedWeek1Monday, parsedTimes, courses, oldStart, oldEnd, ending);
                Log.d(TAG, "saved window " + store.getWindowStart() + "-" + store.getWindowEnd()
                    + " week1Monday=" + store.getWeek1Monday() + " todayWeek=" + store.todayWeek());
                setupReminders();
                int tw = store.todayWeek();
                renderWeekSchedule(tw > 0 ? tw : 1);
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
        sb.append("body{font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;");
        sb.append("background:#FFFFFF;height:100vh;color:#333;padding:12px 8px 0 8px;");
        sb.append("display:flex;flex-direction:column;overflow:hidden;}");
        sb.append(".header{text-align:center;margin-bottom:10px;}");
        sb.append(".header h1{font-size:20px;font-weight:700;color:#333;}");
        sb.append(".toolbar{display:flex;justify-content:center;align-items:center;gap:12px;margin-top:8px;}");
        sb.append(".wbtn{background:#F0F0F5;border:none;color:#555;");
        sb.append("font-size:16px;border-radius:20px;width:36px;height:36px;}");
        sb.append(".wbtn:disabled{opacity:0.3;}");
        sb.append(".wlabel{font-size:16px;font-weight:700;min-width:64px;color:#333;}");
        sb.append(".wdate{font-size:12px;color:#999;margin-top:4px;}");
        sb.append(".hint{text-align:center;font-size:12px;color:#888;margin-top:4px;}");
        sb.append(".btns{display:flex;justify-content:center;gap:10px;margin-top:8px;}");
        sb.append(".btn{background:#F0F0F5;border:none;color:#555;font-size:12px;");
        sb.append("border-radius:16px;padding:6px 16px;}");
        // 全局禁用文字选中：长按课程卡片拖动时不会误触发系统选词/复制弹窗
        sb.append("*{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;}");
        sb.append(".modal input,.modal textarea{-webkit-user-select:auto;user-select:auto;}");
        sb.append(".grid{display:grid;grid-template-columns:34px repeat(7,1fr);gap:3px;");
        sb.append("flex:1;min-height:0;grid-auto-rows:1fr;background:#F8F8FA;border-radius:14px;padding:8px;}");
        sb.append(".gh{text-align:center;font-weight:700;font-size:12px;padding:8px 2px;");
        sb.append("background:#F0F0F5;border-radius:8px;color:#333;}");
        sb.append(".gh-date{font-size:10px;font-weight:400;color:#999;margin-top:2px;}");
        sb.append(".gh.today{background:#667eea;color:#fff;}");
        sb.append(".gh.today .gh-date{color:#ddd;}");
        sb.append(".ts{font-size:10px;text-align:center;color:#777;");
        sb.append("display:flex;flex-direction:column;align-items:center;justify-content:center;");
        sb.append("background:#F0F0F5;border-radius:8px;padding:4px 2px;line-height:1.15;}");
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
        sb.append(".course .info{font-size:10px;opacity:0.9;line-height:1.4;color:#fff;}");
        sb.append(".empty{background:#FAFAFC;border-radius:8px;}");
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
        sb.append("@keyframes fadeIn{from{opacity:0;transform:translateY(8px);}");
        sb.append("to{opacity:1;transform:translateY(0);}}");
        sb.append(".course{animation:fadeIn 0.18s ease-out both;}");
        // 课程详情弹窗
        sb.append(".modal-bg{display:none;position:fixed;top:0;left:0;width:100%;height:100%;");
        sb.append("background:rgba(0,0,0,0.4);z-index:100;}");
        sb.append(".modal-bg.show{display:flex;align-items:center;justify-content:center;}");
        sb.append(".modal{background:#fff;border-radius:16px;width:85%;max-width:340px;");
        sb.append("padding:20px;box-shadow:0 8px 32px rgba(0,0,0,0.2);");
        sb.append("will-change:transform,opacity;}");
        sb.append("@keyframes slideUp{from{transform:translateY(30px);opacity:0;}");
        sb.append("to{transform:translateY(0);opacity:1;}}");
        sb.append(".modal h2{font-size:16px;font-weight:700;margin-bottom:12px;color:#333;}");
        sb.append(".modal .row{display:flex;padding:8px 0;border-bottom:1px solid #F0F0F5;}");
        sb.append(".modal .row .label{color:#999;font-size:13px;min-width:70px;}");
        sb.append(".modal .row .val{color:#333;font-size:13px;flex:1;}");
        sb.append(".modal-close{display:block;margin:16px auto 0;background:#667eea;color:#fff;");
        sb.append("border:none;border-radius:12px;padding:8px 32px;font-size:14px;}");
        // 设置弹窗
        sb.append(".settings-modal{background:#fff;border-radius:16px;width:88%;max-width:360px;");
        sb.append("overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,0.2);");
        sb.append("animation:slideUp 0.25s ease-out;}");
        sb.append(".settings-modal h2{font-size:18px;font-weight:700;padding:20px 20px 12px;color:#333;}");
        sb.append(".tab-bar{display:flex;border-bottom:1px solid #F0F0F5;}");
        sb.append(".tab{flex:1;text-align:center;padding:10px;font-size:14px;color:#999;");
        sb.append("border-bottom:2px solid transparent;}");
        sb.append(".tab.active{color:#667eea;border-bottom-color:#667eea;font-weight:600;}");
        sb.append(".tab-content{padding:16px 20px;}");
        sb.append(".settings-item{display:flex;justify-content:space-between;align-items:center;");
        sb.append("padding:14px 0;border-bottom:1px solid #F0F0F5;}");
        sb.append(".settings-item .si-label{font-size:15px;color:#333;}");
        sb.append(".settings-item .si-desc{font-size:12px;color:#999;margin-top:2px;}");
        sb.append(".logout-btn{display:block;width:100%;margin-top:20px;background:#FF4444;color:#fff;");
        sb.append("border:none;border-radius:12px;padding:12px;font-size:15px;font-weight:600;}");
        sb.append("</style></head><body>");

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
        if (numRows == 0 && courses.length() == 0) {
            sb.append("<div style='grid-column:1/-1;text-align:center;padding:40px 0;")
              .append("color:#999;font-size:14px;'>本周没有课程</div>");
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
                    String detail = buildCourseDetail(first, week, label, time, color);
                    // 格内所有课程的标识（拖动应用时整格一起持久化）
                    StringBuilder keysB = new StringBuilder();
                    for (int k = 0; k < arr.length(); k++) {
                        if (k > 0) keysB.append(";;;");
                        keysB.append(courseKey(arr.optJSONObject(k)).replace("'", "\\'"));
                    }
                    sb.append("<div class='course' style='--cc:").append(color)
                      .append(";background:var(--cc);animation-delay:").append((row + d) * 0.05).append("s' data-detail='")
                      .append(detail).append("' data-keys='").append(keysB).append("' data-day='").append(d).append("' data-row='").append(row).append("'>");
                    // 显示所有课程名（多门用换行分隔）
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject cc = arr.optJSONObject(k);
                        String nm = cc.optString("name", "").replaceAll("\\s+", " ").trim();
                        if (k > 0) sb.append("<hr style='border:0;border-top:1px solid rgba(255,255,255,0.3);margin:3px 0'>");
                        sb.append("<div class='name'>").append(esc(nm)).append("</div>");
                    }
                    String teacher = first.optString("teacher", "").trim();
                    String location = first.optString("location", "").trim();
                    StringBuilder ib = new StringBuilder();
                    if (!teacher.isEmpty()) ib.append(esc(teacher));
                    if (!location.isEmpty()) {
                        if (ib.length() > 0) ib.append(" | ");
                        ib.append(esc(location));
                    }
                    String info = ib.toString();
                    if (info.length() > 40) info = info.substring(0, 40) + "...";
                    if (!info.isEmpty()) sb.append("<div class='info'>").append(info).append("</div>");
                    sb.append("</div>");
                } else {
                    // 空格也带位置属性：拖动放置时 swapNodes 能正确交换属性，否则卡片位置被清空导致应用不生效
                    sb.append("<div class='empty' data-day='").append(d).append("' data-row='").append(row).append("'></div>");
                }
            }
        }
        sb.append("</div>");

        // 课程详情弹窗
        sb.append("<div class='modal-bg' id='courseModal' onclick='closeModal()'>");
        sb.append("<div class='modal' onclick='event.stopPropagation()'>");
        sb.append("<h2 id='cmTitle'></h2>");
        sb.append("<div id='cmBody'></div>");
        sb.append("<button class='modal-close' onclick='closeModal()'>关闭</button>");
        sb.append("</div></div>");

        // 设置弹窗
        sb.append("<div class='modal-bg' id='settingsModal' onclick='closeSettings()'>");
        sb.append("<div class='settings-modal' onclick='event.stopPropagation()'>");
        sb.append("<h2>设置</h2>");
        sb.append("<div class='tab-bar'>");
        sb.append("<div class='tab active' onclick='switchTab(0)'>账号设置</div>");
        sb.append("<div class='tab' onclick='switchTab(1)'>通用设置</div>");
        sb.append("</div>");
        // 账号设置
        sb.append("<div class='tab-content' id='tabAccount'>");
        sb.append("<div class='settings-item'><div><div class='si-label'>当前账号</div>");
        sb.append("<div class='si-desc'>").append(esc(savedUser.isEmpty() ? "未登录" : savedUser)).append("</div></div></div>");
        sb.append("<button class='logout-btn' onclick='Android.onLogout()'>退出登录</button>");
        sb.append("</div>");
        // 通用设置
        sb.append("<div class='tab-content' id='tabGeneral' style='display:none'>");
        sb.append("<div class='settings-item'><div><div class='si-label'>课表背景</div>");
        sb.append("<div class='si-desc'>白色背景</div></div></div>");
        sb.append("<div class='settings-item'><div><div class='si-label'>课程颜色</div>");
        sb.append("<div class='si-desc'>相同课程相同颜色</div></div></div>");
        sb.append("<div class='settings-item'><div><div class='si-label'>提醒功能</div>");
        sb.append("<div class='si-desc'>窗口末周自动提醒</div></div></div>");
        sb.append("</div>");
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
        sb.append("  el.classList.remove('dragging');");
        sb.append("  var target=null;");
        sb.append("  if(t){var under=document.elementFromPoint(t.clientX,t.clientY);");
        sb.append("    target=under?under.closest('.course,.empty'):null;}");
        sb.append("  el.style.transition='';el.style.transform='';el.style.pointerEvents='';");
        sb.append("  if(target&&target!==el){");
        sb.append("    swapNodes(el,target);");
        sb.append("    undoStack.push([el,target]);");
        sb.append("    updateUndoBtn();");
        sb.append("  }");
        sb.append("  setHint(null);");
        sb.append("  didDrag=true;");
        sb.append("  var db=document.getElementById('dockbar');");
        sb.append("  if(db)db.classList.remove('hide-drag');");
        sb.append("  setTimeout(function(){window.__suppressSwipe=false;},50);");
        sb.append("}");
        sb.append("function undoMove(){");
        sb.append("  if(!undoStack.length)return;");
        sb.append("  var p=undoStack.pop();");
        sb.append("  swapNodes(p[0],p[1]);");
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
        sb.append("    else{pressTimer=setTimeout(function(){pressTimer=null;startLift(card);},500);}");
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
        sb.append("    if(d){window.__cardRect=this.getBoundingClientRect();Android.onCourseClick(d);}");
        sb.append("  });");
        sb.append("});");
        // 触摸滑动切周（拖动中/拖动模式下禁用）
        sb.append("var touchStartX=0,touchEndX=0;");
        sb.append("document.addEventListener('touchstart',function(e){touchStartX=e.changedTouches[0].screenX;},false);");
        sb.append("document.addEventListener('touchend',function(e){");
        sb.append("  if(window.__suppressSwipe||dragEl){window.__suppressSwipe=false;return;}");
        sb.append("  if(dragMode)return;");
        // 详情/设置弹窗打开时不响应滑动切周，避免误触跳周
        sb.append("  var cm=document.getElementById('courseModal'),sm=document.getElementById('settingsModal');");
        sb.append("  if((cm&&cm.classList.contains('show'))||(sm&&sm.classList.contains('show')))return;");
        sb.append("  touchEndX=e.changedTouches[0].screenX;");
        sb.append("  var dx=touchEndX-touchStartX;");
        sb.append("  if(Math.abs(dx)>80){Android.onWeekChanged(").append(week + "+(dx>0?-1:1)").append(");}");
        sb.append("},false);");
        // 弹窗（课程详情：反向缩回卡片，背景色同步还原；设置：直接关闭）
        sb.append("function closeModal(){");
        sb.append("  var modal=document.getElementById('courseModal');var mEl=document.querySelector('#courseModal .modal');var r=window.__cardRect;");
        sb.append("  if(r&&r.width>0){");
        sb.append("    var m=mEl.getBoundingClientRect();");
        sb.append("    var sx=r.width/m.width;var sy=Math.max(r.height/m.height,0.15);");
        sb.append("    var dx=(r.left+r.width/2)-(m.left+m.width/2);var dy=(r.top+r.height/2)-(m.top+m.height/2);");
        // 固定 from 为可插值显式状态，防止上轮 transition/transform 残留导致跳变
        sb.append("    mEl.style.transition='none';");
        sb.append("    mEl.style.transform='translate(0px,0px) scale(1,1)';");
        sb.append("    mEl.getBoundingClientRect();");
        sb.append("    mEl.style.transition='transform 0.22s ease-in,opacity 0.22s ease-in,background-color 0.22s ease-in';");
        sb.append("    mEl.style.transform='translate('+dx+'px,'+dy+'px) scale('+sx+','+sy+')';");
        sb.append("    mEl.style.opacity='0';");
        sb.append("    mEl.style.background=(window.__modalColor||'#fff');");
        sb.append("    setTimeout(function(){modal.classList.remove('show');mEl.style.transition='';mEl.style.transform='';mEl.style.opacity='';mEl.style.background='';},230);");
        sb.append("  }else{modal.classList.remove('show');mEl.style.background='';}");
        sb.append("}");
        sb.append("function showSettings(){document.getElementById('settingsModal').classList.add('show');}");
        sb.append("function closeSettings(){document.getElementById('settingsModal').classList.remove('show');}");
        sb.append("function switchTab(i){");
        sb.append("  document.getElementById('tabAccount').style.display=i===0?'block':'none';");
        sb.append("  document.getElementById('tabGeneral').style.display=i===1?'block':'none';");
        sb.append("  document.querySelectorAll('.tab').forEach(function(t,idx){t.classList.toggle('active',idx===i);});");
        sb.append("}");
        sb.append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String buildCourseDetail(JSONObject c, int week, String periodLabel, String periodTime, String color) {
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
            return d.toString().replace("'", "\\'");
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 课程色与白色混合：t为课程色占比（0~1），t≈0.10即超级淡 */
    private static String mixWithWhite(String hex, float t) {
        try {
            String h = hex.replace("#", "").trim();
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            int mr = Math.round(255 * (1 - t) + r * t);
            int mg = Math.round(255 * (1 - t) + g * t);
            int mb = Math.round(255 * (1 - t) + b * t);
            return String.format("#%02X%02X%02X", mr, mg, mb);
        } catch (Exception e) {
            return "#FFFFFF";
        }
    }

    // ====== 五周窗口末尾提醒（最后三天每天一条通知） ======

    private void setupReminders() {
        if (store == null) return;
        long[] times = store.reminderTimes();
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        int flag = Build.VERSION.SDK_INT >= 23
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        for (int i = 0; i < 3; i++) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 100 + i,
                new Intent(this, ReminderReceiver.class), flag);
            am.cancel(pi);
            if (times != null && i < times.length) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, times[i], pi);
                Log.d(TAG, "reminder#" + i + " set at " + ScheduleStore.fmtDate(times[i]));
            }
        }
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
            Log.d(TAG, "credentials captured (pending persist)");
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
            JSONObject d = new JSONObject(detailJson);
            String name = d.optString("name", "");
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
            String color = d.optString("color", "");
            // 弹窗背景：课程色超级淡版本；展开动画中从课程原色减淡过渡
            String paleBg = color.isEmpty() ? "#FFFFFF" : mixWithWhite(color, 0.10f);
            String startBg = color.isEmpty() ? "#FFFFFF" : color;

            String js = "javascript:(function(){"
                + "document.getElementById('cmTitle').textContent='" + esc(name).replace("'", "\\'") + "';"
                + "var b=document.getElementById('cmBody');"
                + "var html='';"
                + "html+='<div class=\"row\"><span class=\"label\">上课时间</span><span class=\"val\">" + esc(weekStr) + (weekday.isEmpty() ? "" : " " + esc(weekday)) + " " + esc(period) + (time.isEmpty() ? "" : " " + esc(time)) + "</span></div>';"
                + "html+='<div class=\"row\"><span class=\"label\">教学周次</span><span class=\"val\">" + esc(weeks) + "</span></div>';"
                + "html+='<div class=\"row\"><span class=\"label\">上课地点</span><span class=\"val\">" + esc(location) + "</span></div>';"
                + "html+='<div class=\"row\"><span class=\"label\">授课教师</span><span class=\"val\">" + esc(teacher) + "</span></div>';"
                + "html+='<div class=\"row\"><span class=\"label\">教学班级</span><span class=\"val\">" + (cls.isEmpty() ? "\u2014" : esc(cls)) + "</span></div>';"
                + (count.isEmpty() ? "" : "html+='<div class=\"row\"><span class=\"label\">选课人数</span><span class=\"val\">" + esc(count) + "人</span></div>';")
                + "html+='<div class=\"row\"><span class=\"label\">教学备注</span><span class=\"val\">" + (remark.isEmpty() ? "无内容" : esc(remark)) + "</span></div>';"
                + "b.innerHTML=html;"
                + "var modal=document.getElementById('courseModal');"
                + "var mEl=document.querySelector('#courseModal .modal');"
                + "modal.classList.add('show');"
                + "window.__modalColor='" + startBg + "';"
                // FLIP：从卡片位置/尺寸放大展开成弹窗，背景随放大从课程色减淡为超淡色
                + "var r=window.__cardRect;"
                + "if(r&&r.width>0){"
                + "  var m=mEl.getBoundingClientRect();"
                + "  var sx=r.width/m.width;var sy=Math.max(r.height/m.height,0.15);"
                + "  var dx=(r.left+r.width/2)-(m.left+m.width/2);var dy=(r.top+r.height/2)-(m.top+m.height/2);"
                + "  mEl.style.transition='none';"
                + "  mEl.style.transformOrigin='center center';"
                + "  mEl.style.transform='translate('+dx+'px,'+dy+'px) scale('+sx+','+sy+')';"
                + "  mEl.style.opacity='0.25';"
                + "  mEl.style.background='" + startBg + "';"
                + "  mEl.getBoundingClientRect();"
                + "  mEl.style.transition='transform 0.3s cubic-bezier(.22,.68,.36,1),opacity 0.3s ease,background-color 0.3s ease';"
                + "  mEl.style.transform='translate(0px,0px) scale(1,1)';"
                + "  mEl.style.opacity='1';"
                + "  mEl.style.background='" + paleBg + "';"
                + "  setTimeout(function(){mEl.style.transition='';},330);"
                + "}else{mEl.style.transform='translate(0px,0px) scale(1,1)';mEl.style.opacity='1';mEl.style.background='" + paleBg + "';}"
                + "})()";
            webView.evaluateJavascript(js, null);
        } catch (Exception e) {
            Log.e(TAG, "showCourseDetail error", e);
        }
    }
}
