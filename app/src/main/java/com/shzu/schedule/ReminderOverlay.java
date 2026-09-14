package com.shzu.schedule;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 课程提醒悬浮窗页面
 *
 * 用 SYSTEM_ALERT_WINDOW(悬浮窗)权限把提醒页面盖在任意应用之上，
 * 无论用户当前在桌面、微信还是浏览器，到点都会弹出来。
 *
 * 视觉：半透明深色遮罩 + 紫蓝渐变圆角卡片 + 描边发光，缩放淡入动画。
 */
public class ReminderOverlay {

    private static final String TAG = "ReminderOverlay";
    /** 铃声最长响铃时长，避免无人操作时一直响 */
    private static final long RING_TIMEOUT_MS = 60000L;

    private static View overlayView;
    private static MediaPlayer player;
    private static Vibrator vibrator;
    private static final Handler ringHandler = new Handler(Looper.getMainLooper());
    private static final Runnable ringTimeout = new Runnable() {
        @Override
        public void run() {
            stopRing();
        }
    };

    /** 是否已获得悬浮窗权限 */
    public static boolean canDraw(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return Settings.canDrawOverlays(ctx);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 弹出提醒页面
     *
     * @return true=已用悬浮窗弹出；false=没有权限，调用方应退回通知提醒
     */
    public static boolean show(Context ctx, String name, String location, String teacher,
                               String timeStr, int week, int day) {
        if (!canDraw(ctx)) {
            Log.w(TAG, "overlay permission not granted, cannot show reminder page");
            return false;
        }
        try {
            dismiss(ctx);
            // 来电式提醒：循环响铃 + 震动
            startRing(ctx);

            float d = ctx.getResources().getDisplayMetrics().density;

            // ===== 根：全屏半透明遮罩 =====
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(0xB30A0A12);

            // ===== 渐变提醒卡片 =====
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);

            // 半透明白玻璃质感：白色渐变 + 高光描边
            GradientDrawable cardBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xE6FFFFFF, 0xD9FFFFFF, 0xCCFFFFFF});
            cardBg.setCornerRadius(16 * d);
            cardBg.setStroke((int) Math.max(1, 1.2f * d), 0x99FFFFFF);
            card.setBackground(cardBg);
            card.setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 18));

            // 顶部小标签
            TextView tag = new TextView(ctx);
            tag.setText("课程提醒");
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tag.setTextColor(0x992A2A3A);
            tag.setLetterSpacing(0.18f);
            card.addView(tag);

            // 课程名
            TextView title = new TextView(ctx);
            title.setText(name == null || name.isEmpty() ? "即将上课" : name);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            title.setTextColor(0xFF1A1A2E);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleLp.topMargin = dp(ctx, 8);
            card.addView(title, titleLp);

            // 分隔线
            View div = new View(ctx);
            div.setBackgroundColor(0x22000000);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
            divLp.topMargin = dp(ctx, 14);
            divLp.bottomMargin = dp(ctx, 6);
            card.addView(div, divLp);

            // 信息行
            if (timeStr != null && !timeStr.isEmpty()) {
                addInfoRow(ctx, card, "时间", timeStr);
            } else {
                addInfoRow(ctx, card, "时间", "即将上课");
            }
            if (location != null && !location.isEmpty()) {
                addInfoRow(ctx, card, "地点", location);
            }
            if (teacher != null && !teacher.isEmpty()) {
                addInfoRow(ctx, card, "教师", teacher);
            }
            if (week > 0) {
                String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
                String wd = (day >= 1 && day <= 7) ? days[day - 1] : "";
                addInfoRow(ctx, card, "周次", "第" + week + "周 " + wd);
            }

            // 知道了按钮
            TextView ok = new TextView(ctx);
            ok.setText("知道了");
            ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            ok.setTextColor(0xFFFFFFFF);
            ok.setTypeface(Typeface.DEFAULT_BOLD);
            ok.setGravity(Gravity.CENTER);
            GradientDrawable okBg = new GradientDrawable();
            okBg.setColor(0xFF667EEA);
            okBg.setCornerRadius(10 * d);
            ok.setBackground(okBg);
            ok.setPadding(0, dp(ctx, 13), 0, dp(ctx, 13));
            LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            okLp.topMargin = dp(ctx, 20);
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss(v.getContext());
                }
            });
            card.addView(ok, okLp);

            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            cardLp.gravity = Gravity.CENTER;
            cardLp.leftMargin = dp(ctx, 26);
            cardLp.rightMargin = dp(ctx, 26);
            root.addView(card, cardLp);

            // 入场动画
            card.setAlpha(0f);
            card.setScaleX(0.86f);
            card.setScaleY(0.86f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).start();

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;

            int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            // 毛玻璃：系统支持时对背后的内容做模糊
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    lp.setBlurBehindRadius(20);
                    lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                } catch (Exception ignored) {
                }
            }

            wm.addView(root, lp);
            overlayView = root;
            Log.d(TAG, "reminder overlay shown: " + name);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "show overlay failed", e);
            return false;
        }
    }

    /** 关闭提醒页面（同时停止铃声与震动） */
    public static void dismiss(Context ctx) {
        stopRing();
        if (overlayView == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(overlayView);
        } catch (Exception e) {
            Log.e(TAG, "dismiss failed", e);
        }
        overlayView = null;
    }

    // ====== 响铃与震动（类似微信语音通话的来电提醒） ======

    private static void startRing(Context ctx) {
        stopRing();

        // 音量过低时抬到 80%，确保听得见
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_RING);
                int cur = am.getStreamVolume(AudioManager.STREAM_RING);
                if (max > 0 && cur < (int) (max * 0.6f)) {
                    am.setStreamVolume(AudioManager.STREAM_RING, (int) (max * 0.8f), 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "raise ring volume failed", e);
        }

        // 循环播放系统来电铃声，没有则退回闹钟/通知音
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri != null) {
                player = new MediaPlayer();
                player.setDataSource(ctx, uri);
                player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
                player.setLooping(true);
                player.prepare();
                player.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "start ringtone failed", e);
        }

        // 循环震动
        try {
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 700, 450, 700, 900};
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "vibrate failed", e);
        }

        ringHandler.removeCallbacks(ringTimeout);
        ringHandler.postDelayed(ringTimeout, RING_TIMEOUT_MS);
    }

    private static void stopRing() {
        ringHandler.removeCallbacks(ringTimeout);
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }
    }

    /** 是否正在显示 */
    public static boolean isShowing() {
        return overlayView != null;
    }

    private static void addInfoRow(Context ctx, LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 6), 0, 0);

        TextView lab = new TextView(ctx);
        lab.setText(label);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lab.setTextColor(0x882A2A3A);
        lab.setMinWidth(dp(ctx, 42));
        row.addView(lab);

        TextView val = new TextView(ctx);
        val.setText(value);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        val.setTextColor(0xFF22222E);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        valLp.leftMargin = dp(ctx, 10);
        row.addView(val, valLp);

        parent.addView(row);
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
