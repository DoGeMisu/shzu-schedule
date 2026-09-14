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
 * 窗口末周最后三天的提醒接收器：
 * 课表周期(五周)即将结束 → 发通知提示用户，打开应用将自动更新下一个五周课表
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "schedule_reminder";
    public static final int NOTIFICATION_ID = 1001;

    @Override
    public void onReceive(Context ctx, Intent intent) {
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

        // 点击通知 → 打开App → 启动时检测到窗口到期自动抓取新五周
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
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "课表周期提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("五周课表周期结束前三天提醒更新");
            nm.createNotificationChannel(ch);
        }
    }
}
