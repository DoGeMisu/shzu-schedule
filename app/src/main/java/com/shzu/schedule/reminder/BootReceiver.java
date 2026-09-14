package com.shzu.schedule.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;

import com.shzu.schedule.ScheduleStore;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后拉起课程提醒服务，重新计算提醒时刻表。
 *
 * 自建的定时器(前台服务)在设备重启后会消失，需要在这里重新启动服务。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;

        try {
            ScheduleStore store = new ScheduleStore(ctx);
            if (!store.hasData()) return;
            if (store.getReminderKeys().isEmpty()) {
                Log.d(TAG, "no reminder courses, skip service start");
                return;
            }
            Log.d(TAG, "boot completed, restarting reminder service");
            ReminderService.start(ctx);
        } catch (Exception e) {
            Log.e(TAG, "boot start failed", e);
        }
    }
}
