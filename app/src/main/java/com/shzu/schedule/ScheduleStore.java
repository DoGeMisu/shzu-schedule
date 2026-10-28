package com.shzu.schedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 课表本地存储与周次计算
 *
 * 数据模型(SharedPreferences "schedule_store"/"data"，JSON 覆盖写=删除旧数据):
 * {
 *   week1Monday: 学期第1周周一 00:00 epoch毫秒(基准日)
 *   windowStart/windowEnd: 负责的周次范围(整学期固定为 1..totalWeeks)
 *   totalWeeks: 学期总周数
 *   updatedAt: 抓取时间
 *   courses: [ {day,row,name,teacher,location,weeks} ]
 *   periodTimes: [ "10:00-11:50", ... ] 节次作息时间表
 *   overrides: { courseKey: {d,r} }  用户拖动调整的位置覆盖
 */
public class ScheduleStore {

    private static final String PREFS = "schedule_store";
    private static final String KEY_DATA = "data";
    private static final String KEY_THEME = "theme";                // 课表主题 light/dark
    private static final String KEY_WIDGET_THEME = "widget_theme";   // 小组件配色 light/dark
    private static final String KEY_BG = "bg_image";                // 自定义背景图文件名
    private static final String KEY_SPLASH_ANIM = "splash_anim";    // 是否播放开屏动画（关闭则启动直达课表）
    /**
     * 界面字体缩放百分比（80~150，默认 100）。
     * 页面里所有字号都写死在 CSS 里，渲染后由 JS 按这个比例统一放大/缩小，
     * 所以它必须参与周课表 HTML 的缓存指纹（见 MainActivity.scheduleStateSignature）。
     */
    private static final String KEY_FONT_SCALE = "font_scale";
    /**
     * 用户自己添加的课程。与教务抓取的数据完全分离：
     * 刷新/覆盖/清空教务课表都不会影响这里，反之亦然。
     */
    private static final String KEY_CUSTOM = "custom_courses";
    /** 位置冲突时哪一路课程显示在前面：school / custom */
    private static final String KEY_CONFLICT_PRIORITY = "conflict_priority";
    /** 上次自动检查更新的时间（epoch ms），用于「每天最多一次」节流 */
    private static final String KEY_LAST_CHECK = "update_last_check";
    /** 自动检查期间流过的服务端公共内容（公告/维护通知），可能为空串 */
    private static final String KEY_LAST_CONTENT = "update_last_content";
    /**
     * 检测到新版本时先存下来的更新内容，等安装完成、下次启动时弹出。
     * 这样「自动更新完成后弹更新内容」不依赖跨版本保留任何安装产物。
     */
    private static final String KEY_PENDING_LOG = "update_pending_log";
    private static final String KEY_PENDING_VER = "update_pending_ver";
    /** 已经展示过更新内容弹窗的版本号，避免每次启动重复弹 */
    private static final String KEY_LOG_SHOWN_VER = "update_log_shown_ver";
    /**
     * 学生资料（姓名 / 学号）：进入教务系统时顺带读取。
     * **只存本机**，不上传任何服务器；退出登录时一并清除。
     */
    private static final String KEY_STU_NAME = "student_name";
    private static final String KEY_STU_ID = "student_id";
    /** 资料归属账号（学号） */
    private static final String KEY_STU_OWNER = "student_owner";
    /** 自定义课程条数上限，防止误操作写入海量数据拖慢渲染 */
    public static final int MAX_CUSTOM_COURSES = 200;
    private static final long DAY = 86400000L;
    private static final int DATA_VERSION = 13; // 递增以清除旧版/测试数据（v13: 清除拖动测试期写入的坏 overrides）

    private final SharedPreferences prefs;
    /** 内存中的完整数据对象：所有读取都走内存，避免每次重新解析 JSON */
    private JSONObject data = null;
    private long week1Monday = 0;
    private int windowStart = 0, windowEnd = 0, totalWeeks = 0;
    private JSONArray courses = null;
    private String[] periodTimes = null;      // 节次时间表（每行2节课的时间段）
    private JSONObject overrides = null;      // 拖动位置覆盖表
    private String theme = "light";
    private String bgImage = "";
    private boolean splashAnimEnabled = true;
    /** 界面字体缩放百分比（80~150，默认 100） */
    private int fontScale = 100;
    /** 自定义课程（独立通道，结构见 normalizeCustom） */
    private JSONArray customCourses = new JSONArray();
    /** 冲突时优先显示的来源：school(默认) / custom */
    private String conflictPriority = "school";
    /** 自动更新相关轻量配置（与课表数据解耦，clear() 清课表时不动这里） */
    private long updateLastCheck = 0;
    private String updateLastContent = "";
    private String pendingLogVer = "";
    private String pendingLog = "";
    private String logShownVer = "";
    /** 学生资料（仅本机） */
    private String studentName = "";
    private String studentId = "";
    private String studentOwner = "";

    public ScheduleStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    // ====== 读取 ======

    private void load() {
        // 一次解析，全部字段留在内存（后续读取不再碰磁盘/JSON）
        try {
            String json = prefs.getString(KEY_DATA, null);
            if (json != null) {
                JSONObject obj = new JSONObject(json);
                if (obj.optInt("version", 1) >= DATA_VERSION) {
                    data = obj;
                    week1Monday = obj.optLong("week1Monday", 0);
                    windowStart = obj.optInt("windowStart", 0);
                    windowEnd = obj.optInt("windowEnd", 0);
                    totalWeeks = obj.optInt("totalWeeks", 0);
                    courses = obj.optJSONArray("courses");
                    overrides = obj.optJSONObject("overrides");
                    JSONArray pt = obj.optJSONArray("periodTimes");
                    if (pt != null && pt.length() > 0) {
                        periodTimes = new String[pt.length()];
                        for (int i = 0; i < pt.length(); i++) periodTimes[i] = pt.optString(i, "");
                    }
                } else {
                    // 版本过期：旧版/测试数据自动清除
                    prefs.edit().remove(KEY_DATA).apply();
                }
            }
        } catch (Exception ignored) {
        }
        // 轻量配置项一并读入内存
        theme = prefs.getString(KEY_THEME, "light");
        bgImage = prefs.getString(KEY_BG, "");
        splashAnimEnabled = prefs.getBoolean(KEY_SPLASH_ANIM, true);
        fontScale = clampFontScale(prefs.getInt(KEY_FONT_SCALE, 100));
        conflictPriority = prefs.getString(KEY_CONFLICT_PRIORITY, "school");
        // 自动更新：节流时间戳、上次公共内容、待弹出的更新内容
        updateLastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        updateLastContent = prefs.getString(KEY_LAST_CONTENT, "");
        pendingLogVer = prefs.getString(KEY_PENDING_VER, "");
        pendingLog = prefs.getString(KEY_PENDING_LOG, "");
        logShownVer = prefs.getString(KEY_LOG_SHOWN_VER, "");
        studentName = prefs.getString(KEY_STU_NAME, "");
        studentId = prefs.getString(KEY_STU_ID, "");
        studentOwner = prefs.getString(KEY_STU_OWNER, "");
        // 自定义课程走独立键，与 KEY_DATA 互不影响
        try {
            String cj = prefs.getString(KEY_CUSTOM, null);
            if (cj != null) customCourses = new JSONArray(cj);
        } catch (Exception ignored) {
            customCourses = new JSONArray();
        }
    }

    /** 把内存数据写回磁盘（仅在变更时调用） */
    private void persist() {
        try {
            if (data != null) prefs.edit().putString(KEY_DATA, data.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public boolean hasData() {
        return courses != null && courses.length() > 0;
    }

    public JSONArray getAllCourses() {
        return courses == null ? new JSONArray() : courses;
    }

    public int getWindowStart() { return windowStart; }
    public int getWindowEnd() { return windowEnd; }
    public int getTotalWeeks() { return totalWeeks; }
    public long getWeek1Monday() { return week1Monday; }

    /** 节次时间表（从教务作息数据获取），如 ["10:00-11:50",...]；未存时返回null */
    public String[] getPeriodTimes() {
        return periodTimes;
    }

    public void clear() {
        prefs.edit().remove(KEY_DATA).apply();
        data = null;
        overrides = null;
        week1Monday = 0;
        windowStart = 0;
        windowEnd = 0;
        totalWeeks = 0;
        courses = null;
    }

    /**
     * 清空全部本地数据：课表、主题、背景图引用、自定义课程。
     * 用于「全新安装后清理上一版本残留数据」，清空后需重新登录抓取。
     */
    public void clearAll() {
        clear();
        prefs.edit()
                // 提醒功能已移除，顺手清掉旧版本可能留下的键
                .remove("course_reminders")
                .remove("reminder_advance")
                .remove(KEY_THEME)
                .remove(KEY_WIDGET_THEME)
                .remove(KEY_BG)
                .remove(KEY_CUSTOM)
                .remove(KEY_CONFLICT_PRIORITY)
                .remove(KEY_STU_NAME)
                .remove(KEY_STU_ID)
                .remove(KEY_STU_OWNER)
                .apply();
        periodTimes = null;
        theme = "light";
        bgImage = "";
        customCourses = new JSONArray();
        conflictPriority = "school";
    }

    // ====== 课程位置（拖动覆盖） ======

    /** 用户拖动产生的位置覆盖表（courseKey → {d,r}），无则返回null；读内存缓存 */
    public JSONObject getOverrides() {
        return overrides;
    }

    /** 合并写入位置覆盖：moves = [{k,d,r}]，k为courseKey，d为1..7(周一..周日)，r为1..5(大节) */
    public void saveOverrides(JSONArray moves) {
        try {
            if (data == null) return;
            JSONObject ov = overrides;
            if (ov == null) ov = new JSONObject();
            for (int i = 0; i < moves.length(); i++) {
                JSONObject m = moves.optJSONObject(i);
                if (m == null) continue;
                String k = m.optString("k", "");
                int d = m.optInt("d", 0), r = m.optInt("r", 0);
                if (k.isEmpty() || d < 1 || d > 7 || r < 1 || r > 8) continue;
                JSONObject pos = new JSONObject();
                pos.put("d", d);
                pos.put("r", r);
                ov.put(k, pos);
            }
            overrides = ov;
            data.put("overrides", ov);
            persist();
        } catch (Exception ignored) {
        }
    }

    /** 主题：light / dark */
    public String getTheme() {
        return theme;
    }

    public void setTheme(String mode) {
        theme = (mode == null || mode.isEmpty()) ? "light" : mode;
        prefs.edit().putString(KEY_THEME, theme).apply();
    }

    /**
     * 桌面小组件配色：light / dark。
     * 独立于 App 主题，由用户在小组件上直接点按钮切换。
     */
    public String getWidgetTheme() {
        return prefs.getString(KEY_WIDGET_THEME, "light");
    }

    public void setWidgetTheme(String mode) {
        String v = "dark".equals(mode) ? "dark" : "light";
        prefs.edit().putString(KEY_WIDGET_THEME, v).apply();
    }

    public String toggleWidgetTheme() {
        String next = "dark".equals(getWidgetTheme()) ? "light" : "dark";
        setWidgetTheme(next);
        return next;
    }

    /** 自定义背景图文件名（空字符串=未设置） */
    public String getBgImage() {
        return bgImage;
    }

    public void setBgImage(String fileName) {
        bgImage = (fileName == null) ? "" : fileName;
        prefs.edit().putString(KEY_BG, bgImage).apply();
    }

    /** 是否播放开屏动画（默认开启）；关闭后启动直接进入课表 */
    public boolean isSplashAnimEnabled() {
        return splashAnimEnabled;
    }

    public void setSplashAnimEnabled(boolean enabled) {
        splashAnimEnabled = enabled;
        prefs.edit().putBoolean(KEY_SPLASH_ANIM, enabled).apply();
    }

    // ====== 界面字体缩放 ======
    //
    // 只影响 HTML 里的字号，不动布局尺寸：卡片高度由网格 1fr 分配，
    // 放大字号只会让字更满，不会把卡片撑变形。

    /** 字体缩放百分比，80~150，默认 100 */
    public int getFontScale() {
        return fontScale;
    }

    public void setFontScale(int percent) {
        fontScale = clampFontScale(percent);
        prefs.edit().putInt(KEY_FONT_SCALE, fontScale).apply();
    }

    /** 越界值一律夹到 80~150，防止旧数据或误传把页面字撑爆 */
    private static int clampFontScale(int v) {
        if (v < 80) return 80;
        if (v > 150) return 150;
        return v;
    }

    // ====== 自定义课程（独立通道） ======
    //
    // 结构与教务课程保持字段兼容（name/teacher/location/weeks/day/row），
    // 便于复用周次判定与渲染；额外多出 id/color/remark 三项。
    // 唯一键由 MainActivity.courseKey 统一给出，带 "custom:" 前缀，避免与教务课程撞键。

    /** 全部自定义课程（返回副本，外部修改不影响内存） */
    public JSONArray getCustomCourses() {
        try {
            return new JSONArray(customCourses.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public int getCustomCourseCount() {
        return customCourses.length();
    }

    /** 按 id 取一条（返回副本），找不到返回 null */
    public JSONObject getCustomCourse(String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = 0; i < customCourses.length(); i++) {
            JSONObject o = customCourses.optJSONObject(i);
            if (o == null || !id.equals(o.optString("id", ""))) continue;
            try {
                return new JSONObject(o.toString());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 第 week 周有课的自定义课程 */
    public JSONArray customCoursesForWeek(int week) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < customCourses.length(); i++) {
            JSONObject c = customCourses.optJSONObject(i);
            if (c == null) continue;
            if (weekMatches(c.optString("weeks", ""), week)) out.put(c);
        }
        return out;
    }

    /**
     * 新增或更新一条自定义课程。
     *
     * @param input 表单数据；id 为空 = 新增，id 已存在 = 覆盖更新
     * @return 保存后的课程对象；校验失败返回 null
     */
    public JSONObject saveCustomCourse(JSONObject input) {
        if (input == null) return null;
        JSONObject c = normalizeCustom(input);
        if (c == null) return null;

        String id = c.optString("id", "");
        int hit = -1;
        for (int i = 0; i < customCourses.length(); i++) {
            JSONObject o = customCourses.optJSONObject(i);
            if (o != null && !id.isEmpty() && id.equals(o.optString("id", ""))) {
                hit = i;
                break;
            }
        }
        if (hit < 0) {
            if (customCourses.length() >= MAX_CUSTOM_COURSES) return null;
            if (id.isEmpty()) {
                id = "c" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);
                try { c.put("id", id); } catch (Exception ignored) {}
            }
            customCourses.put(c);
        } else {
            try { customCourses.put(hit, c); } catch (Exception ignored) {}
        }
        persistCustom();
        return c;
    }

    /** 删除一条自定义课程，返回是否删除成功 */
    public boolean removeCustomCourse(String id) {
        if (id == null || id.isEmpty()) return false;
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < customCourses.length(); i++) {
            JSONObject o = customCourses.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id", ""))) { removed = true; continue; }
            next.put(o);
        }
        if (removed) {
            customCourses = next;
            persistCustom();
        }
        return removed;
    }

    public void clearCustomCourses() {
        customCourses = new JSONArray();
        persistCustom();
    }

    private void persistCustom() {
        try {
            prefs.edit().putString(KEY_CUSTOM, customCourses.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 校验并规范化一条自定义课程。
     * 课程名必填；星期 1..7、节次 1..8 越界即判失败（位置决定它画在哪一格）。
     */
    private static JSONObject normalizeCustom(JSONObject in) {
        try {
            String name = in.optString("name", "").replaceAll("\\s+", " ").trim();
            if (name.isEmpty()) return null;
            if (name.length() > 30) name = name.substring(0, 30);
            int day = in.optInt("day", 0);
            int row = in.optInt("row", 0);
            if (day < 1 || day > 7 || row < 1 || row > 8) return null;

            JSONObject c = new JSONObject();
            c.put("id", in.optString("id", ""));
            c.put("name", name);
            c.put("teacher", clip(in.optString("teacher", ""), 20));
            c.put("location", clip(in.optString("location", ""), 30));
            c.put("weeks", clip(in.optString("weeks", ""), 40));
            c.put("remark", clip(in.optString("remark", ""), 60));
            c.put("day", day);
            c.put("row", row);
            String color = in.optString("color", "").trim();
            if (!color.matches("^#[0-9a-fA-F]{6}$")) color = "";
            c.put("color", color);
            // 是否「有意叠加」在已有课的格子上（加课时该格已有课 → true）。
            // 渲染时据此区分：有意叠加用琥珀色描边，教务同步后撞上的才用红色报警。
            c.put("overlap", in.optBoolean("overlap", false));
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** 冲突时优先显示哪一路：school / custom */
    public String getConflictPriority() {
        return "custom".equals(conflictPriority) ? "custom" : "school";
    }

    public void setConflictPriority(String v) {
        conflictPriority = "custom".equals(v) ? "custom" : "school";
        prefs.edit().putString(KEY_CONFLICT_PRIORITY, conflictPriority).apply();
    }

    // ====== 自动更新 ======

    /**
     * 上次自动检查时间（epoch ms）。
     * v1.4.5 起不再用它做 24 小时节流——改成「每次进入软件都检查一次」，
     * 这个时间戳只作诊断用途（记录最近一次成功检查的时刻）。
     */
    public long getUpdateLastCheck() {
        return updateLastCheck;
    }

    public void setUpdateLastCheck(long ts) {
        updateLastCheck = ts;
        prefs.edit().putLong(KEY_LAST_CHECK, ts).apply();
    }

    /** 上次自动检查时服务端返回的公共内容 */
    public String getUpdateLastContent() {
        return updateLastContent == null ? "" : updateLastContent;
    }

    public void setUpdateLastContent(String content) {
        updateLastContent = content == null ? "" : content;
        prefs.edit().putString(KEY_LAST_CONTENT, updateLastContent).apply();
    }

    /** 暂存待弹出的更新内容（版本号 + 日志），安装完成后由启动流程消费 */
    public void setPendingUpdateLog(String version, String log) {
        pendingLogVer = version == null ? "" : version;
        pendingLog = log == null ? "" : log;
        prefs.edit()
                .putString(KEY_PENDING_VER, pendingLogVer)
                .putString(KEY_PENDING_LOG, pendingLog)
                .apply();
    }

    public String getPendingUpdateLog() {
        return pendingLog == null ? "" : pendingLog;
    }

    public String getPendingUpdateVersion() {
        return pendingLogVer == null ? "" : pendingLogVer;
    }

    /** 消费掉暂存的更新内容：已弹过就清掉，避免重复弹 */
    public void clearPendingUpdateLog() {
        setPendingUpdateLog("", "");
    }

    /** 已展示过更新内容弹窗的版本号 */
    public String getLogShownVersion() {
        return logShownVer == null ? "" : logShownVer;
    }

    // ====== 学生资料（仅本机保存，不上传）======

    public String getStudentName() {
        return studentName == null ? "" : studentName;
    }

    public String getStudentId() {
        return studentId == null ? "" : studentId;
    }

    /** 这份资料属于哪个登录账号（学号）：用于识别「换了账号/遗留数据」 */
    public String getStudentOwner() {
        return studentOwner == null ? "" : studentOwner;
    }

    /**
     * 写入学生资料，并记下它属于哪个账号。
     *
     * @param owner 当前登录账号（学号）；为空时不覆盖已有 owner，避免把归属洗掉
     */
    public void setStudentInfo(String name, String sid, String owner) {
        String n = name == null ? "" : name.replaceAll("\\s+", " ").trim();
        String i = sid == null ? "" : sid.replaceAll("\\s+", "").trim();
        String own = owner == null ? "" : owner.replaceAll("\\s+", "").trim();
        // 归属账号变了（换号/上次是别的账号或调试数据）→ 整份替换，不做增量合并，
        // 否则「只抓到学号」时会把上一个账号的姓名留在页面上。
        if (!own.isEmpty() && !own.equals(studentOwner)) {
            studentName = "";
            studentId = "";
        }
        if (!n.isEmpty()) studentName = n;
        if (!i.isEmpty()) studentId = i;
        if (!own.isEmpty()) studentOwner = own;
        prefs.edit()
                .putString(KEY_STU_NAME, studentName)
                .putString(KEY_STU_ID, studentId)
                .putString(KEY_STU_OWNER, studentOwner)
                .apply();
    }

    /** 退出登录时清除（与账号密码一起走） */
    public void clearStudentInfo() {
        studentName = "";
        studentId = "";
        studentOwner = "";
        prefs.edit().remove(KEY_STU_NAME).remove(KEY_STU_ID).remove(KEY_STU_OWNER).apply();
    }

    public void setLogShownVersion(String v) {
        logShownVer = v == null ? "" : v;
        prefs.edit().putString(KEY_LOG_SHOWN_VER, logShownVer).apply();
    }

    /**
     * 自定义课程指纹（进渲染缓存签名）。
     * 增删改任何一条都会改变它，从而让后台预热的周页面缓存失效。
     */
    public int getCustomCoursesHash() {
        int h = 1;
        for (int i = 0; i < customCourses.length(); i++) {
            JSONObject c = customCourses.optJSONObject(i);
            if (c == null) continue;
            // overlap 必须进指纹：否则「有意叠加」标记变化时，预热的周页面仍是旧样式
            h = 31 * h + (c.optString("id", "") + "|" + c.optString("name", "") + "|"
                    + c.optString("location", "") + "|" + c.optString("weeks", "") + "|"
                    + c.optString("color", "") + "|" + c.optInt("day", 0) + "|" + c.optInt("row", 0)
                    + "|" + (c.optBoolean("overlap", false) ? "o" : "-")).hashCode();
        }
        return h;
    }

    /** 教务 + 自定义的合并列表（桌面小组件用，它不关心课程来源） */
    public JSONArray allCoursesForWeek(int week) {
        JSONArray out = coursesForWeek(week);
        JSONArray cu = customCoursesForWeek(week);
        for (int i = 0; i < cu.length(); i++) {
            JSONObject o = cu.optJSONObject(i);
            if (o != null) out.put(o);
        }
        return out;
    }

    /**
     * 课表数据的轻量指纹。
     * 用于判断「后台预生成的周课表 HTML」是否仍然有效：课程增删改后指纹变化，
     * 旧缓存立即作废，避免把过期的课表当作新数据渲染出来。
     */
    public int getCoursesHash() {
        if (courses == null) return 0;
        int h = 1;
        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            h = 31 * h + (c.optString("name", "") + "|" + c.optString("teacher", "") + "|"
                    + c.optString("location", "") + "|" + c.optString("weeks", "") + "|"
                    + c.optInt("day", 0) + "|" + c.optInt("row", 0)).hashCode();
        }
        return h;
    }

    /** 清除所有拖动位置覆盖（恢复课程在课表中的原始位置） */
    public void clearOverrides() {
        if (data == null) return;
        data.remove("overrides");
        overrides = null;
        persist();
    }

    /**
     * 删除单门课程的拖动覆盖。
     * 编辑自定义课程时会改它自身的 day/row，若旧覆盖还留着，
     * 渲染时会再次盖回旧位置，表现为「改了位置却没生效」。
     */
    public void removeOverride(String key) {
        if (data == null || key == null || key.isEmpty() || overrides == null) return;
        if (!overrides.has(key)) return;
        overrides.remove(key);
        if (overrides.length() == 0) {
            data.remove("overrides");
            overrides = null;
        }
        persist();
    }

    // ====== 保存（覆盖旧数据 = 删除上次课表） ======

    /**
     * 保存整学期课表（覆盖旧数据）。
     *
     * @param parsedWeek          教务页面解析到的当前周次（0=解析失败）
     * @param parsedTotal         教务页面解析到的学期总周数（0=解析失败）
     * @param week1MondayOverride 从教学周历接口获取的第1周周一epoch毫秒（0=未获取到）
     * @param newPeriodTimes      从教务作息数据解析的节次时间段（null=未解析到）
     * @param newCourses          解析出的课程数组
     */
    public void save(Context ctx, int parsedWeek, int parsedTotal, long week1MondayOverride,
                     String[] newPeriodTimes, JSONArray newCourses) {
        try {
            // 刷新课表前先读出旧的位置覆盖表（用户拖动布局），保存时继续继承
            JSONObject oldOverrides = getOverrides();
            // 优先使用从教学周历接口获取的第1周周一日期
            if (week1MondayOverride > 0) {
                week1Monday = week1MondayOverride;
            } else if (parsedWeek > 0) {
                // 兜底：用页面解析到的当前周次反推第1周周一
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                long today = c.getTimeInMillis();
                int dow = c.get(Calendar.DAY_OF_WEEK);
                int dowMon = dow == Calendar.SUNDAY ? 7 : dow - 1;
                week1Monday = today - (parsedWeek - 1) * 7 * DAY - (dowMon - 1) * DAY;
            }

            int todayW = parsedWeek > 0 ? parsedWeek : todayWeek();
            int total = parsedTotal > 0 ? parsedTotal : Math.max(maxWeekOf(newCourses), todayW + 19);

            // 整学期课表：窗口固定为 1..总周数（不再按五周滚动）
            int start = 1;
            int end = Math.max(total, 1);

            JSONObject obj = new JSONObject();
            obj.put("version", DATA_VERSION);
            obj.put("week1Monday", week1Monday);
            obj.put("windowStart", start);
            obj.put("windowEnd", end);
            obj.put("totalWeeks", total);
            obj.put("updatedAt", System.currentTimeMillis());
            obj.put("courses", newCourses);
            if (newPeriodTimes != null && newPeriodTimes.length > 0) {
                JSONArray ptArr = new JSONArray();
                for (String t : newPeriodTimes) ptArr.put(t);
                obj.put("periodTimes", ptArr);
            }
            // 继承用户拖动产生的位置覆盖，避免刷新课表丢失自定义布局
            if (oldOverrides != null && oldOverrides.length() > 0) {
                obj.put("overrides", oldOverrides);
            }

            data = obj;
            overrides = obj.optJSONObject("overrides");
            persist();
            windowStart = start; windowEnd = end; totalWeeks = total;
            courses = newCourses;
            if (newPeriodTimes != null && newPeriodTimes.length > 0) periodTimes = newPeriodTimes;
        } catch (Exception ignored) {
        }
    }

    // ====== 周次与日期换算 ======

    /** 今天所在学期周次（无基准返回0） */
    public int todayWeek() {
        if (week1Monday <= 0) return 0;
        long today = startOfToday();
        long diff = today - week1Monday;
        if (diff < 0) return 1;
        return (int) (diff / (7 * DAY)) + 1;
    }

    /** 第week周的周一 00:00 epoch */
    public long mondayOf(int week) {
        return week1Monday + (week - 1) * 7 * DAY;
    }

    private static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ====== 按周过滤课程 ======

    /** 返回第week周有课的课程数组 */
    public JSONArray coursesForWeek(int week) {
        JSONArray out = new JSONArray();
        if (courses == null) return out;
        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            String weeks = c.optString("weeks", "");
            if (weekMatches(weeks, week)) out.put(c);
        }
        return out;
    }

    /** 从课程数据推断最大周次（总周数兜底用） */
    public static int maxWeekOf(JSONArray list) {
        int max = 0;
        if (list == null) return 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c == null) continue;
            String w = c.optString("weeks", "").replaceAll("\\[.*?\\]", "");
            Matcher m = Pattern.compile("(\\d+)").matcher(w);
            while (m.find()) {
                try {
                    int v = Integer.parseInt(m.group(1));
                    if (v > max && v <= 40) max = v;
                } catch (Exception ignored) {
                }
            }
        }
        return max;
    }

    /**
     * 判断某课在第week周是否上课
     * 支持格式： "1-16周"/"1-16(周)"/"3,5,7(周)"/"11(周)"/"单周"/"1-16周(单)"/"3-18(周)[01-02节]"
     * 以及多段混合："3-4,6-7(周)"、"3,5-10(周)"、"3-6,8-9,12-13(周)"
     *
     * 多段周次必须逐段判定、任一段命中即算上课。旧实现用整体正则找第一个区间就 return，
     * 会把 "3-6,8-9,12-13" 截成 "3-6"、"3,5-10" 截成 "5-10"，
     * 导致这些周次整门课从课表和桌面小组件中一起消失。
     */
    public static boolean weekMatches(String weeksText, int week) {
        if (weeksText == null || weeksText.trim().isEmpty()) return true;
        // 去掉节次信息 [01-02节]，并把全角分隔符统一成半角逗号
        String t = weeksText.replaceAll("\\[.*?\\]", " ")
                .replace('，', ',').replace('、', ',').replace('；', ',').replace(';', ',')
                .trim();
        if (t.isEmpty()) return true;

        boolean matched = false;
        for (String raw : t.split(",")) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            // 区间段："3-16" / "3~16" / "3至16"
            Matcher r = Pattern.compile("(\\d+)\\s*[-~至]\\s*(\\d+)").matcher(seg);
            if (r.find()) {
                int from = Integer.parseInt(r.group(1));
                int to = Integer.parseInt(r.group(2));
                if (to < from) { int tmp = from; from = to; to = tmp; }
                if (week >= from && week <= to) { matched = true; break; }
                continue;
            }
            // 单值段："9(周)" / "第3周" / "11"
            Matcher n = Pattern.compile("(\\d+)").matcher(seg);
            if (n.find() && Integer.parseInt(n.group(1)) == week) { matched = true; break; }
        }

        // 整串不含任何数字（如"单周"/"双周"）：不做区间限制，仅按单双判断
        if (!matched && Pattern.compile("\\d").matcher(t).find()) return false;

        if (t.contains("单") && week % 2 == 0) return false;
        if (t.contains("双") && week % 2 == 1) return false;
        return true;
    }

    /**
     * 课程唯一键。拖动 overrides 等多处调用点必须给出同一结果，
     * 因此统一收在这里，避免 MainActivity / NextCourse 各写一份后漂移。
     *
     * 教务课程：名称|教师|周次|day|row（历史格式，改动会导致已存拖动位置失效，不要动）
     * 自定义课程：custom:id —— 用稳定 id 而不是内容哈希，
     *            这样改课名/地点后拖动位置仍然跟着这门课走。
     */
    public static String courseKey(JSONObject c) {
        if (c == null) return "";
        String id = c.optString("id", "");
        if (!id.isEmpty()) return "custom:" + id;
        return c.optString("name", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("teacher", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("weeks", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optInt("day", 0)
            + "|" + c.optInt("row", 0);
    }

    // ====== 日期格式化 ======

    public static String fmtDate(long millis) {        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(millis));
    }

    public static String fmtWeekday(long millis) {
        String[] w = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return w[c.get(Calendar.DAY_OF_WEEK) - 1];
    }
}
