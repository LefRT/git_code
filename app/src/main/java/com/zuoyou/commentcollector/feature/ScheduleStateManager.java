package com.zuoyou.commentcollector.feature;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.zuoyou.commentcollector.feature.ScheduleData.DailySchedule;
import com.zuoyou.commentcollector.feature.ScheduleData.ScheduleItem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 日程状态机 + AlarmManager 调度 + 状态持久化。
 * <p>
 * 事项状态流转：
 * <pre>
 *   PENDING → START_SENT → END_CHECK_SENT → ADJUSTING → COMPLETED
 * </pre>
 */
public class ScheduleStateManager {

    private static final String TAG = "ScheduleState";
    private static final String DIR_NAME = "daily_plan";
    private static final String STATE_FILE = "schedule_state.json";

    /** 事项状态 */
    public enum State {
        PENDING,          // 尚未开始
        START_SENT,       // 已发开始提醒
        END_CHECK_SENT,   // 已发结束回访
        ADJUSTING,        // 正在与用户讨论调整
        COMPLETED,        // 已完成（时间确认/超时自动完成）
        SKIPPED           // 已跳过（事项时间已过）
    }

    /** 单个事项的运行状态 */
    public static class ItemState {
        public String itemId;
        public State state = State.PENDING;
        public double plannedHours;
        public double actualHours = -1;     // -1 = 未确认
        public String adjustedStart;        // 调整后的开始时间（可能为空，用原计划）
        public String adjustedEnd;          // 调整后的结束时间（可能为空，用原计划）
        public long endCheckSentAt = 0;     // 发送结束回访的时间戳（用于超时计算）
        public long adjustingStartedAt = 0; // 进入 ADJUSTING 状态的时间戳

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("item_id", itemId);
            obj.put("state", state.name());
            obj.put("planned_hours", plannedHours);
            obj.put("actual_hours", actualHours);
            obj.put("adjusted_start", adjustedStart != null ? adjustedStart : "");
            obj.put("adjusted_end", adjustedEnd != null ? adjustedEnd : "");
            obj.put("end_check_sent_at", endCheckSentAt);
            obj.put("adjusting_started_at", adjustingStartedAt);
            return obj;
        }

