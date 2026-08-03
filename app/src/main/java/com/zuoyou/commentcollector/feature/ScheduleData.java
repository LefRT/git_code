package com.zuoyou.commentcollector.feature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 日程秘书 — 数据模型。
 */
public class ScheduleData {

    /**
     * 一个日程事项。
     */
    public static class ScheduleItem {
        public String id;
        public String startTime;
        public String endTime;
        public double plannedHours;
        public String taskName;
        public String category;
        public int priority;

        public ScheduleItem() {}

        public ScheduleItem(String id, String startTime, String endTime,
                            double plannedHours, String taskName, String category, int priority) {
            this.id = id;
            this.startTime = startTime;
            this.endTime = endTime;
            this.plannedHours = plannedHours;
            this.taskName = taskName;
            this.category = category;
            this.priority = priority;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("start_time", startTime);
            obj.put("end_time", endTime);
            obj.put("planned_hours", plannedHours);
            obj.put("task_name", taskName);
            obj.put("category", category);
            obj.put("priority", priority);
            return obj;
        }

        public static ScheduleItem fromJson(JSONObject obj) {
            ScheduleItem item = new ScheduleItem();
            item.id = obj.optString("id", "");
            item.startTime = obj.optString("start_time", "");
            item.endTime = obj.optString("end_time", "");
            item.plannedHours = obj.optDouble("planned_hours", 0);
            item.taskName = obj.optString("task_name", "");
            item.category = obj.optString("category", "学习");
            item.priority = obj.optInt("priority", 1);
            return item;
        }
    }

    /**
     * 一天的计划（固定项 + 当天轮换项）。
     */
    public static class DailySchedule {
        public String date;
        public List<ScheduleItem> items;

        public DailySchedule() {
            items = new ArrayList<>();
        }

        public DailySchedule(String date, List<ScheduleItem> items) {
            this.date = date;
            this.items = items;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("date", date);
            JSONArray arr = new JSONArray();
            for (ScheduleItem item : items) {
                arr.put(item.toJson());
            }
            obj.put("items", arr);
            return obj;
        }

        public static DailySchedule fromJson(JSONObject obj) {
            DailySchedule schedule = new DailySchedule();
            schedule.date = obj.optString("date", "");
            JSONArray arr = obj.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    schedule.items.add(ScheduleItem.fromJson(arr.optJSONObject(i)));
                }
            }
            return schedule;
        }

        /** 生成供 AI 使用的日程文本 */
        public String toContextString() {
            StringBuilder sb = new StringBuilder();
            sb.append("📅 今日计划（").append(date).append("）：\n");
            for (int i = 0; i < items.size(); i++) {
                ScheduleItem item = items.get(i);
                sb.append(i + 1).append(". ")
                        .append(item.startTime).append("-").append(item.endTime)
                        .append(" | ").append(item.taskName)
                        .append(" | 计划").append(formatHours(item.plannedHours));
                if (item.priority >= 2) sb.append(" | ⭐重要");
                sb.append("\n");
            }
            return sb.toString();
        }

        private String formatHours(double h) {
            if (h == (int) h) return (int) h + "h";
            return h + "h";
        }
    }
}
