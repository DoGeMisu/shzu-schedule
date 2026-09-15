package com.shzu.schedule;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * 「下一节课」计算：给桌面小组件与内部提示共用。
 *
 * 数据来源与课表页保持一致：
 *  - 课程必须落在当前周次（ScheduleStore.weekMatches）
 *  - 位置要应用拖动 overrides，否则用户拖过的课会在小组件里显示错位置
 */
public final class NextCourse {

    private NextCourse() {}

    /** 一节课的展示数据 */
    public static final class Item {
        public String name = "";
        public String location = "";
        public String teacher = "";
        /** 形如 "10:00" */
        public String startTime = "";
        /** 形如 "11:50" */
        public String endTime = "";
        /** 1=周一 ... 7=周日 */
        public int day = 0;
        /** 1..5 大节 */
        public int row = 0;
        /** 周次 */
        public int week = 0;
        /** 是否就是今天 */
        public boolean today = false;
        /** 距开课的分钟数；已在授课中为负 */
        public long minutesUntil = 0;
        /** 是否正在上课 */
        public boolean ongoing = false;
        /** 课程色（与课表页同一套分配规则，形如 "#FF6B6B"） */
        public String color = "#667eea";
        /** 距今第几天：0=今天，1=明天…… */
        public int dayOffset = 0;

        /** "10:00-11:50" */
        public String timeRange() {
            if (startTime.isEmpty()) return "";
            return endTime.isEmpty() ? startTime : startTime + "-" + endTime;
        }
    }

    private static final String[] DEFAULT_TIMES = {
        "10:00-11:50", "12:00-13:50", "14:00-15:50", "16:00-17:50", "18:00-19:50"
    };

    /**
     * 课程配色，必须与 MainActivity.renderWeekSchedule 里的 colors 数组完全一致，
     * 否则小组件的课程色会和课表页对不上。
     */
    private static final String[] COLORS = {
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFA07A",
        "#DDA0DD", "#87CEEB", "#F0E68C", "#FFB6C1", "#98D8C8",
        "#B0C4DE", "#DEB887", "#87CEFA", "#90EE90", "#FFD700"
    };

