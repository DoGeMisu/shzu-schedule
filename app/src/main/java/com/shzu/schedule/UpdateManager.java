package com.shzu.schedule;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自动更新：检查版本 → 下载 → 字节数 + SHA-256 校验 → 唤起系统安装器。
 *
 * 服务端是 m1SuHub（Nginx + PHP 8.3 + SQLite），接口依据
 * 《石大课表 · 自动更新接口对接文档》（2026-10-27 版）：
 *   GET /api/version.php?app=&lt;AppKey&gt;&amp;current=&lt;本地版本&gt;&amp;key=&lt;API Key&gt;
 *   GET /api/content.php?app=&lt;AppKey&gt;
 *
 * 设计约束（对应文档 §4 / §6）：
 * 1. 只从接口返回的 download_url 下载，不硬编码任何下载地址；
 *    download_url 是 6 小时签名链接，**不缓存到磁盘**，每次下载前现取。
 * 2. 必须校验 size 与 SHA-256，任一不符立即删除并报错。
 * 3. 校验通过前不触碰安装目录；下载到 getExternalFilesDir(null)/update/。
 * 4. 网络失败（超时/断网/503）静默降级，不阻塞启动。
 * 5. 401 表示 API Key 失效：属于配置错误，不做重试。
 * 6. 请求超时 15 秒；下载超时按包大小动态设置（≥120 秒）。
 * 7. 必须跟随重定向（服务端可能 301 到 https），且解析 JSON 前先看 Content-Type
 *    —— 503 熔断时返回的是 HTML 错误页。
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";

    /**
     * 更新服务 Base URL。本期按对接文档 §6 使用 IPv4 直连地址：
     * 域名 misuhub.top 尚未完成 ICP 备案，备案完成前不得在客户端出现任何域名。
     * 备案完成后由站长发布新安装包，把这一行换成正式域名即可 —— 服务端两个入口并存，
     * 老版本继续用 IP 不受影响。因此 Base URL 只允许出现在这一个常量里。
     *
     * 历史教训（v1.4.1 的 bug「打开软件不检查更新」）：
     * 当时在 network_security_config 里给 IP 配了明文放行，但 Android 的
     * &lt;domain&gt; 只匹配主机名、**不支持 IP 字面量**，放行实际无效，请求被
     * cleartextTrafficPermitted="false" 拦掉。故本期的明文放行必须写在
     * base-config 上（见 res/xml/network_security_config.xml 的说明）。
     */
    public static final String BASE_URL = "http://120.26.246.218:8080";
    /** 本应用在 m1SuHub 后台登记的 App Key（站长创建） */
    public static final String APP_KEY = "shzu-schedule";

    /**
     * App 专属 API Key（m1ak_ 开头）。服务端只存它的 SHA-256，作用是把自家 App
     * 与盗链脚本区分开，并解锁「不限次签名下载通道」——不带 key 会退化为
     * 每 IP 每天 5 次的受限通道（见文档 §3）。
     *
     * 值来自 gradle.properties 的 shzuUpdateApiKey，由 build.gradle 注入 BuildConfig，
     * 不写死在业务代码里；轮换密钥只需改属性并重新发版（旧版会收到 401）。
     */
    private static final String API_KEY = BuildConfig.UPDATE_API_KEY;

    // ====== HTTP 状态语义（文档 §4 错误码矩阵）======
    public static final int ERR_NETWORK = -1;      // 连接失败/超时：可重试
    public static final int ERR_CONFIG = 401;      // API Key 错误：配置问题，禁止重试
    public static final int ERR_BAD_PARAM = 400;   // app/current 参数非法
    public static final int ERR_FORBIDDEN = 403;   // 下载链接过期 / 文件权限 / IP 被封
    public static final int ERR_NOT_FOUND = 404;   // 应用不存在或安装包已下架
    public static final int ERR_QUOTA = 429;       // 匿名下载额度用尽
    public static final int ERR_MAINTENANCE = 503; // 站点流量熔断（响应是 HTML）

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;
    /** 下载超时下限（任务书 4.4-5：按包大小动态设置，建议 ≥120 秒） */
    private static final int DOWNLOAD_TIMEOUT_MIN = 120000;
    private static final int BUFFER = 8192;
    private static final int MAX_RETRY = 3;
    /** 重定向最大跳数（服务端可能 301 到 https） */
    private static final int MAX_REDIRECTS = 3;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 取消标记：用户点「取消下载」时置位，下载循环据此中止 */
    private volatile boolean cancelled = false;

    /** 检查结果 */
    public static class Info {
        public boolean updateAvailable;
        public String latestVersion = "";
        public String downloadUrl = "";
        public long size = 0;
        public String sha256 = "";
        public String changelog = "";
        /** 本应用公共内容（公告/维护通知），空串表示不展示 */
        public String content = "";
        public boolean forceUpdate;
        public String releasedAt = "";
        /** 本次请求的 HTTP 状态；ERR_NETWORK 表示连接层失败 */
        public int httpCode = 200;
        /** 服务端返回的错误说明（ok=false 时）或本地错误描述 */
        public String error = "";

        /** 请求是否被服务端接受（HTTP 200 且 ok=true 且有最新版本信息） */
        public boolean ok() {
            return httpCode == 200 && error.isEmpty() && !latestVersion.isEmpty();
        }

        /** API Key 无效：配置错误，调用方应提示而不是无限重试 */
        public boolean isConfigError() {
            return httpCode == ERR_CONFIG;
        }
    }

    /** 下载进度回调 */
    public interface ProgressListener {
        void onProgress(long done, long total);
        void onFinished(File apk);
        void onFailed(String reason);
        /** 用户主动取消（任务书 4.2-4：安装失败/取消时保留文件可重试） */
        void onCancelled();
    }

    /** User-Agent 带应用标识（文档 §4.4-6），形如 shzu-schedule/1.4.4 (Android) */
    private String userAgent() {
        return "shzu-schedule/" + currentVersionName() + " (Android)";
    }

    /** 本地 versionName；取不到时返回 "0" */
    private String currentVersionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    public UpdateManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ==================== 版本检查 ====================

    /**
     * 查询服务端最新版本与公共内容。
     *
     * 返回值恒为非 null：**连接层失败也会返回 Info**，只是 httpCode=ERR_NETWORK、error 带描述。
     * 这样调用方能区分「没网」「密钥失效」「站点熔断」，而不是一律当作"检查失败"。
     *
     * @param current 本地 versionName，如 "1.4.4"
     */
    public Info checkForUpdate(final String current) {
        Info info = new Info();
        Resp r;
        try {
            r = request(versionUrl(current), READ_TIMEOUT);
        } catch (Exception e) {
            Log.w(TAG, "check failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            info.httpCode = ERR_NETWORK;
            info.error = "网络连接失败";
            return info;
        }

        info.httpCode = r.code;
        if (r.code == ERR_CONFIG) {
            // 文档 §2：401 = 密钥配置错误，应当提示配置问题而不是无限重试
            Log.e(TAG, "api key rejected (401), check BuildConfig.UPDATE_API_KEY");
            info.error = "invalid api key";
            return info;
        }
        if (r.code == ERR_MAINTENANCE) {
            // 熔断时响应体是 HTML，先判 Content-Type 再解析，避免 JSON 异常
            Log.w(TAG, "service unavailable (503, " + r.contentType + ")");
            info.error = "服务暂时不可用";
            return info;
        }
        JSONObject json = jsonOf(r);
        if (json == null) {
            Log.w(TAG, "check failed: HTTP " + r.code + " 非 JSON 响应 (" + r.contentType + ")");
            info.error = "响应格式异常";
            return info;
        }
        if (!json.optBoolean("ok")) {
            String err = json.optString("error", "请求失败");
            Log.w(TAG, "check failed: HTTP " + r.code + " " + err);
            info.error = err;
            return info;
        }

        info.error = "";
        info.latestVersion = json.optString("latest_version", "");
        info.downloadUrl = json.optString("download_url", "");
        info.size = json.optLong("size", 0);
        info.sha256 = json.optString("sha256", "");
        info.changelog = json.optString("changelog", "");
        info.content = json.optString("content", "");
        info.forceUpdate = json.optBoolean("force_update", false);
        info.releasedAt = json.optString("released_at", "");
        // 文档 §1：优先信任服务端的 update_available，避免两端规则不一致；
        // 未回传该字段（例如没传 current）时才由客户端按同一套规则补算。
        if (json.has("update_available")) {
            info.updateAvailable = json.optBoolean("update_available");
        } else {
            info.updateAvailable = compareVersion(current, info.latestVersion) < 0;
        }
        // 有更新但没给下载地址：服务端配置不全，按不可更新处理（保留 content）
        if (info.updateAvailable && info.downloadUrl.isEmpty()) {
            Log.w(TAG, "update available but download_url empty");
            info.updateAvailable = false;
        }
        return info;
    }

    /** 只拉公共内容（内容频繁变动、不需要版本信息时用） */
    public String fetchContent() {
        try {
            Resp r = request(BASE_URL + "/api/content.php?app=" + Uri.encode(APP_KEY), READ_TIMEOUT);
            JSONObject json = jsonOf(r);
            if (json == null || !json.optBoolean("ok")) return "";
            return json.optString("content", "");
        } catch (Exception e) {
            Log.w(TAG, "fetchContent error", e);
            return "";
        }
    }

    /**
     * 组装 version 接口地址。
     * current 按文档要求只允许 [0-9A-Za-z._-]{1,32}，这里先净化再发送，
     * 避免本地版本号带奇怪字符时被服务端 400 拒绝。
     */
    private static String versionUrl(String current) {
        String cur = current == null ? "" : current.trim().replaceAll("[^0-9A-Za-z._-]", "");
        if (cur.length() > 32) cur = cur.substring(0, 32);
        StringBuilder sb = new StringBuilder(BASE_URL)
                .append("/api/version.php?app=").append(Uri.encode(APP_KEY));
        if (!cur.isEmpty()) sb.append("&current=").append(Uri.encode(cur));
        if (API_KEY != null && !API_KEY.isEmpty()) sb.append("&key=").append(Uri.encode(API_KEY));
        return sb.toString();
    }

    /** 把 HTTP 状态码翻成给用户看的文案（文档 §4 错误码矩阵） */
    public static String httpErrorMessage(int code) {
        switch (code) {
            case ERR_CONFIG:
                return "更新服务密钥失效，请安装最新版本";
            case ERR_BAD_PARAM:
                return "更新请求参数异常";
            case ERR_FORBIDDEN:
                return "下载链接已过期或当前网络被限制，请稍后重试";
            case ERR_NOT_FOUND:
                return "更新包暂不可用";
            case ERR_QUOTA:
                return "下载次数已达上限，请明天再试";
            case ERR_MAINTENANCE:
                return "服务暂时不可用，请稍后重试";
            case ERR_NETWORK:
                return "网络连接失败，请检查网络后重试";
            default:
                return "更新失败（HTTP " + code + "）";
        }
    }

    // ==================== 下载与校验 ====================

    /** 取消下载（用户点「取消」时调用）；已下载完成的校验/安装不受影响 */
    public void cancelDownload() {
        cancelled = true;
    }

    /** 是否已被用户取消（供 UI 判断要不要收起进度条） */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 后台线程执行：下载 → 字节数 + SHA-256 校验 → 回调。
     * 下载到 external files 的 update 子目录，校验通过前不进入安装流程。
     *
     * 签名链接有效期 6 小时（文档 §2），进程内保留的 download_url 可能已过期：
     * 遇到 403 时重新取一次 version.php 换新链接再试，且换链接不计入重试次数。
     */
    public void downloadAndVerify(final Info info, final ProgressListener listener) {
        cancelled = false;
        io.execute(() -> {
            File dir = updateDir();
            if (dir == null) {
                post(listener, l -> l.onFailed("存储空间不可用"));
                return;
            }
            String fileName = "shzu-schedule-v" + info.latestVersion + ".apk";
            File dest = new File(dir, fileName);
            // 复用已下载且校验通过的同版本安装包（用户上次取消了安装，这次不用再下一遍）
            if (dest.exists() && dest.length() > 0
                    && sizeMatches(dest, info.size) && sha256Matches(dest, info.sha256)) {
                Log.d(TAG, "reuse cached apk: " + fileName);
                post(listener, l -> l.onFinished(dest));
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dest.delete();

            String url = info.downloadUrl;
            int attempt = 0;
            boolean urlRefreshed = false;
            while (attempt < MAX_RETRY) {
                if (cancelled) {
                    Log.d(TAG, "download cancelled by user");
                    post(listener, l -> l.onCancelled());
                    return;
                }
                attempt++;
                Log.d(TAG, "download attempt " + attempt + ": " + url);
                try {
                    downloadFile(url, dest, info.size, listener);
                    if (cancelled) {
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                        post(listener, l -> l.onCancelled());
                        return;
                    }
                    // 文档 §4：字节数 + SHA-256 双重校验，任一不符立即删除并报错
                    if (!sizeMatches(dest, info.size)) {
                        Log.w(TAG, "size mismatch, expected=" + info.size
                                + " actual=" + dest.length());
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                        post(listener, l -> l.onFailed("安装包大小不符，下载可能不完整"));
                        return;
                    }
                    if (sha256Matches(dest, info.sha256)) {
                        post(listener, l -> l.onFinished(dest));
                        return;
                    }
                    String reason = info.sha256.isEmpty()
                            ? "安装包校验失败" : "安装包校验失败，可能损坏或被篡改";
                    Log.w(TAG, "sha256 mismatch, expected=" + info.sha256
                            + " actual=" + sha256Of(dest));
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    post(listener, l -> l.onFailed(reason));
                    return;
                } catch (HttpStatusException se) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    // 403 = 签名过期 / 文件权限 / IP 被封。过期可以换链接重来一次
                    if (se.code == ERR_FORBIDDEN && !urlRefreshed) {
                        urlRefreshed = true;
                        attempt--; // 换链接不计入重试次数
                        String fresh = freshDownloadUrl();
                        if (!fresh.isEmpty()) {
                            Log.d(TAG, "signed download url expired, refreshed once");
                            url = fresh;
                            continue;
                        }
                    }
                    Log.w(TAG, "download rejected: HTTP " + se.code);
                    final String msg = httpErrorMessage(se.code);
                    post(listener, l -> l.onFailed(msg));
                    return;
                } catch (IOException e) {
                    Log.w(TAG, "download attempt " + attempt + " failed", e);
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    if (cancelled) {
                        post(listener, l -> l.onCancelled());
                        return;
                    }
                    if (attempt >= MAX_RETRY) {
                        post(listener, l -> l.onFailed("下载失败，请检查网络后重试"));
                        return;
                    }
                    try {
                        Thread.sleep(1200L * attempt);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "download error", e);
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    post(listener, l -> l.onFailed("下载失败：" + e.getMessage()));
                    return;
                }
            }
        });
    }

    /**
     * 重新取一次版本信息以获得新的签名下载链接（旧链接 6 小时后失效）。
     * 取不到就返回空串，由调用方按失败处理。
     */
    private String freshDownloadUrl() {
        try {
            Info fresh = checkForUpdate(currentVersionName());
            if (fresh != null && fresh.ok() && !fresh.downloadUrl.isEmpty()) {
                return fresh.downloadUrl;
            }
            Log.w(TAG, "refresh download url failed: " + (fresh == null ? "null" : fresh.error));
        } catch (Exception e) {
            Log.w(TAG, "refresh download url error", e);
        }
        return "";
    }

    private void downloadFile(String urlStr, File dest, long expectSize,
                              ProgressListener listener) throws IOException {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        File tmp = new File(dest.getAbsolutePath() + ".part");
        try {
            // 下载超时按包大小动态设置：1MB 至少给 2 秒，总和不低于 DOWNLOAD_TIMEOUT_MIN
            int readTimeout = DOWNLOAD_TIMEOUT_MIN;
            if (expectSize > 0) {
                readTimeout = Math.max(readTimeout, (int) (expectSize / 1024 * 2));
            }
            c = openFollowing(urlStr, CONNECT_TIMEOUT, readTimeout, null, userAgent());
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // 403 过期 / 404 下架 / 429 额度 / 503 熔断：交给上层按状态码分类处理
                throw new HttpStatusException(code);
            }
            long total = c.getContentLengthLong();
            if (total <= 0) total = expectSize;
            in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[BUFFER];
            long done = 0;
            int n;
            long lastPush = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                // 每 250ms 推一次进度，避免主线程被刷爆
                long now = System.currentTimeMillis();
                if (now - lastPush > 250) {
                    lastPush = now;
                    final long d = done, t = total;
                    post(listener, l -> l.onProgress(d, t));
                }
            }
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(dest)) {
                throw new IOException("rename failed");
            }
            final long d = dest.length();
            post(listener, l -> l.onProgress(d, d));
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (c != null) c.disconnect();
        }
    }

    /** 下载/请求遇到非 200 时抛出，携带状态码供上层分类处理 */
    private static class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    /** 字节数比对：服务端没给 size（0）时跳过，避免误判 */
    private static boolean sizeMatches(File f, long expect) {
        return expect <= 0 || f.length() == expect;
    }

    /** SHA-256 比对；服务端没给校验值（空串）时放行，但调用方应提示风险 */
    private boolean sha256Matches(File f, String expect) {
        if (expect == null || expect.trim().isEmpty()) return true;
        try {
            String actual = sha256Of(f);
            return expect.trim().toLowerCase(Locale.US).equals(actual);
        } catch (Exception e) {
            Log.e(TAG, "sha256 compute error", e);
            return false;
        }
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    // ==================== 安装 ====================

    /**
     * 唤起系统安装器。Android 8+ 需要用户授予「安装未知应用」权限，
     * 未授权时先跳到系统设置页（用户手动返回后需再点一次更新）。
     */
    public boolean installApk(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PackageManager pm = ctx.getPackageManager();
                if (!pm.canRequestPackageInstalls()) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return false;
                }
            }
            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "install error", e);
            return false;
        }
    }

    /** 系统安装器可用性：是否已允许安装未知应用 */
    public boolean canInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ctx.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /**
     * 清理已安装完成的临时安装包（任务书 4.2-4）。
     * 在「安装后启动弹更新内容」时调用：此时新版本已经跑起来，旧包没有保留价值。
     * 安装失败/用户取消时**不要**调这个，保留文件以便重试。
     */
    public void clearDownloadedApk() {
        try {
            File dir = updateDir();
            if (dir == null) return;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".apk"));
            if (files == null) return;
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            Log.d(TAG, "cleared downloaded apks");
        } catch (Exception e) {
            Log.w(TAG, "clearDownloadedApk failed", e);
        }
    }

    /** 打开「允许安装未知应用」系统设置页 */
    public void openInstallPermissionPage() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open install permission page failed", e);
        }
    }

    // ==================== 工具 ====================

    private File updateDir() {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "update");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdir update dir failed: " + dir);
            return null;
        }
        return dir;
    }

    /** 一次 HTTP 响应的原始结果：状态码、Content-Type、正文 */
    private static class Resp {
        int code;
        String contentType = "";
        String body = "";
    }

    /**
     * 发一次 GET 并读回完整响应。**不在这里抛状态码异常**：
     * 4xx/5xx 的 body 仍是 JSON（{"ok":false,"error":...}），交给调用方判断。
     * 只有连接层失败（超时/断网）才抛 IOException。
     */
    private Resp request(String urlStr, int readTimeout) throws IOException {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = openFollowing(urlStr, CONNECT_TIMEOUT, readTimeout,
                    "application/json", "shzu-schedule-updater/" + currentVersionName());
            Resp r = new Resp();
            r.code = c.getResponseCode();
            String ct = c.getContentType();
            r.contentType = ct == null ? "" : ct.toLowerCase(Locale.US);
            // 失败时 body 仍是 JSON（除外 503 熔断时是 HTML），统一读回来再判类型
            in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                r.body = new String(bos.toByteArray(), "UTF-8");
            }
            return r;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (c != null) c.disconnect();
        }
    }

    /**
     * 打开连接并**手动跟随重定向**。
     *
     * HttpURLConnection 的 setInstanceFollowRedirects 不跨协议跟随
     * （http→https 会直接返回 301），而文档 §4 明确要求客户端必须能跟随 301，
     * 因此这里自己走 Location，最多 MAX_REDIRECTS 跳。
     */
    private static HttpURLConnection openFollowing(String urlStr, int connectTimeout, int readTimeout,
                                                  String accept, String ua) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout(readTimeout);
            c.setInstanceFollowRedirects(false);
            if (accept != null) c.setRequestProperty("Accept", accept);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            c.connect();
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.trim().isEmpty()) {
                    throw new IOException("HTTP " + code + " 缺少 Location");
                }
                // 用 URL(base, location) 解析，兼容服务端给相对路径
                current = new URL(new URL(current), loc.trim()).toString();
                Log.d(TAG, "redirect(" + code + ") -> " + current);
                continue;
            }
            return c;
        }
        throw new IOException("重定向次数过多");
    }

    /** 响应是否可当 JSON 解析：503 熔断返回的是 HTML 错误页 */
    private static boolean isJson(Resp r) {
        if (r == null) return false;
        if (r.contentType.contains("json")) return true;
        String b = r.body == null ? "" : r.body.trim();
        return b.startsWith("{") || b.startsWith("[");
    }

    private static JSONObject jsonOf(Resp r) {
        if (!isJson(r)) return null;
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 版本号比较：按段做数值比较（非字符串），缺失段补 0。
     * 与 m1SuHub 服务端同一规则，保证两边判断一致（1.10.0 &gt; 1.9.0）。
     *
     * 非规范标签（如 1.4.1-beta）按服务端规范本不该出现，这里做容错：
     * 段内只取前导数字，其余忽略。否则带标签的版本会被判成"永远有更新"，
     * 每次启动都弹窗。
     *
     * @return 负数：current &lt; latest；0：相等；正数：current &gt; latest
     */
    public static int compareVersion(String current, String latest) {
        int[] a = parseSegments(current);
        int[] b = parseSegments(latest);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** 每段只取前导数字，忽略 -beta / _rc 之类的非规范后缀 */
    private static int[] parseSegments(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split("[.]");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = leadingInt(parts[i]);
        }
        return out;
    }

    /** 取一段字符串的前导数字；没有数字（纯后缀）时返回 0 */
    private static int leadingInt(String seg) {
        if (seg == null) return 0;
        String s = seg.trim();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(s.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 把后台线程的回切回主线程执行 */
    private void post(final ProgressListener l,
                      final java.util.function.Consumer<ProgressListener> action) {
        if (l == null) return;
        main.post(() -> action.accept(l));
    }
}