        public static ItemState fromJson(JSONObject obj) {
            ItemState s = new ItemState();
            s.itemId = obj.optString("item_id", "");
            try { s.state = State.valueOf(obj.optString("state", "PENDING")); }
            catch (IllegalArgumentException e) { s.state = State.PENDING; }
            s.plannedHours = obj.optDouble("planned_hours", 0);
            s.actualHours = obj.optDouble("actual_hours", -1);
            s.adjustedStart = obj.optString("adjusted_start", "");
            if (s.adjustedStart.isEmpty()) s.adjustedStart = null;
            s.adjustedEnd = obj.optString("adjusted_end", "");
            if (s.adjustedEnd.isEmpty()) s.adjustedEnd = null;
            s.endCheckSentAt = obj.optLong("end_check_sent_at", 0);
            s.adjustingStartedAt = obj.optLong("adjusting_started_at", 0);
            return s;
        }
    }

    // ─── 运行时状态 ───

    private final Context appContext;
    private final File stateFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private String currentDate;
    private List<ItemState> itemStates = new ArrayList<>();
    private boolean isComplete = false;

    // 超时常量（毫秒）
    static final long END_CHECK_TIMEOUT_MS = 15 * 60 * 1000;     // 15分钟
    static final long ADJUSTING_TIMEOUT_MS = 30 * 60 * 1000;     // 30分钟

    // ─── 单例 ───

    private static volatile ScheduleStateManager sInstance;

    public static ScheduleStateManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ScheduleStateManager.class) {
                if (sInstance == null) {
                    sInstance = new ScheduleStateManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private ScheduleStateManager(Context context) {
        this.appContext = context;
        File dir = new File(appContext.getFilesDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        this.stateFile = new File(dir, STATE_FILE);
        loadState();
    }

    // ─── 初始化今日状态 ───

    /**
     * 根据今日计划初始化状态（如果日期变了则重置）。
     */
    public synchronized void initForToday() {
        String today = dateFormat.format(Calendar.getInstance().getTime());

        // 同一天：从文件恢复
        if (today.equals(currentDate) && !itemStates.isEmpty()) {
            Log.d(TAG, "今日状态已初始化，从文件恢复: " + itemStates.size() + " 项");
            // 跳过已经过去且还在 PENDING 状态的事项
            skipPastPendingItems();
            return;
        }

        // 新的一天：用当日计划重新生成状态
        DailySchedule schedule = ScheduleDataManager.getInstance(appContext).getTodaySchedule();
        currentDate = today;
        itemStates.clear();

        for (ScheduleItem item : schedule.items) {
            ItemState s = new ItemState();
            s.itemId = item.id;
            s.plannedHours = item.plannedHours;
            s.adjustedStart = item.startTime;
            s.adjustedEnd = item.endTime;
            itemStates.add(s);
        }

        isComplete = false;
        saveState();
        skipPastPendingItems();
        Log.d(TAG, "初始化今日状态: " + itemStates.size() + " 项, date=" + today);
    }

    /**
     * 跳过已过去且仍在 PENDING 状态的事项（App 重启后）。
     */
    private void skipPastPendingItems() {
        Calendar now = Calendar.getInstance();
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        for (ItemState s : itemStates) {
            if (s.state != State.PENDING) continue;
            int endMin = effectiveEndMinutes(s);
            if (nowMin > endMin + 5) { // 结束时间已过 5 分钟（含跨午夜补偿）
                s.state = State.SKIPPED;
                Log.d(TAG, "跳过已过去事项: " + s.itemId);
            }
        }
        saveState();
    }

    // ─── 事件驱动：Alarm 触发时的处理 ───

    /**
     * 处理 Alarm 触发。调用方传入当前时间，返回本次应执行的动作。
     */
    public synchronized AlarmAction onAlarmTriggered() {
        Calendar now = Calendar.getInstance();

        // 检查是否有 END_CHECK_SENT 超时
        for (int i = 0; i < itemStates.size(); i++) {
            ItemState s = itemStates.get(i);
            if (s.state == State.END_CHECK_SENT
                    && s.endCheckSentAt > 0
                    && System.currentTimeMillis() - s.endCheckSentAt >= END_CHECK_TIMEOUT_MS) {
                s.state = State.COMPLETED;
                s.actualHours = s.plannedHours;  // 默认按计划
                saveState();
                Log.d(TAG, "超时自动完成: " + s.itemId);
                // 检查是否还有下一个事项
                return findNextAction(now, i + 1);
            }
            if (s.state == State.ADJUSTING
                    && s.adjustingStartedAt > 0
                    && System.currentTimeMillis() - s.adjustingStartedAt >= ADJUSTING_TIMEOUT_MS) {
                s.state = State.COMPLETED;
                saveState();
                Log.d(TAG, "调整超时自动完成: " + s.itemId);
                return findNextAction(now, i + 1);
            }
        }

        // 找第一个需要动作的事项
        return findNextAction(now, 0);
    }

    private AlarmAction findNextAction(Calendar now, int startFrom) {
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        for (int i = startFrom; i < itemStates.size(); i++) {
            ItemState s = itemStates.get(i);
            switch (s.state) {
                case PENDING: {
                    int startMin = timeToMinutes(getStartTime(s));
                    if (nowMin >= startMin) {
                        s.state = State.START_SENT;
                        saveState();
                        return AlarmAction.startReminder(i, s);
                    }
                    break;
                }
                case START_SENT: {
                    int endMin = timeToMinutes(getEndTime(s));
                    if (nowMin >= endMin) {
                        s.state = State.END_CHECK_SENT;
                        s.endCheckSentAt = System.currentTimeMillis();
                        saveState();
                        return AlarmAction.endCheck(i, s);
                    }
                    break;
                }
                case COMPLETED:
                case SKIPPED:
                    continue;
                default:
                    break;
            }
            // 如果这个事项还在等待中，不需要继续检查后面的（时间线顺序）
            if (s.state == State.PENDING || s.state == State.START_SENT) {
                break;
            }
        }
        return null;
    }

    // ─── 用户回复后的处理 ───

    /**
     * 用户回复了消息（用于 END_CHECK_SENT → ADJUSTING）。
     */
    public synchronized ItemState onUserReplied() {
        for (ItemState s : itemStates) {
            if (s.state == State.END_CHECK_SENT) {
                s.state = State.ADJUSTING;
                s.adjustingStartedAt = System.currentTimeMillis();
                saveState();
                return s;
            }
        }
        return null;
    }

    /**
     * AI 确认调整完成 → COMPLETED。
     */
    public synchronized void markCompleted(int itemIndex) {
        if (itemIndex >= 0 && itemIndex < itemStates.size()) {
            itemStates.get(itemIndex).state = State.COMPLETED;
            saveState();
        }
        // 检查是否全部完成
        checkAllComplete();
    }

    private void checkAllComplete() {
        for (ItemState s : itemStates) {
            if (s.state != State.COMPLETED && s.state != State.SKIPPED) return;
        }
        isComplete = true;
    }

    // ─── AlarmManager 调度 ───

    /**
     * 计算下一个需要触发 Alarm 的时间并设置。
     */
    public synchronized long scheduleNextAlarm() {
        Calendar now = Calendar.getInstance();
        long nextAlarmMs = 0;

        for (ItemState s : itemStates) {
            long alarmMs = getAlarmTimeForItem(s, now);
            if (alarmMs > now.getTimeInMillis() && (nextAlarmMs == 0 || alarmMs < nextAlarmMs)) {
                nextAlarmMs = alarmMs;
            }
        }

        if (nextAlarmMs > 0) {
            setAlarm(nextAlarmMs);
        }

        Log.d(TAG, "设置下一个闹钟: " + (nextAlarmMs > 0
                ? new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(nextAlarmMs)
                : "无"));
        return nextAlarmMs;
    }

    private long getAlarmTimeForItem(ItemState s, Calendar now) {
        switch (s.state) {
            case PENDING: {
                // 计算 startTime 对应的今天毫秒值
                int[] hm = parseTime(getStartTime(s));
                Calendar alarm = (Calendar) now.clone();
                alarm.set(Calendar.HOUR_OF_DAY, hm[0]);
                alarm.set(Calendar.MINUTE, hm[1]);
                alarm.set(Calendar.SECOND, 0);
                alarm.set(Calendar.MILLISECOND, 0);
                if (alarm.getTimeInMillis() <= now.getTimeInMillis()) return 0;
                return alarm.getTimeInMillis();
            }
            case START_SENT: {
                int[] hm = parseTime(getEndTime(s));
                Calendar alarm = (Calendar) now.clone();
                alarm.set(Calendar.HOUR_OF_DAY, hm[0]);
                alarm.set(Calendar.MINUTE, hm[1]);
                alarm.set(Calendar.SECOND, 0);
                alarm.set(Calendar.MILLISECOND, 0);
                // 跨午夜（如 22:00→02:00）：如果结束时间在今天已经过了，加一天
                String startTime = getStartTime(s);
                if (!startTime.isEmpty()) {
                    int startH = parseTime(startTime)[0];
                    if (hm[0] < startH && alarm.getTimeInMillis() <= now.getTimeInMillis()) {
                        alarm.add(Calendar.DAY_OF_YEAR, 1);
                    }
                }
                if (alarm.getTimeInMillis() <= now.getTimeInMillis()) return 0;
                return alarm.getTimeInMillis();
            }
            case END_CHECK_SENT:
                if (s.endCheckSentAt > 0) return s.endCheckSentAt + END_CHECK_TIMEOUT_MS;
                break;
            case ADJUSTING:
                if (s.adjustingStartedAt > 0) return s.adjustingStartedAt + ADJUSTING_TIMEOUT_MS;
                break;
            default:
                break;
        }
        return 0;
    }

    private void setAlarm(long timeMs) {
        AlarmManager alarmMgr = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmMgr == null) return;

        Intent intent = new Intent(appContext, ScheduleSecretaryService.class);
        intent.setAction(ScheduleSecretaryService.ACTION_ALARM);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getService(appContext, 0, intent, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pending);
        } else {
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, timeMs, pending);
        }
    }

    /** 取消已设置的 Alarm */
    public synchronized void cancelAlarm() {
        AlarmManager alarmMgr = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmMgr == null) return;
        Intent intent = new Intent(appContext, ScheduleSecretaryService.class);
        intent.setAction(ScheduleSecretaryService.ACTION_ALARM);
        PendingIntent pending = PendingIntent.getService(appContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmMgr.cancel(pending);
    }

    // ─── 查询方法 ───

    /** 当前应该被回访/正在调整的事项索引 */
    public synchronized int getCurrentItemIndex() {
        for (int i = 0; i < itemStates.size(); i++) {
            State st = itemStates.get(i).state;
            if (st == State.END_CHECK_SENT || st == State.ADJUSTING) return i;
        }
        return -1;
    }

    /** 获取事项的运行状态（先查运行时调整，否则用原计划） */
    public synchronized ItemState getItemState(int index) {
        if (index >= 0 && index < itemStates.size()) return itemStates.get(index);
        return null;
    }

    public synchronized List<ItemState> getAllStates() {
        return new ArrayList<>(itemStates);
    }

    public synchronized boolean isTodayComplete() {
        return isComplete;
    }

    public synchronized String getCurrentDate() {
        return currentDate;
    }

    /** 生成当前状态文本供 AI 使用 */
    public synchronized String buildStateContext(DailySchedule schedule) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 执行进度：\n");
        for (int i = 0; i < itemStates.size(); i++) {
            ItemState s = itemStates.get(i);
            String name = (i < schedule.items.size()) ? schedule.items.get(i).taskName : "";
            sb.append(i + 1).append(". ").append(name)
                    .append(" (").append(getStartTime(s)).append("-").append(getEndTime(s)).append(")");
            switch (s.state) {
                case PENDING: sb.append(" — 等待开始"); break;
                case START_SENT: sb.append(" — 进行中"); break;
                case END_CHECK_SENT: sb.append(" — 等待确认"); break;
                case ADJUSTING: sb.append(" — 正在调整"); break;
                case COMPLETED:
                    sb.append(" — 已完成");
                    if (s.actualHours > 0) sb.append("（实际").append(s.actualHours).append("h）");
                    break;
                case SKIPPED: sb.append(" — 已跳过"); break;
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ─── 辅助方法 ───

    private String getStartTime(ItemState s) {
        return (s.adjustedStart != null) ? s.adjustedStart : "";
    }

    private String getEndTime(ItemState s) {
        return (s.adjustedEnd != null) ? s.adjustedEnd : "";
    }

    /** "HH:MM" → 分钟数，跨午夜（如 22:00→02:00）自动加 24h */
    static int timeToMinutes(String time) {
        if (time == null || time.isEmpty()) return Integer.MAX_VALUE;
        int[] hm = parseTime(time);
        return hm[0] * 60 + hm[1];
    }

    /** 计算结束时间的有效分钟数，处理跨午夜场景。
     *  如果 endHour < startHour（如 start 22:00, end 02:00），end 加 24 小时 */
    private int effectiveEndMinutes(ItemState s) {
        String start = getStartTime(s);
        String end = getEndTime(s);
        if (start.isEmpty() || end.isEmpty()) return timeToMinutes(end);
        int startH = parseTime(start)[0];
        int endH = parseTime(end)[0];
        int endMin = timeToMinutes(end);
        if (endH < startH) endMin += 24 * 60; // 跨午夜
        return endMin;
    }

    /** 解析 "HH:MM" → [hour, minute]，支持 "02:00" 表示凌晨2点 */
    static int[] parseTime(String time) {
        try {
            String[] parts = time.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            return new int[]{h, m};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    // ─── 持久化 ───

    private synchronized void saveState() {
        try {
            JSONObject root = new JSONObject();
            root.put("date", currentDate);
            root.put("is_complete", isComplete);
            JSONArray arr = new JSONArray();
            for (ItemState s : itemStates) {
                arr.put(s.toJson());
            }
            root.put("items", arr);

            try (FileOutputStream fos = new FileOutputStream(stateFile)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "保存状态失败", e);
        }
    }

    private void loadState() {
        if (!stateFile.exists()) return;

        try {
            String json = readFile(stateFile);
            JSONObject root = new JSONObject(json);
            currentDate = root.optString("date", "");
            isComplete = root.optBoolean("is_complete", false);

            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                itemStates.clear();
                for (int i = 0; i < arr.length(); i++) {
                    itemStates.add(ItemState.fromJson(arr.getJSONObject(i)));
                }
            }
            Log.d(TAG, "加载状态: " + itemStates.size() + " 项, date=" + currentDate);
        } catch (Exception e) {
            Log.e(TAG, "加载状态失败", e);
        }
    }

    private String readFile(File file) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ─── AlarmAction（动作描述） ───

    public static class AlarmAction {
        public enum Type { START_REMINDER, END_CHECK, TIMEOUT }

        public Type type;
        public int itemIndex;
        public ItemState itemState;

        public AlarmAction(Type type, int itemIndex, ItemState itemState) {
            this.type = type;
            this.itemIndex = itemIndex;
            this.itemState = itemState;
        }

        public static AlarmAction startReminder(int index, ItemState state) {
            return new AlarmAction(Type.START_REMINDER, index, state);
        }

        public static AlarmAction endCheck(int index, ItemState state) {
            return new AlarmAction(Type.END_CHECK, index, state);
        }
    }
}