    /**
     * 按课程名分配颜色。
     *
     * 课表页是按 coursesForWeek(week) 的遍历顺序、首次出现即分配递增下标，
     * 这里用同样的顺序重建映射，保证同一门课在小组件和课表页颜色一致。
     */
    private static java.util.Map<String, String> colorMapFor(ScheduleStore store, int week) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        JSONArray list = store.coursesForWeek(week);
        int idx = 0;
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                String name = c.optString("name", "");
                if (!map.containsKey(name)) {
                    map.put(name, COLORS[idx % COLORS.length]);
                    idx++;
                }
            }
        }
        return map;
    }

    private static String[] timesOf(ScheduleStore store) {
        String[] t = store != null ? store.getPeriodTimes() : null;
        return (t == null || t.length == 0) ? DEFAULT_TIMES : t;
    }

    /** 取第 row 大节（1..5）的 "HH:mm-HH:mm"，越界返回空串 */
    private static String timeOf(String[] times, int row) {
        int i = row - 1;
        if (i < 0 || i >= times.length) return "";
        return times[i] == null ? "" : times[i].trim();
    }

    private static String startOf(String range) {
        int p = range.indexOf('-');
        return p > 0 ? range.substring(0, p).trim() : range.trim();
    }

    private static String endOf(String range) {
        int p = range.indexOf('-');
        return p > 0 ? range.substring(p + 1).trim() : "";
    }

    /** 把 "HH:mm" 解析成当天的分钟数，失败返回 -1 */
    private static int minutesOfDay(String hhmm) {
        if (hhmm == null) return -1;
        int p = hhmm.indexOf(':');
        if (p <= 0) return -1;
        try {
            int h = Integer.parseInt(hhmm.substring(0, p).trim());
            int m = Integer.parseInt(hhmm.substring(p + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 计算「下一节课」，等价于 upcoming(ctx, 1) 的第一项。
     */
    public static Item next(Context ctx) {
        java.util.List<Item> list = upcoming(ctx, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按时间先后返回接下来的若干节课（供小组件多行显示）。
     *
     * 排序规则：
     *  1. 先按天：今天 → 明天 → 后天……最多向后看 7 天
     *  2. 同一天内按开始时间升序
     *  3. 今天已在进行的课排在最前（标记 ongoing）
     *
     * 今天已结束的课不计入。返回的 Item 带 color（与课表页一致）与 dayOffset。
     *
     * @param limit 最多返回几节；<=0 时按 8 处理
     */
    public static java.util.List<Item> upcoming(Context ctx, int limit) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        if (limit <= 0) limit = 8;

        ScheduleStore store = new ScheduleStore(ctx);
        if (!store.hasData()) return out;

        int week = store.todayWeek();
        if (week <= 0) return out;

        String[] times = timesOf(store);
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int todayDow = toMondayFirst(now.get(Calendar.DAY_OF_WEEK));

        // 第 0 天 = 今天（过掉已结束的课）；1..7 = 之后各天（不限时间）
        for (int offset = 0; offset <= 7 && out.size() < limit; offset++) {
            int d = todayDow + offset;
            int targetWeek = week;
            while (d > 7) { d -= 7; targetWeek++; }

            JSONArray courses = store.coursesForWeek(targetWeek);
            if (courses == null || courses.length() == 0) continue;

            java.util.Map<String, String> colorMap = colorMapFor(store, targetWeek);
            // offset==0 时只取尚未结束的课
            int afterMinute = (offset == 0) ? nowMin : -1;
            java.util.List<Item> dayItems =
                itemsOnDay(ctx, store, courses, times, targetWeek, d, afterMinute, colorMap);

            for (Item it : dayItems) {
                if (out.size() >= limit) break;
                it.dayOffset = offset;
                it.today = (offset == 0);
                if (offset == 0) {
                    it.ongoing = it.minutesUntil < 0;
                }
                out.add(it);
            }
        }
        return out;
    }

    /**
     * 取某一天的全部相关课程，按开始时间升序。
     *
     * @param afterMinute 该分钟之前已结束的课会被剔除；-1 表示不限（用于未来某天）
     */
    private static java.util.List<Item> itemsOnDay(Context ctx, ScheduleStore store, JSONArray courses,
                                                   String[] times, int week, int day, int afterMinute,
                                                   java.util.Map<String, String> colorMap) {
        java.util.List<Item> list = new java.util.ArrayList<>();

        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;

            int[] pos = positionOf(ctx, store, c);
            int cDay = pos[0], cRow = pos[1];
            if (cDay != day) continue;

            String range = timeOf(times, cRow);
            int startMin = minutesOfDay(startOf(range));
            if (startMin < 0) continue;
            int endMin = minutesOfDual(endOf(range), startMin);

            // 今天：已结束的跳过
            if (afterMinute >= 0) {
                boolean ended = (endMin >= 0 && endMin <= afterMinute);
                boolean ongoing = startMin <= afterMinute && (endMin < 0 || endMin > afterMinute);
                if (ended) continue;
                Item it = build(c, range, week, cDay, cRow, true);
                it.color = colorMap.getOrDefault(it.name, COLORS[0]);
                it.minutesUntil = startMin - afterMinute;
                it.ongoing = ongoing;
                list.add(it);
                continue;
            }

            Item it = build(c, range, week, cDay, cRow, false);
            it.color = colorMap.getOrDefault(it.name, COLORS[0]);
            list.add(it);
        }

        // 同一天内按开始时间升序；时间相同则保持原有顺序
        final String[] t = times;
        list.sort((a, b) -> {
            int sa = minutesOfDay(a.startTime), sb = minutesOfDay(b.startTime);
            if (sa < 0) sa = Integer.MAX_VALUE;
            if (sb < 0) sb = Integer.MAX_VALUE;
            return Integer.compare(sa, sb);
        });
        return list;
    }

    /** 结束时间兜底：没解析出来就按开始时间 +100 分钟估 */
    private static int minutesOfDual(String end, int startMin) {
        int v = minutesOfDay(end);
        return v >= 0 ? v : (startMin >= 0 ? startMin + 100 : -1);
    }

    /** 应用拖动 overrides 后的 (day, row)，与课表页渲染逻辑一致 */
    private static int[] positionOf(Context ctx, ScheduleStore store, JSONObject c) {
        int day = c.optInt("day", 0);
        int row = c.optInt("row", 0);
        JSONObject ov = store.getOverrides();
        if (ov != null) {
            String key = courseKey(c);
            JSONObject o = ov.optJSONObject(key);
            if (o != null) {
                day = o.optInt("day", day);
                row = o.optInt("row", row);
            }
        }
        return new int[]{day, row};
    }

    private static Item build(JSONObject c, String range, int week, int day, int row, boolean mark) {
        Item it = new Item();
        it.name = c.optString("name", "").replaceAll("\\s+", " ").trim();
        it.location = c.optString("location", "").replaceAll("\\s+", " ").trim();
        it.teacher = c.optString("teacher", "").replaceAll("\\s+", " ").trim();
        it.startTime = startOf(range);
        it.endTime = endOf(range);
        it.week = week;
        it.day = day;
        it.row = row;
        it.today = mark;
        return it;
    }

    /** Calendar.DAY_OF_WEEK(周日=1) → 周一起算(周一=1..周日=7) */
    private static int toMondayFirst(int calendarDow) {
        return calendarDow == Calendar.SUNDAY ? 7 : calendarDow - 1;
    }

    /**
     * 课程唯一键。必须与 MainActivity.courseKey 保持一致，
     * 否则 overrides / 提醒集合会对不上（历史 bug：键不含位置导致互相覆盖，已修为含 day|row）。
     */
    static String courseKey(JSONObject c) {
        return (c.optString("name", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("teacher", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("weeks", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optInt("day", 0)
            + "|" + c.optInt("row", 0));
    }
}
