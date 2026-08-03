package com.zuoyou.commentcollector.feature;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import com.zuoyou.commentcollector.feature.ScheduleData.DailySchedule;
import com.zuoyou.commentcollector.feature.ScheduleData.ScheduleItem;

/**
 * 日程数据管理器 — 单例。
 * <p>
 * 首次启动从 assets 拷贝 schedule_data.json → filesDir/daily_plan/,
 * 之后按日期 + 星期几轮换生成每日计划。
 */
public class ScheduleDataManager {

    private static final String TAG = "ScheduleData";
    private static final String DIR_NAME = "daily_plan";
    private static final String DATA_FILE = "schedule_data.json";

    private final Context appContext;
    private final File dataDir;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    // 原始数据
    private List<ScheduleItem> fixedItems = new ArrayList<>();
    private JSONObject rotations = new JSONObject();
    private String startDate;
    private String endDate;

    // ─── 单例 ───

    private static volatile ScheduleDataManager sInstance;

    public static ScheduleDataManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ScheduleDataManager.class) {
                if (sInstance == null) {
                    sInstance = new ScheduleDataManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private ScheduleDataManager(Context context) {
        this.appContext = context;
        this.dataDir = new File(appContext.getFilesDir(), DIR_NAME);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        initDataFile();
        loadData();
    }

    /**
     * 首次启动时从 assets 拷贝数据文件。
     */
    private void initDataFile() {
        File file = new File(dataDir, DATA_FILE);
        if (file.exists()) return;

        try (InputStream is = appContext.getAssets().open("daily_plan/" + DATA_FILE);
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            Log.d(TAG, "日程数据已从 assets 初始化");
        } catch (Exception e) {
            Log.e(TAG, "初始化日程数据失败", e);
        }
    }

    private void loadData() {
        File file = new File(dataDir, DATA_FILE);
        if (!file.exists()) {
            Log.w(TAG, "日程数据文件不存在");
            return;
        }

        try {
            String json = readFile(file);
            JSONObject root = new JSONObject(json);

            // 固定项
            JSONArray fixedArr = root.optJSONArray("fixed_items");
            if (fixedArr != null) {
                fixedItems.clear();
                for (int i = 0; i < fixedArr.length(); i++) {
                    fixedItems.add(ScheduleItem.fromJson(fixedArr.getJSONObject(i)));
                }
            }

            rotations = root.optJSONObject("rotations");
            startDate = root.optJSONObject("dates").optString("start", "");
            endDate = root.optJSONObject("dates").optString("end", "");

            Log.d(TAG, "日程数据加载完成: " + fixedItems.size() + " 固定项, "
                    + (rotations != null ? rotations.length() : 0) + " 天轮换");
        } catch (Exception e) {
            Log.e(TAG, "加载日程数据失败", e);
        }
    }

    /**
     * 获取今天的完整计划（含星期轮换）。
     */
    public DailySchedule getTodaySchedule() {
        Calendar cal = Calendar.getInstance();
        String today = dateFormat.format(cal.getTime());

        // 星期几 → 中文名
        int dow = cal.get(Calendar.DAY_OF_WEEK); // 1=周日, 2=周一...7=周六
        String dayName;
        switch (dow) {
            case Calendar.MONDAY:    dayName = "周一"; break;
            case Calendar.TUESDAY:   dayName = "周二"; break;
            case Calendar.WEDNESDAY: dayName = "周三"; break;
            case Calendar.THURSDAY:  dayName = "周四"; break;
            case Calendar.FRIDAY:    dayName = "周五"; break;
            case Calendar.SATURDAY:  dayName = "周六"; break;
            default:                 dayName = "周日"; break;
        }

        List<ScheduleItem> items = new ArrayList<>(fixedItems);

        // 添加轮换项
        if (rotations != null) {
            JSONArray rotArr = rotations.optJSONArray(dayName);
            if (rotArr != null) {
                for (int i = 0; i < rotArr.length(); i++) {
                    items.add(ScheduleItem.fromJson(rotArr.optJSONObject(i)));
                }
            }
        }

        Log.d(TAG, "生成 " + today + "（" + dayName + "）计划: " + items.size() + " 项");
        return new DailySchedule(today, items);
    }

    /**
     * 检查当前日期是否在计划范围内。
     */
    public boolean isInRange() {
        if (startDate == null || endDate == null) return true;
        Calendar cal = Calendar.getInstance();
        String today = dateFormat.format(cal.getTime());
        return today.compareTo(startDate) >= 0 && today.compareTo(endDate) <= 0;
    }

    /**
     * 重新加载数据（从 assets 覆盖，保留已完成的进度状态）。
     */
    public void reloadFromAssets() {
        File file = new File(dataDir, DATA_FILE);
        file.delete();
        initDataFile();
        loadData();
        Log.d(TAG, "日程数据已重新加载");
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
            Log.e(TAG, "读取文件失败: " + file.getName(), e);
            return "";
        }
    }
}
