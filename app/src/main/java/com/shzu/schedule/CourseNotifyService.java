package com.shzu.schedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 通知栏常驻课程。
 *
 * 用前台服务而不是普通通知，是因为普通通知在应用被系统回收后就没人更新了 ——
 * 挂着的会是"上一次打开应用时"的课。前台服务能保证进程活着、每分钟校正一次内容。
 *
 * 通知结构（按需求）：
 *   标题：课程名 + 两个空格 + 时间（时间用 0.78 倍字号，弱化成"小标题"）
 *   正文：地点 · 教师
 *   右上角副文本：正在上课 / 第 N 节 / 明天
 *
 * 只在内容真的变化时才 notify，避免每分钟都推一次（虽然 setOnlyAlertOnce 已经不响铃，
 * 但频繁通知会让部分 ROM 的通知排序抖动）。
 */
public class CourseNotifyService extends Service {

    private static final String TAG = "CourseNotify";
    private static final String CHANNEL_ID = "course_persist_v2";
    private static final int NOTIF_ID = 2001;

    /** 刷新间隔。课程边界最多晚一分钟反映到通知上，这个误差可以接受 */
    private static final long TICK_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 上一次的内容指纹：一样就不重复推通知 */
    private String lastSignature = "";
    /** buildNotification 顺带写出来，供 refresh 比较 */
    private String currentSignature = "";
    /** 富文本标题（课程名 + 缩小的灰色时间），buildNotification 里重建 */
    private CharSequence titleSpanned = null;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先 startForeground：Android 8+ 启动前台服务后 5 秒内不挂通知会被系统杀掉
        try {
            startForeground(NOTIF_ID, buildNotification());
            lastSignature = currentSignature;
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        // 被系统回收后自动重建，重建时 intent 可能为 null，这里不依赖它
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        // 关开关时要把常驻通知一起撤掉，否则会留下一条点不动的"幽灵通知"
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===== 通知构建 =====

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        // IMPORTANCE_LOW 会被 Android 12+ 折叠进"静音"分组，展开通知栏才看得到；
        // 用 DEFAULT 让它留在正常区域，靠 setSilent/setOnlyAlertOnce 保证不出声不打扰。
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "课程常驻通知", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("在通知栏常驻显示当前课程");
        // 常驻信息不该有提示音、不该震动，也不该出现在锁屏顶部打扰
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent contentIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    /** 后台刷新：内容没变就什么都不做 */
    private void refresh() {
        Notification n = buildNotification();
        String sig = currentSignature;
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, n);
            } catch (Exception e) {
                Log.w(TAG, "notify failed", e);
            }
        }
    }

    private Notification buildNotification() {
        titleSpanned = null;
        NextCourse.Item it = null;
        try {
            it = NextCourse.next(this);
        } catch (Exception e) {
            Log.w(TAG, "NextCourse.next failed", e);
        }

        String title;
        String body;
        String sub;
        String color = "#667EEA";
        String sig;

        if (it == null) {
            title = "暂无课程";
            body = "打开课表更新后，这里会显示当前课程";
            sub = "";
            sig = "none";
        } else {
            String name = it.name.isEmpty() ? "未命名课程" : it.name;
            String time = it.timeRange();
            // 课程名 + 两个空格 + 时间：时间单独缩到 0.78 倍并压暗，形成"小标题"的层次
            SpannableString sp = new SpannableString(time.isEmpty() ? name : name + "  " + time);
            if (!time.isEmpty()) {
                int s = name.length();
                sp.setSpan(new RelativeSizeSpan(0.78f), s, sp.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new ForegroundColorSpan(0xFF9AA3B2), s, sp.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            title = sp.toString();
            titleSpanned = sp;

            StringBuilder sb = new StringBuilder();
            if (!it.location.isEmpty()) sb.append(it.location);
            if (!it.teacher.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(it.teacher);
            }
            body = sb.length() == 0 ? "暂无地点与教师" : sb.toString();
            // 副文本：正在上课优先，其次是"明天/周X"，都没有就留空
            if (it.ongoing) sub = "正在上课";
            else if (it.dayOffset == 1) sub = "明天";
            else if (it.dayOffset > 1) sub = it.dayOffset + " 天后";
            else sub = "";
            color = it.color;
            sig = name + "|" + time + "|" + body + "|" + sub + "|" + it.ongoing;
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mark)
                .setContentTitle(titleSpanned != null ? titleSpanned : title)
                .setContentText(body)
                .setContentIntent(contentIntent())
                .setOngoing(true)          // 常驻：用户划不掉
                .setOnlyAlertOnce(true)    // 更新时不再提示
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        if (!sub.isEmpty()) b.setSubText(sub);
        try {
            b.setColor(Color.parseColor(color));
        } catch (Exception ignored) {
        }
        currentSignature = sig;
        return b.build();
    }
}
