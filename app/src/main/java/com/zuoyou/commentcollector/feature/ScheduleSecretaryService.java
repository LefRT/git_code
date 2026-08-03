package com.zuoyou.commentcollector.feature;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.zuoyou.commentcollector.Constants;
import com.zuoyou.commentcollector.R;
import com.zuoyou.commentcollector.feature.ScheduleData.DailySchedule;
import com.zuoyou.commentcollector.feature.ScheduleData.ScheduleItem;
import com.zuoyou.commentcollector.feature.ScheduleStateManager.AlarmAction;
import com.zuoyou.commentcollector.feature.ScheduleStateManager.ItemState;

/**
 * 日程秘书前台服务 — 基于 AlarmManager 事件驱动。
 * <p>
 * 收到 ACTION_ALARM 时执行状态机检查 → 发消息 → 弹通知 → 设下一个闹钟。
 */
public class ScheduleSecretaryService extends Service {

    private static final String TAG = "ScheduleSecretary";

    public static final String ACTION_ALARM = "com.zuoyou.commentcollector.ACTION_SCHEDULE_ALARM";
    public static final String ACTION_STOP = "STOP";

    private static final String CHANNEL_ID = "schedule_secretary";
    private static final String CHANNEL_NAME = "日程秘书";
    private static final int NOTIFICATION_ID = 2001;
    private static final int ALARM_NOTIFY_ID = 2002;

    /** 秘书会话的固定 ID（ChatSessionManager 中的特殊会话） */
    public static final int SECRETARY_SESSION_ID = -999;

    private static volatile boolean sIsRunning = false;
    /** 秘书聊天是否在前台打开（设为 true 时抑制通知） */
    private static volatile boolean sSecretaryChatVisible = false;

    public static void setSecretaryChatVisible(boolean visible) {
        sSecretaryChatVisible = visible;
    }

    private ChatSessionManager sessionManager;
    private ChatAiService aiService;
    private ScheduleStateManager stateManager;
    private ScheduleDataManager dataManager;
    private volatile boolean processingAlarm = false;

    public static boolean isRunning() {
        return sIsRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning = true;
        sessionManager = ChatSessionManager.getInstance(this);
        aiService = new ChatAiService(this);
        aiService.setSystemPrompt(Constants.SECRETARY_SYSTEM_PROMPT);  // 秘书人格
        stateManager = ScheduleStateManager.getInstance(this);
        dataManager = ScheduleDataManager.getInstance(this);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());

        // 初始化今日状态 + 设第一个闹钟
        stateManager.initForToday();
        stateManager.scheduleNextAlarm();

        Log.d(TAG, "日程秘书服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_ALARM.equals(action)) {
            handleAlarm();
        }

        return START_STICKY;
    }

    private void handleAlarm() {
        if (processingAlarm) {
            Log.d(TAG, "正在处理上一个闹钟，跳过");
            return;
        }
        processingAlarm = true;

        // 初始化今日状态（确保是新的一天）
        stateManager.initForToday();

        // 执行状态机
        AlarmAction action = stateManager.onAlarmTriggered();
        if (action == null) {
            Log.d(TAG, "无待处理事项");
            stateManager.scheduleNextAlarm();
            processingAlarm = false;
            return;
        }

        // 获取今日计划
        DailySchedule schedule = dataManager.getTodaySchedule();
        ScheduleItem item = (action.itemIndex < schedule.items.size())
                ? schedule.items.get(action.itemIndex) : null;
        if (item == null) {
            processingAlarm = false;
            return;
        }

        // 构造 AI 提示词
        String prompt = buildPrompt(action, item, schedule);
        Log.d(TAG, "发送主动消息: type=" + action.type + " item=" + item.taskName);

        // 获取或创建秘书会话
        int sessionId = sessionManager.getOrCreateSecretarySession();
        if (sessionId == -1) {
            // 降级：创建新会话
            sessionId = sessionManager.createNewSession();
        }

        final int sid = sessionId;
        final AlarmAction finalAction = action;

        // 调用 AI 生成消息
        aiService.chat(sessionId, prompt, new ChatAiService.ChatCallback() {
            @Override
            public void onResponse(String reply) {
                // 保存 AI 消息到秘书会话
                sessionManager.saveMessage(sid, "assistant", reply);

                // 弹通知（如果聊天界面没开着秘书会话则弹）
                if (!sSecretaryChatVisible) {
                    showAlarmNotification(reply);
                } else {
                    Log.d(TAG, "秘书聊天在前台，跳过通知");
                }

                // 继续调度
                stateManager.scheduleNextAlarm();
                processingAlarm = false;

                Log.d(TAG, "主动消息已发送: " + (reply.length() > 50 ? reply.substring(0, 50) + "..." : reply));
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "主动消息发送失败: " + error);
                // 即使失败也继续调度
                stateManager.scheduleNextAlarm();
                processingAlarm = false;
            }
        });
    }

    /** 构造发送给 AI 的系统指令 */
    private String buildPrompt(AlarmAction action, ScheduleItem item, DailySchedule schedule) {
        String stateCtx = stateManager.buildStateContext(schedule);
        ItemState state = action.itemState;
        // 使用调整后的时间（如果已调整），否则用原始时间
        String effectiveStart = (state.adjustedStart != null) ? state.adjustedStart : item.startTime;
        String effectiveEnd = (state.adjustedEnd != null) ? state.adjustedEnd : item.endTime;
        double plannedH = state.plannedHours > 0 ? state.plannedHours : item.plannedHours;

        switch (action.type) {
            case START_REMINDER:
                return "现在是" + effectiveStart + "，事项「" + item.taskName + "」的起始时间到了。"
                        + "请以日程秘书的身份，给用户发送一条温和的提醒消息，告知该开始了。"
                        + "风格：知性温柔、带一点俏皮，不用太正式，像姐姐提醒弟弟去做事。"
                        + "\n\n" + stateCtx;

            case END_CHECK:
                return "现在是" + effectiveEnd + "，事项「" + item.taskName + "」（计划" + plannedH + "小时）"
                        + "的结束时间到了。请以日程秘书的身份，温柔地询问用户完成情况、实际花了多长时间、"
                        + "是否需要调整后续计划。风格：知性温柔、略带俏皮，不要催促不要指责。"
                        + "\n\n" + stateCtx;

            default:
                return stateCtx;
        }
    }

    // ─── 通知 ───

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("日程秘书运行状态");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildForegroundNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("日程秘书")
                .setContentText("日程管理已就绪")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return builder.build();
    }

    private void showAlarmNotification(String message) {
        // 截取前 80 字作为通知内容
        String content = message.length() > 80 ? message.substring(0, 80) + "…" : message;

        // 点通知打开聊天（MainActivity）
        Intent openIntent = new Intent(this, com.zuoyou.commentcollector.MainActivity.class);
        openIntent.putExtra("open_secretary_chat", true);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 100, openIntent, flags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📅 日程秘书")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(ALARM_NOTIFY_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        sIsRunning = false;
        stateManager.cancelAlarm();
        if (aiService != null) aiService.cancel();
        super.onDestroy();
        Log.d(TAG, "日程秘书服务已停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
