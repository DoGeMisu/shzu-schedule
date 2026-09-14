package com.shzu.schedule.reminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;

import com.shzu.schedule.MainActivity;
import com.shzu.schedule.ScheduleStore;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 课程提醒前台服务：自建定时器，不依赖系统闹钟(AlarmManager)。
 *
 * 工作方式：
 *   常驻前台服务(带常驻通知，避免被系统回收) → 内部 Handler 每秒 tick →
 *   检查"已到提醒时刻且未触发"的课程 → 用悬浮窗在任意界面之上弹出提醒页面。
 *
 * 入口：
 *   start(ctx)          启动并全量重算提醒时刻表
 *   reload(ctx)         数据变化(改课程/改提前量/刷新课表)后重算
 *   debug(ctx, seconds) 调试：N 秒后弹一条测试提醒（可退回桌面验证）
 *   stop(ctx)           停止
 */
public class ReminderService extends Service {

    private static final String TAG = "ReminderService";
    private static final String CHANNEL_ID = "reminder_service";
    private static final int SERVICE_NOTIFY_ID = 9001;

    public static final String ACTION_START = "com.shzu.schedule.action.START";
    public static final String ACTION_RELOAD = "com.shzu.schedule.action.RELOAD";
    public static final String ACTION_DEBUG = "com.shzu.schedule.action.DEBUG";
    public static final String EXTRA_SECONDS = "seconds";

    private static final long DAY = 86400000L;
    private static final long MINUTE = 60000L;
    private static final long TICK_MS = 1000L;
    /** 提醒时刻已过去超过该时长则不再补弹（避免开机后弹出一堆积压提醒） */
    private static final long STALE_WINDOW = 10 * MINUTE;

    /** 一次待触发的提醒 */
    public static class Trigger {
        public long time;
        public String key = "";
        public String name = "";
        public String location = "";
        public String teacher = "";
        public String timeStr = "";
        public int week = 0;
        public int day = 0;
    }

    private Handler handler;
    private final List<Trigger> triggers = new ArrayList<>();
    private boolean ticking = false;

    // ====== 静态入口 ======

    public static void start(Context ctx) {
        dispatch(ctx, ACTION_START, 0);
    }

    public static void reload(Context ctx) {
        dispatch(ctx, ACTION_RELOAD, 0);
    }

