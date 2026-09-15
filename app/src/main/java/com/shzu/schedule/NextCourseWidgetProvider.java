package com.shzu.schedule;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 「接下来几节课」桌面小组件。
 *
 * 尺寸适配说明：
 *  安卓官方没有"把 App 图标拉大"的能力，桌面大图标即 App Widget。
 *  不同启动器允许的拉伸尺寸不同（常见 2x1 / 2x2 / 4x1 / 4x2），
 *  这里读取 onAppWidgetOptionsChanged 给出的 minWidth/minHeight，
 *  按可用空间决定显示几节课，避免小尺寸下文字被裁切。
 *
 * 配色：
 *  支持浅色/深色两套配色，用户在小组件上点右上角的 ◐ 按钮切换，
 *  偏好存在 ScheduleStore（与 App 主题相互独立）。
 */
public class NextCourseWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_REFRESH = "com.shzu.schedule.WIDGET_REFRESH";
    private static final String ACTION_TOGGLE_THEME = "com.shzu.schedule.WIDGET_TOGGLE_THEME";

    /** 最多渲染的行数，与 widget_next_course.xml 中预留的行数一致 */
    private static final int MAX_ROWS = 4;

    // 行控件 id 表（RemoteViews 不能用数组动态取 id，只能列出来）
    private static final int[] ROW_IDS = {
        R.id.row1, R.id.row2, R.id.row3, R.id.row4
    };
    private static final int[] DOT_IDS = {
        R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4
    };
    private static final int[] NAME_IDS = {
        R.id.name1, R.id.name2, R.id.name3, R.id.name4
    };
    private static final int[] SUB_IDS = {
        R.id.sub1, R.id.sub2, R.id.sub3, R.id.sub4
    };

    // ===== 浅色配色 =====
    private static final int LIGHT_NAME = 0xFF1F2437;
    private static final int LIGHT_SUB = 0xFF5A6285;
    private static final int LIGHT_LABEL = 0xFF5A6285;
    private static final int LIGHT_BTN = 0xFF4A5170;

    // ===== 深色配色 =====
    private static final int DARK_NAME = 0xFFF2F4FF;
    private static final int DARK_SUB = 0xFFAEB6D6;
    private static final int DARK_LABEL = 0xFFAEB6D6;
    private static final int DARK_BTN = 0xFFD8DDF5;

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            updateWidget(context, mgr, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr,
                                          int id, Bundle newOptions) {
        updateWidget(context, mgr, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)) {
            refreshAll(context);
        } else if (ACTION_TOGGLE_THEME.equals(action)) {
            // 切换配色后立刻重绘
            try {
                new ScheduleStore(context).toggleWidgetTheme();
            } catch (Exception ignored) {
            }
            refreshAll(context);
        }
    }

    /** 供 App 内部（课表变化、提醒触发）调用，主动刷新所有小组件 */
    public static void refreshAll(Context context) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, NextCourseWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids == null || ids.length == 0) return;
            Intent it = new Intent(context, NextCourseWidgetProvider.class);
            it.setAction(ACTION_REFRESH);
            it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            context.sendBroadcast(it);
        } catch (Exception ignored) {
            // 小组件刷新失败不应影响主流程
        }
    }

    private void updateWidget(Context context, AppWidgetManager mgr, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_next_course);

        boolean dark;
        try {
            dark = "dark".equals(new ScheduleStore(context).getWidgetTheme());
        } catch (Exception e) {
            dark = false;
        }

        // 背景与文字配色
        views.setInt(R.id.widget_root, "setBackgroundResource",
            dark ? R.drawable.widget_bg_dark : R.drawable.widget_bg);
        int cName = dark ? DARK_NAME : LIGHT_NAME;
        int cSub = dark ? DARK_SUB : LIGHT_SUB;
        int cLabel = dark ? DARK_LABEL : LIGHT_LABEL;
        int cBtn = dark ? DARK_BTN : LIGHT_BTN;
        views.setTextColor(R.id.widget_label, cLabel);
        views.setTextColor(R.id.widget_theme_btn, cBtn);

        int rows = rowCountFor(mgr, id);

        List<NextCourse.Item> items = null;
        try {
            items = NextCourse.upcoming(context, rows);
        } catch (Exception ignored) {
            // 数据异常时退化为"暂无课程"，不要崩
        }

        if (items == null || items.isEmpty()) {
            views.setTextViewText(R.id.widget_label, "石大课表");
            setRow(views, 0, null, cName, cSub);
            for (int i = 1; i < MAX_ROWS; i++) setRow(views, i, null, cName, cSub);
        } else {
            views.setTextViewText(R.id.widget_label, labelFor(items));
            for (int i = 0; i < MAX_ROWS; i++) {
                NextCourse.Item it = i < items.size() ? items.get(i) : null;
                setRow(views, i, it, cName, cSub);
            }
        }

        // 点击卡片 → 打开 App
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);

        // 点击 ◐ → 切换深浅配色。
        // PendingIntent 的 requestCode 必须与上面不同，否则会互相覆盖。
        Intent toggle = new Intent(context, NextCourseWidgetProvider.class);
        toggle.setAction(ACTION_TOGGLE_THEME);
        PendingIntent piToggle = PendingIntent.getBroadcast(
            context, 1, toggle,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_theme_btn, piToggle);

        mgr.updateAppWidget(id, views);
    }

    /** 顶栏文字：有正在上的课就提示，否则给「接下来」并带上日期范围 */
    private String labelFor(List<NextCourse.Item> items) {
        NextCourse.Item first = items.get(0);
        if (first.ongoing) return "正在上课";
        if (items.size() == 1) {
            return first.today ? "下一节" : dayLabel(first.day) + " · 下一节";
        }
        // 多节时说明覆盖范围：今天 / 含明天 / 跨天
        int lastOffset = items.get(items.size() - 1).dayOffset;
        if (lastOffset == 0) return "接下来 " + items.size() + " 节 · 今天";
        if (lastOffset == 1) return "接下来 " + items.size() + " 节 · 含明天";
        return "接下来 " + items.size() + " 节";
    }

    /** 渲染一行；item 为 null 时整行隐藏 */
    private void setRow(RemoteViews views, int idx, NextCourse.Item it,
                        int cName, int cSub) {
        if (idx < 0 || idx >= MAX_ROWS) return;
        int rowId = ROW_IDS[idx], dotId = DOT_IDS[idx];
        int nameId = NAME_IDS[idx], subId = SUB_IDS[idx];

        if (it == null) {
            views.setViewVisibility(rowId, View.GONE);
            return;
        }
        views.setViewVisibility(rowId, View.VISIBLE);

        // 色点：用课程自身颜色，与课表页一致
        int color;
        try {
            color = Color.parseColor(it.color);
        } catch (Exception e) {
            color = 0xFF667EEA;
        }
        views.setInt(dotId, "setBackgroundColor", color);

        views.setTextViewText(nameId, it.name);
        views.setTextColor(nameId, cName);

        views.setTextViewText(subId, subLineFor(it));
        views.setTextColor(subId, cSub);
    }

    /** 副行内容：地点 + 时间；跨天时前面带上星期 */
    private String subLineFor(NextCourse.Item it) {
        StringBuilder sb = new StringBuilder();
        if (!it.today) {
            sb.append(dayLabel(it.day));
            if (it.dayOffset == 1) sb.append("(明天)");
            sb.append(" ");
        }
        if (!it.location.isEmpty()) sb.append(it.location);
        String t = it.timeRange();
        if (!t.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 依据启动器给出的可用尺寸决定显示几行。
     * 行高约 36dp，加上顶栏与内边距，据此估算。
     */
    private int rowCountFor(AppWidgetManager mgr, int id) {
        try {
            Bundle opt = mgr.getAppWidgetOptions(id);
            if (opt == null) return 2;

            int minH = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            int minW = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);

            // 高度不够：只显示 1 节
            if (minH > 0 && minH < 110) return 1;
            // 2x2 上下：显示 2 节
            if (minH < 180) return 2;
            // 高且宽：3~4 节
            if (minW >= 220) return 4;
            return 3;
        } catch (Exception e) {
            return 2;
        }
    }

    private static String dayLabel(int day) {
        String[] w = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return (day >= 1 && day <= 7) ? w[day] : "";
    }
}
