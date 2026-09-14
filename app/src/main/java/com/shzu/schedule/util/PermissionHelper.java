package com.shzu.schedule.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 提醒相关权限跳转
 *
 * - 悬浮窗权限(SYSTEM_ALERT_WINDOW)：让提醒页面能盖在其他应用之上
 * - 自启动权限：各厂商 ROM 自己的"允许后台自启动"开关，Android 无统一 API，只能按机型跳转
 * - 后台运行/电池优化白名单：避免服务被系统休眠
 */
public class PermissionHelper {

    private static final String TAG = "PermissionHelper";

    /** 各厂商自启动管理页面（按顺序尝试） */
    private static final String[][] AUTOSTART_PAGES = {
        // 小米 / 红米
        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
        // 华为 / 荣耀
        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
        // OPPO / 一加
        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
        {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
        {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
        {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
        // vivo / iQOO
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
        // 魅族
        {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
        {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
        // 三星
        {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        {"com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        // 联想 / 乐视
        {"com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"},
        {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
    };

    /** 申请悬浮窗权限（让提醒能显示在其他应用之上） */
    public static boolean openOverlaySettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "open overlay settings failed", e);
        }
        return openAppDetails(ctx);
    }

    /** 打开厂商的"自启动管理"页面 */
    public static boolean openAutoStartSettings(Context ctx) {
        for (String[] page : AUTOSTART_PAGES) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(page[0], page[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (ctx.getPackageManager().resolveActivity(i, 0) == null) continue;
                ctx.startActivity(i);
                Log.d(TAG, "opened autostart page: " + page[0] + "/" + page[1]);
                return true;
            } catch (Exception ignored) {
                // 尝试下一个厂商页面
            }
        }
        Log.w(TAG, "no autostart page matched, fallback to app details");
        return openAppDetails(ctx);
    }

    /** 申请电池优化白名单（后台常驻） */
    public static boolean openBatterySettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "request ignore battery optimizations failed", e);
        }
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "open battery settings failed", e);
        }
        return openAppDetails(ctx);
    }

    /** 兜底：本应用详情页 */
    public static boolean openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "open app details failed", e);
            return false;
        }
    }

    /** 电池优化白名单是否已加入 */
    public static boolean isIgnoringBatteryOptimizations(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.os.PowerManager pm =
                (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}
