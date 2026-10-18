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
    private static final String KEY_REMINDERS = "course_reminders";  // 已选提醒课程 JSON Set
    private static final String KEY_ADVANCE = "reminder_advance";   // 全局提前量（分钟）
    private static final String KEY_THEME = "theme";                // 课表主题 light/dark
    private static final String KEY_WIDGET_THEME = "widget_theme";   // 小组件配色 light/dark
    private static final String KEY_BG = "bg_image";                // 自定义背景图文件名
    private static final long DAY = 86400000L;
    private static final int DATA_VERSION = 13; // 递增以清除旧版/测试数据（v13: 清除拖动测试期写入的坏 overrides）
    private static final int DEFAULT_ADVANCE = 15; // 默认提前15分钟

    private final SharedPreferences prefs;
    /** 内存中的完整数据对象：所有读取都走内存，避免每次重新解析 JSON */
    private JSONObject data = null;
    private long week1Monday = 0;
    private int windowStart = 0, windowEnd = 0, totalWeeks = 0;
    private JSONArray courses = null;
    private String[] periodTimes = null;      // 节次时间表（每行2节课的时间段）
    private JSONObject overrides = null;      // 拖动位置覆盖表
    private java.util.Set<String> reminderKeys = new java.util.HashSet<>();
    private int advanceMinutes = DEFAULT_ADVANCE;
    private String theme = "light";
    private String bgImage = "";

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
        advanceMinutes = prefs.getInt(KEY_ADVANCE, DEFAULT_ADVANCE);
        theme = prefs.getString(KEY_THEME, "light");
        bgImage = prefs.getString(KEY_BG, "");
        try {
            String rj = prefs.getString(KEY_REMINDERS, null);
            if (rj != null) {
                JSONArray arr = new JSONArray(rj);
                for (int i = 0; i < arr.length(); i++) reminderKeys.add(arr.optString(i, ""));
            }
        } catch (Exception ignored) {
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
     * 清空全部本地数据：课表、提醒勾选、提前量、主题、背景图引用。
     * 用于「全新安装后清理上一版本残留数据」，清空后需重新登录抓取。
     */
    public void clearAll() {
        clear();
        prefs.edit()
                .remove(KEY_REMINDERS)
                .remove(KEY_ADVANCE)
                .remove(KEY_THEME)
                .remove(KEY_WIDGET_THEME)
                .remove(KEY_BG)
                .apply();
        periodTimes = null;
        reminderKeys.clear();
        advanceMinutes = DEFAULT_ADVANCE;
        theme = "light";
        bgImage = "";
    }

    // ====== 课程提醒 ======

    /** 获取全局提前量（分钟），默认15 */
    public int getAdvanceMinutes() {
        return advanceMinutes;
    }

    /** 设置全局提前量（分钟） */
    public void setAdvanceMinutes(int minutes) {
        advanceMinutes = minutes;
        prefs.edit().putInt(KEY_ADVANCE, minutes).apply();
    }

    /** 获取已选提醒课程的 key 集合（返回副本，读内存缓存） */
    public java.util.Set<String> getReminderKeys() {
        return new java.util.HashSet<>(reminderKeys);
    }

    /** 保存提醒课程 key 集合（全量覆盖） */
    public void saveReminderKeys(java.util.Set<String> keys) {
        reminderKeys = new java.util.HashSet<>(keys);
        JSONArray arr = new JSONArray();
        for (String k : reminderKeys) arr.put(k);
        prefs.edit().putString(KEY_REMINDERS, arr.toString()).apply();
    }

    /** 判断某课程是否已设提醒（纯内存查表） */
    public boolean isReminder(String courseKey) {
        return reminderKeys.contains(courseKey);
    }

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
                if (k.isEmpty() || d < 1 || d > 7 || r < 1 || r > 5) continue;
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

    /** 清除所有拖动位置覆盖（恢复课程在课表中的原始位置） */
    public void clearOverrides() {
        if (data == null) return;
        data.remove("overrides");
        overrides = null;
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
     * 导致这些周次整门课从课表、小组件、课前提醒中一起消失。
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

    // ====== 日期格式化 ======

    public static String fmtDate(long millis) {
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(millis));
    }

    public static String fmtWeekday(long millis) {
        String[] w = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return w[c.get(Calendar.DAY_OF_WEEK) - 1];
    }
}
