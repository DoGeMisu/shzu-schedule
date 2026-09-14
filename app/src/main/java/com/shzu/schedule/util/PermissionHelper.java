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

    /**
     * 打开系统的电池优化设置页（后台常驻）
     *
     * 注意：不再申请 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限
     * （该权限会被系统与安全软件标记为"常驻后台"敏感权限），
     * 改为引导用户到系统设置里手动允许，功能等价且不触发权限告警。
     */
    public static boolean openBatterySettings(Context ctx) {
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

    /** 通知权限是否已开启 */
    public static boolean hasNotificationPermission(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            android.app.NotificationManager nm =
                (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前机型厂商（小写） */
    public static String manufacturer() {
        String m = android.os.Build.MANUFACTURER;
        return m == null ? "" : m.toLowerCase();
    }

    /** 是否小米/红米（有"后台弹出界面"这道额外限制） */
    public static boolean isMiui() {
        String m = manufacturer();
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    /**
     * 本机需要手动开启的权限指引（按厂商给具体路径）
     * 国产 ROM 对"后台弹窗 / 自启动 / 省电策略"常有额外限制，且没有统一 API，
     * 只能明确告诉用户去哪个开关，否则不熟悉手机设置的用户根本找不到。
     */
    public static String guideText() {
        String m = manufacturer();
        StringBuilder sb = new StringBuilder();
        if (isMiui()) {
            sb.append("小米/红米（MIUI、HyperOS）\n")
              .append("1）设置 → 应用设置 → 应用管理 → 石大课表 → 权限管理 → 「后台弹出界面」设为允许\n")
              .append("2）设置 → 应用设置 → 应用管理 → 石大课表 → 省电策略 → 无限制\n")
              .append("3）设置 → 应用设置 → 应用管理 → 石大课表 → 自启动 → 允许\n")
              .append("（第 1 步最关键：不开的话提醒弹不出来，只会发通知）");
        } else if (m.contains("huawei") || m.contains("honor")) {
            sb.append("华为/荣耀（EMUI、HarmonyOS）\n")
              .append("1）设置 → 应用 → 应用启动管理 → 石大课表 → 关闭「自动管理」，手动打开：自启动 / 关联启动 / 后台活动\n")
              .append("2）设置 → 应用 → 应用管理 → 石大课表 → 权限 → 悬浮窗 → 允许\n")
              .append("3）设置 → 电池 → 更多电池设置 → 关闭「休眠时始终保持网络连接」以外的省电限制");
        } else if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) {
            sb.append("OPPO / realme / 一加（ColorOS）\n")
              .append("1）设置 → 应用管理 → 石大课表 → 权限管理 → 悬浮窗 → 允许\n")
              .append("2）设置 → 应用管理 → 石大课表 → 耗电管理 → 允许后台活动 / 允许自启动\n")
              .append("3）最近任务界面下拉卡片 → 加锁（防止被一键清理）");
        } else if (m.contains("vivo") || m.contains("iqoo")) {
            sb.append("vivo / iQOO（OriginOS）\n")
              .append("1）设置 → 应用与权限 → 权限管理 → 石大课表 → 悬浮窗 → 允许\n")
              .append("2）设置 → 应用与权限 → 应用管理 → 石大课表 → 权限 → 自启动 → 允许\n")
              .append("3）设置 → 电池 → 后台高耗电 → 允许石大课表后台运行");
        } else if (m.contains("meizu") || m.contains("flyme")) {
            sb.append("魅族（Flyme）\n")
              .append("1）设置 → 应用管理 → 石大课表 → 权限管理 → 悬浮窗 → 允许\n")
              .append("2）手机管家 → 权限管理 → 后台管理 → 石大课表 → 允许后台运行");
        } else if (m.contains("samsung")) {
            sb.append("三星（One UI）\n")
              .append("1）设置 → 应用程序 → 石大课表 → 权限 → 显示在其他应用上层 → 允许\n")
              .append("2）设置 → 电池 → 后台使用限制 → 从「深度休眠应用」中移除石大课表");
        } else {
            sb.append("通用步骤（各机型大同小异）\n")
              .append("1）在系统设置里找到「石大课表」，把「悬浮窗 / 显示在其他应用上层」设为允许\n")
              .append("2）把它的「自启动 / 后台运行 / 省电策略」设为允许或不限制\n")
              .append("3）在最近任务里给它加锁，避免被一键清理");
        }
        return sb.toString();
    }

    /** 小米：直达"后台弹出界面"权限编辑页 */
    public static boolean openMiuiPopupPermission(Context ctx) {
        try {
            Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
            i.setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity");
            i.putExtra("extra_pkgname", ctx.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "open miui permission editor failed", e);
        }
        return openAppDetails(ctx);
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