    public static void debug(Context ctx, int seconds) {
        dispatch(ctx, ACTION_DEBUG, seconds);
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, ReminderService.class));
        } catch (Exception e) {
            Log.e(TAG, "stop failed", e);
        }
    }

    private static void dispatch(Context ctx, String action, int seconds) {
        try {
            Intent i = new Intent(ctx, ReminderService.class);
            i.setAction(action);
            if (seconds > 0) i.putExtra(EXTRA_SECONDS, seconds);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "dispatch " + action + " failed", e);
        }
    }

    // ====== 生命周期 ======

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent == null || intent.getAction() == null)
            ? ACTION_START : intent.getAction();

        startForegroundCompat();

        if (ACTION_DEBUG.equals(action)) {
            int seconds = intent.getIntExtra(EXTRA_SECONDS, 10);
            if (seconds <= 0) seconds = 10;
            addDebugTrigger(seconds);
        } else {
            // START / RELOAD 都全量重算
            rebuild();
        }

        if (!ticking) {
            ticking = true;
            handler.removeCallbacks(tickRunnable);
            handler.postDelayed(tickRunnable, TICK_MS);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ticking = false;
        if (handler != null) handler.removeCallbacks(tickRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ====== 定时心跳 ======

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                checkTriggers();
            } catch (Exception e) {
                Log.e(TAG, "tick error", e);
            }
            if (ticking) handler.postDelayed(this, TICK_MS);
        }
    };

    /** 检查是否有到点的提醒 */
    private void checkTriggers() {
        long now = System.currentTimeMillis();
        List<Trigger> due = null;

        synchronized (triggers) {
            for (java.util.Iterator<Trigger> it = triggers.iterator(); it.hasNext(); ) {
                Trigger t = it.next();
                if (t.time > now) continue;
                it.remove();
                if (now - t.time <= STALE_WINDOW) {
                    if (due == null) due = new ArrayList<>();
                    due.add(t);
                } else {
                    Log.d(TAG, "skip stale reminder: " + t.name);
                }
            }
        }

        if (due == null) return;

        for (Trigger t : due) {
            Log.d(TAG, "fire reminder: " + t.name + " week " + t.week);
            boolean shown = ReminderOverlay.show(this, t.name, t.location, t.teacher,
                t.timeStr, t.week, t.day);
            if (!shown) {
                // 没有悬浮窗权限 → 退回通知，保证提醒不丢
                notifyFallback(t);
            }
        }
        updateServiceNotification();
    }

    // ====== 提醒时刻表计算 ======

    /** 全量重算所有已选课程的提醒时刻 */
    private void rebuild() {
        List<Trigger> list = new ArrayList<>();
        int skipped = 0;
        try {
            ScheduleStore store = new ScheduleStore(this);
            Set<String> keys = store.getReminderKeys();
            if (store.hasData() && !keys.isEmpty()) {
                int advance = store.getAdvanceMinutes();
                long week1Monday = store.getWeek1Monday();
                JSONArray courses = store.getAllCourses();
                String[] periodTimes = store.getPeriodTimes();
                int totalWeeks = store.getTotalWeeks();
                if (totalWeeks <= 0) totalWeeks = 30;
                JSONObject overrides = store.getOverrides();
                long now = System.currentTimeMillis();

                for (int i = 0; i < courses.length(); i++) {
                    JSONObject c = courses.optJSONObject(i);
                    if (c == null) continue;
                    String key = courseKey(c);
                    if (!keys.contains(key)) continue;

                    int day = c.optInt("day", 0);
                    int row = c.optInt("row", 0);
                    String weeks = c.optString("weeks", "");
                    String name = c.optString("name", "");
                    String location = c.optString("location", "");
                    String teacher = c.optString("teacher", "");

                    // 应用拖动产生的覆盖位置
                    if (overrides != null) {
                        JSONObject o = overrides.optJSONObject(key);
                        if (o != null) {
                            int nd = o.optInt("d", 0), nr = o.optInt("r", 0);
                            if (nd >= 1 && nd <= 7 && nr >= 1 && nr <= 5) {
                                day = nd;
                                row = nr;
                            }
                        }
                    }
                    if (day < 1 || day > 7 || row < 1 || row > 5) continue;

                    String timeStr = "";
                    if (periodTimes != null && row - 1 < periodTimes.length) {
                        timeStr = periodTimes[row - 1];
                    }
                    int[] hm = parseStartTime(timeStr);
                    if (hm == null) hm = new int[]{8, 0};

                    for (int w = 1; w <= totalWeeks; w++) {
                        if (!ScheduleStore.weekMatches(weeks, w)) continue;
                        if (week1Monday <= 0) continue;

                        long classDate = week1Monday + (w - 1) * 7 * DAY + (day - 1) * DAY;
                        Calendar cal = Calendar.getInstance();
                        cal.setTimeInMillis(classDate);
                        cal.set(Calendar.HOUR_OF_DAY, hm[0]);
                        cal.set(Calendar.MINUTE, hm[1]);
                        cal.set(Calendar.SECOND, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        long triggerTime = cal.getTimeInMillis() - advance * MINUTE;

                        // 【今日以前的课程提醒一律禁用】
                        // 用绝对时间戳比较：昨天/更早的提醒时刻、以及今天已经过去的时刻
                        // 都不会排入，避免重启 App 或改系统时间后把旧课补弹出来。
                        if (triggerTime <= now) {
                            skipped++;
                            if (skipped <= 8) {
                                Log.d(TAG, "disable past reminder: " + name + " week " + w
                                    + " at " + fmtTime(triggerTime));
                            }
                            continue;
                        }

                        Trigger t = new Trigger();
                        t.time = triggerTime;
                        t.key = key;
                        t.name = name;
                        t.location = location;
                        t.teacher = teacher;
                        t.timeStr = timeStr;
                        t.week = w;
                        t.day = day;
                        list.add(t);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "rebuild error", e);
        }

        Collections.sort(list, new Comparator<Trigger>() {
            @Override
            public int compare(Trigger a, Trigger b) {
                return Long.compare(a.time, b.time);
            }
        });

        synchronized (triggers) {
            triggers.clear();
            triggers.addAll(list);
        }
        Log.d(TAG, "rebuild: " + list.size() + " reminders pending"
            + (skipped > 0 ? " (" + skipped + " past reminders disabled)" : ""));
        updateServiceNotification();
    }

    /** 调试：seconds 秒后弹一条测试提醒 */
    private void addDebugTrigger(int seconds) {
        Trigger t = new Trigger();
        t.time = System.currentTimeMillis() + seconds * 1000L;
        t.key = "__debug__";
        t.name = "测试提醒：高等数学D";
        t.location = "会4-419";
        t.teacher = "调试模式";
        t.timeStr = "10:00-11:40";
        t.week = 3;
        t.day = 1;
        synchronized (triggers) {
            triggers.add(t);
            Collections.sort(triggers, new Comparator<Trigger>() {
                @Override
                public int compare(Trigger a, Trigger b) {
                    return Long.compare(a.time, b.time);
                }
            });
        }
        Log.d(TAG, "debug trigger in " + seconds + "s");
    }

    // ====== 通知 ======

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "课程提醒服务", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("保持课程提醒在后台准时弹出");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        startForeground(SERVICE_NOTIFY_ID, buildServiceNotification());
    }

    private void updateServiceNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(SERVICE_NOTIFY_ID, buildServiceNotification());
        } catch (Exception e) {
            Log.e(TAG, "update notification failed", e);
        }
    }

    private Notification buildServiceNotification() {
        int pending;
        Trigger next = null;
        synchronized (triggers) {
            pending = triggers.size();
            if (!triggers.isEmpty()) next = triggers.get(0);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = Build.VERSION.SDK_INT >= 23
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flag);

        String text;
        if (next != null) {
            text = "下次提醒：" + fmtTime(next.time) + " " + next.name;
        } else if (pending > 0) {
            text = "已开启课程提醒 · " + pending + " 个提醒待触发";
        } else {
            text = "已开启课程提醒";
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("石大课表提醒运行中")
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build();
    }

    /** 没有悬浮窗权限时的兜底通知 */
    private void notifyFallback(Trigger t) {
        try {
            ReminderReceiver.ensureCourseChannel(this);
            StringBuilder sb = new StringBuilder();
            if (t.timeStr != null && !t.timeStr.isEmpty()) sb.append(t.timeStr);
            if (t.location != null && !t.location.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(t.location);
            }
            String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            if (t.week > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("第").append(t.week).append("周")
                  .append(t.day >= 1 && t.day <= 7 ? days[t.day - 1] : "");
            }

            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flag = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(this, 3000, open, flag);

            NotificationCompat.Builder nb = new NotificationCompat.Builder(this,
                ReminderReceiver.CHANNEL_COURSE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("课程提醒：" + t.name)
                .setContentText(sb.length() > 0 ? sb.toString() : "即将上课")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(sb.toString()))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(3000 + (t.name.hashCode() & 0xFF), nb.build());
        } catch (Exception e) {
            Log.e(TAG, "fallback notify failed", e);
        }
    }

    // ====== 工具 ======

    /** 格式化提醒时刻，用于日志与通知展示 */
    private static String fmtTime(long millis) {
        return new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
            .format(new java.util.Date(millis));
    }

    /** 从 "10:00-11:40" 解析起始时间 */
    private static int[] parseStartTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return null;
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(timeStr);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return null;
    }

    /** courseKey 格式与前端/存储保持一致：名称|教师|周次|day|row */
    private static String courseKey(JSONObject c) {
        return (c.optString("name", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("teacher", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optString("weeks", "").replaceAll("\\s+", " ").trim()
            + "|" + c.optInt("day", 0)
            + "|" + c.optInt("row", 0));
    }
}
