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
 * 五周课表窗口存储与周次计算
 *
 * 数据模型(SharedPreferences "schedule_store"/"data"，JSON 覆盖写=删除旧数据):
 * {
 *   week1Monday: 学期第1周周一 00:00 epoch毫秒(基准日)
 *   windowStart/windowEnd: 当前负责的5周窗口
 *   totalWeeks: 学期总周数
 *   updatedAt: 抓取时间
 *   courses: [ {day,row,name,teacher,location,weeks} ]
 * }
 */
public class ScheduleStore {

    private static final String PREFS = "schedule_store";
    private static final String KEY_DATA = "data";
    private static final long DAY = 86400000L;
    private static final int DATA_VERSION = 12; // 递增以清除旧版/测试数据（v12: 新增教学班级字段，正式版）

    private final SharedPreferences prefs;
    private long week1Monday = 0;
    private int windowStart = 0, windowEnd = 0, totalWeeks = 0;
    private JSONArray courses = null;
    private String[] periodTimes = null; // 节次时间表（每行2节课的时间段）

    public ScheduleStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    // ====== 读取 ======

    private void load() {
        try {
            String json = prefs.getString(KEY_DATA, null);
            if (json == null) return;
            JSONObject obj = new JSONObject(json);
            // 版本校验：旧版/测试数据自动清除
            if (obj.optInt("version", 1) < DATA_VERSION) {
                prefs.edit().remove(KEY_DATA).apply();
                return;
            }
            week1Monday = obj.optLong("week1Monday", 0);
            windowStart = obj.optInt("windowStart", 0);
            windowEnd = obj.optInt("windowEnd", 0);
            totalWeeks = obj.optInt("totalWeeks", 0);
            courses = obj.optJSONArray("courses");
            JSONArray pt = obj.optJSONArray("periodTimes");
            if (pt != null && pt.length() > 0) {
                periodTimes = new String[pt.length()];
                for (int i = 0; i < pt.length(); i++) periodTimes[i] = pt.optString(i, "");
            }
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
        try {
            String json = prefs.getString(KEY_DATA, null);
            if (json == null) return null;
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("version", 1) < DATA_VERSION) return null;
            JSONArray arr = obj.optJSONArray("periodTimes");
            if (arr == null || arr.length() == 0) return null;
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i, "");
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public void clear() {
        prefs.edit().remove(KEY_DATA).apply();
        week1Monday = 0;
        windowStart = 0;
        windowEnd = 0;
        totalWeeks = 0;
        courses = null;
    }

    // ====== 保存（覆盖旧数据 = 删除上次课表） ======

    /**
     * @param parsedWeek      教务页面解析到的当前周次（0=解析失败）
     * @param parsedTotal     教务页面解析到的学期总周数（0=解析失败）
     * @param week1MondayOverride 从教学周历接口获取的第1周周一epoch毫秒（0=未获取到）
     * @param newPeriodTimes  从教务作息数据解析的节次时间段（null=未解析到）
     * @param oldStart        旧窗口起始周（0=无旧数据）
     * @param oldEnd          旧窗口结束周（0=无旧数据）
     * @param ending          本次更新是否由窗口末尾触发（提前更新后五周）
     */
    public void save(Context ctx, int parsedWeek, int parsedTotal, long week1MondayOverride,
                     String[] newPeriodTimes, JSONArray newCourses, int oldStart, int oldEnd, boolean ending) {
        try {
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
            int total = parsedTotal > 0 ? parsedTotal : (totalWeeks > 0 ? totalWeeks : todayW + 19);

            // 新窗口起始规则：
            // 首次 = 今天周次；已过期 = 从今天起新五周；
            // 窗口末尾提前更新 = 旧窗口下一周起的后五周；周期内普通刷新 = 窗口不变
            int start;
            if (oldEnd > 0) {
                if (todayW > oldEnd) start = Math.max(todayW, 1);
                else if (ending) start = oldEnd + 1;
                else start = Math.max(oldStart, 1);
            } else {
                start = Math.max(todayW, 1);
            }
            int end = Math.min(start + 4, Math.max(start, total));

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

            prefs.edit().putString(KEY_DATA, obj.toString()).apply();
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

    /** 窗口末周的最后三天提醒时刻：周五/六/日（过去的不返回） */
    public long[] reminderTimes() {
        if (windowEnd <= 0 || week1Monday <= 0) return new long[0];
        long endMonday = mondayOf(windowEnd);
        long now = System.currentTimeMillis();
        long[] all = {endMonday + 4 * DAY, endMonday + 5 * DAY, endMonday + 6 * DAY};
        int n = 0;
        for (long t : all) if (t > now) n++;
        long[] out = new long[n];
        int i = 0;
        for (long t : all) if (t > now) out[i++] = t;
        return out;
    }

    /** 今天是否处于窗口末周最后三天（周五及以后） */
    public boolean isEndingWindow() {
        int tw = todayWeek();
        return tw != 0 && windowEnd != 0 && tw == windowEnd
            && startOfToday() >= mondayOf(windowEnd) + 4 * DAY;
    }

    /**
     * 是否需要刷新抓取：
     * 今天已超出窗口（新周期开始），或处于窗口末周周五及以后（提前3天更新后五周）
     */
    public boolean needsRefresh() {
        int tw = todayWeek();
        if (tw == 0 || windowEnd == 0) return false;
        return tw > windowEnd || isEndingWindow();
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

    /**
     * 判断某课在第week周是否上课
     * 支持格式： "1-16周"/"1-16(周)"/"3,5,7(周)"/"11(周)"/"单周"/"1-16周(单)"/"3-18(周)[01-02节]" 等
     */
    public static boolean weekMatches(String weeksText, int week) {
        if (weeksText == null || weeksText.trim().isEmpty()) return true;
        String t = weeksText.trim();
        // 去掉节次信息 [01-02节]
        t = t.replaceAll("\\[.*?\\]", "").trim();
        if (t.isEmpty()) return true;

        // 范围 "1-16周" 或 "1-16(周)"
        Matcher m = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)").matcher(t);
        if (m.find()) {
            int s = Integer.parseInt(m.group(1)), e = Integer.parseInt(m.group(2));
            if (week < s || week > e) return false;
            if (t.contains("单")) return week % 2 == 1;
            if (t.contains("双")) return week % 2 == 0;
            return true;
        }
        // 单周/双周
        if (t.contains("单周") || t.contains("单")) return week % 2 == 1;
        if (t.contains("双周") || t.contains("双")) return week % 2 == 0;
        // "第X周"
        Matcher m3 = Pattern.compile("第\\s*(\\d+)\\s*周").matcher(t);
        if (m3.find()) return Integer.parseInt(m3.group(1)) == week;
        // 逗号列表 "3,5,7,9(周)"
        if (t.contains(",") || t.contains("，")) {
            Matcher m2 = Pattern.compile("(\\d+)").matcher(t);
            while (m2.find()) {
                if (Integer.parseInt(m2.group(1)) == week) return true;
            }
            return false;
        }
        // 单个周次 "11(周)" 或 "11周"
        Matcher m4 = Pattern.compile("^(\\d+)").matcher(t);
        if (m4.find()) return Integer.parseInt(m4.group(1)) == week;
        return true; // 无法解析时保守显示
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
