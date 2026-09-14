package com.shzu.schedule.reminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;

import com.shzu.schedule.MainActivity;
import com.shzu.schedule.ScheduleStore;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * 课程提醒的通知兜底
 *
 * 正常路径是悬浮窗弹窗；当用户没有授予悬浮窗权限时，
 * ReminderService 会走这里发一条高优先级通知，保证提醒不丢。
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL_COURSE = "course_reminder";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if ("course".equals(intent.getStringExtra("type"))) {
            showCourseReminder(ctx, intent);
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

    /** 创建课程提醒通知渠道（App 启动时调用一次） */
    public static void ensureChannel(Context ctx) {
        ensureCourseChannel(ctx);
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
