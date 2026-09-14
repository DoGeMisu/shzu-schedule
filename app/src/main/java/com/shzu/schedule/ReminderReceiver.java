package com.shzu.schedule;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * 通知接收器：支持两种提醒
 * 1. 窗口末周提醒（原有）：课表周期结束前三天通知更新
 * 2. 课程上课提醒（新增）：上课前提前N分钟弹出通知，含课程名/教室/时间
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "schedule_reminder";
    public static final String CHANNEL_COURSE = "course_reminder";
    public static final int NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String type = intent.getStringExtra("type");
        if ("course".equals(type)) {
            showCourseReminder(ctx, intent);
        } else {
            showWindowReminder(ctx);
        }
    }

    /** 课程上课提醒 */
    private void showCourseReminder(Context ctx, Intent intent) {
        String name = intent.getStringExtra("name");
        String location = intent.getStringExtra("location");
        String teacher = intent.getStringExtra("teacher");
        String timeStr = intent.getStringExtra("time");
        int notifyId = intent.getIntExtra("notifyId", 2000);
        int week = intent.getIntExtra("week", 0);
        int day = intent.getIntExtra("day", 0);

        if (name == null || name.isEmpty()) return;

        ensureCourseChannel(ctx);

        String title = "课程提醒：" + name;
        StringBuilder sb = new StringBuilder();
        if (timeStr != null && !timeStr.isEmpty()) sb.append(timeStr);
        if (location != null && !location.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(location);
        }
        if (teacher != null && !teacher.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(teacher);
        }
        if (week > 0) {
            if (sb.length() > 0) sb.append(" · ");
            String[] days = {"周一","周二","周三","周四","周五","周六","周日"};
            sb.append("第").append(week).append("周").append(day >= 1 && day <= 7 ? days[day-1] : "");
        }
        String text = sb.length() > 0 ? sb.toString() : "即将上课";

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = Build.VERSION.SDK_INT >= 23
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(ctx, notifyId, open, flag);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_COURSE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifyId, nb.build());
    }

    /** 窗口末周提醒（原有功能） */
    private void showWindowReminder(Context ctx) {
        ScheduleStore store = new ScheduleStore(ctx);
        if (!store.hasData()) return;

        String title = "石大课表提醒";
        String text;
        if (store.getTotalWeeks() > 0 && store.getWindowEnd() > 0) {
            int nextStart = store.getWindowEnd() + 1;
            int nextEnd = Math.min(store.getWindowEnd() + 5, store.getTotalWeeks());
            text = "第" + store.getWindowStart() + "-" + store.getWindowEnd()
                + "周课表周期即将结束，打开应用自动更新第" + nextStart + "-" + nextEnd + "周课表";
        } else {
            text = "课表数据即将进入新周期，打开应用自动更新";
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = Build.VERSION.SDK_INT >= 23
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flag);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, nb.build());
    }

    /** 创建通知渠道（App启动时调用一次） */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            // 窗口提醒渠道
            NotificationChannel ch1 = new NotificationChannel(CHANNEL_ID,
                "课表周期提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch1.setDescription("五周课表周期结束前三天提醒更新");
            nm.createNotificationChannel(ch1);
            // 课程上课提醒渠道
            ensureCourseChannel(ctx);
        }
    }

    /** 创建课程提醒通知渠道 */
    public static void ensureCourseChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_COURSE) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_COURSE,
                "课程上课提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("上课前提前提醒，含课程名/教室/时间");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 200, 100, 200});
            nm.createNotificationChannel(ch);
        }
    }
}
