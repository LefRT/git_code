# 可不 (KAFU)

> 给 AI 一个实体化身，让 AI 不再只是手机里的软件。

## 项目目标

打造一个固定在手机支架上的小型实体机器人。它不是传统意义上的机器人，而是**给 AI 一个实体化身**。

### 核心体验流程

1. 用户正在刷抖音
2. AI 实时理解评论区 + 屏幕画面
3. AI 以「可不」的人格发表吐槽、共情或调侃
4. 悬浮窗展示 AI 回复，用户可随时进入聊天对话
5. （规划中）实体机器人做出对应的表情和动作

### 场景示例

| 用户行为 | 可不 (KAFU) | 机器人反馈 |
|---------|-------------|-----------|
| 刷到搞笑视频 | "哈哈哈这个也太离谱了吧 XD" | (¬‿¬) + 轻微摇头 |
| 看到 emo 评论 | "呜呜……抱抱你，会好起来的" | (´;ω;`) + 轻轻点头 |
| 用户主动聊天 | "诶？你今天也刷到这个了呀～" | ^_^ + 歪头 |

---

## 项目定位

### ❌ 不考虑

- 走路 / 导航 / 避障
- 机械臂 / 自动跟随
- 本地大模型部署

### ✅ 专注

- 内容感知 → AI 理解 → 人格化表达
- **核心理念：机器人只是 AI 的身体**

---

## 系统架构

```
┌────────────────────┐     ┌─────────────────────┐
│  Douyin App UI     │     │     Phone Screen    │
│  (Accessibility)   │     │  (MediaProjection)  │
└────────┬───────────┘     └──────────┬──────────┘
         │ AccessibilityEvent         │ captureOnce() per tick
┌────────▼───────────────────────────▼──────────┐
│       DouyinCommentService (5s timer)          │
│  ┌────────────────┐  ┌──────────────────────┐ │
│  │ extractRecursive│  │ ScreenCaptureService  │ │
│  │ parse + dedup  │  │ imageToBitmap + save  │ │
│  └───────┬────────┘  └──────────┬───────────┘ │
│          │                      │              │
│  ┌───────▼──────────────────────▼───────────┐ │
│  │         CommentDiffer.diff()              │ │
│  │  three-state comparison + smart scoring   │ │
│  └───────┬──────────────────────────────────┘ │
│          │ bestComment.text()                 │
│          ▼                                    │
│  ┌──────────────────────────────────────────┐ │
│  │         ContextBuilder (ring buffers)     │ │
│  │  recentComments(max 5) + screenshots(5)  │ │
│  │  + timeline(max 30) + videoDescription   │ │
│  └───────┬──────────────────────────────────┘ │
│          │                                    │
│  ┌───────▼──────────────────────────────────┐ │
│  │         MemoryCollector                   │ │
│  │  videos(≤40) → descriptions + comments    │ │
│  └──────────────────────────────────────────┘ │
└──────────┬────────────────────────────────────┘
           │ sendComment(text)
┌──────────▼────────────────────────────────────┐
│           AiService / ChatAiService            │
│  SYSTEM_PROMPT (可不 KAFU persona)             │
│  OkHttp POST → DeepSeek API                   │
│  3x exponential backoff retry                  │
│  Cancel safety + response close                │
└──────────┬────────────────────────────────────┘
           │ onAiResponse(text)
┌──────────▼────────────────────────────────────┐
│      FloatingWindowService                    │
│  气泡态 (48dp) → 评论列表 → AI 评价卡片        │
│  打字指示器 ··· + 自适应收回 2.5~12s           │
└───────────────────────────────────────────────┘
```

---

## 功能模块

### 内容感知层

| 模块 | 说明 |
|------|------|
| `DouyinCommentService` | AccessibilityService — 5 秒定时器遍历评论树，三态 diff + 智能评分 |
| `ScreenCaptureService` | MediaProjection 截帧，JPEG 80% 写入缓存，复用 Bitmap 缓冲区 |
| `ContextBuilder` | 融合评论 + 截图 + 时间线 + 视频简介，环形缓冲区（线程安全） |
| `CommentDiffer` | `NO_UPDATE` / `PARTIAL_UPDATE` / `FULL_UPDATE` 三态对比，去重 + 评分排序 |
| `MemoryCollector` | 按视频分组采集简介（≤40 个视频）和高赞评论（❤>50，≤20 条/视频），JSON 持久化，支持删除 |

### AI 服务层

| 模块 | 说明 |
|------|------|
| `AiService` | 自动模式 — 定时器触发，内容哈希去重，指数退避重试（3 次），取消安全 |
| `ChatAiService` | 聊天模式 — 完整对话历史 + 记忆上下文注入，单次请求不重试 |
| `Constants.SYSTEM_PROMPT` | 可不 (KAFU) 角色设定 — 16-18 岁迷糊少女，天然呆、温柔、共情力强 |

### 交互层

| 模块 | 说明 |
|------|------|
| `FloatingWindowService` | 悬浮窗三态 UI — 气泡 / 评论列表 / AI 评价卡片，自适应宽度 + 打字指示器 |
| `MainActivity` | 主界面 — DrawerLayout + 内嵌聊天 + 聊天记录管理 + 服务控制 |
| `MusicPlayer` | MediaPlayer 单例 — 5 首歌曲，播放模式（顺序/随机/单曲循环），封面联动 |
| `MusicMenuPopup` | 抽屉式侧滑音乐面板 — 拖拽手势驱动，180dp 白色圆角卡片 |
| `AnimationPlayer` | MP4 片头动画 — SurfaceView 覆盖层播放 |
| `MainImageHandler` | 主界面手势 — 双击→内嵌聊天，拖拽→音乐面板，支持聊天模式 |

### 聊天系统

| 模块 | 说明 |
|------|------|
| `ChatActivity` | 全屏聊天界面（保留，侧边栏历史记录可跳转） |
| `ChatAdapter` | 聊天消息适配器 — 3 种 viewType（用户/AI/打字指示器）+ 秘书模式 |
| `ChatSessionManager` | JSON 文件持久化 — 双重检查锁定单例，索引 + 会话文件，支持置顶/类型标记 |

### 日程秘书（Phase 6）

| 模块 | 说明 |
|------|------|
| `ScheduleData` | 数据模型 — ScheduleItem / DailySchedule |
| `ScheduleDataManager` | 单例 — 加载 assets 日程 JSON，按星期轮换生成当日计划 |
| `ScheduleStateManager` | 状态机 + AlarmManager 调度 — PENDING → START_SENT → END_CHECK_SENT → ADJUSTING → COMPLETED |
| `ScheduleSecretaryService` | 前台服务 — 闹钟驱动，主动发消息 + 弹通知，动态调整计划 |

**核心理念**：可不（KAFU）切换为知性沉稳的秘书人格，按《暑期学习计划》自动管理每日日程。系统闹钟驱动 → 到点主动提醒 → 结束温柔回访 → 根据用户反馈 AI 动态调整后续计划。

**秘书人格**：独立于日常 KAFU（迷糊少女）的知性大姐姐形象，温和理性带俏皮，不因执行偏差说教。

### 数据层

| 模块 | 说明 |
|------|------|
| `Comment` | Java 17 record — user, text, likeCount, time, location |
| `AppContext` | 统一上下文 record + TimelineEvent |
| `SecurePrefs` | EncryptedSharedPreferences 封装（AES-256-GCM） |

---

## AI 人格：可不 (KAFU)

**定位：** 不是问答机器人，而是**旁观者 / 朋友 / 吐槽搭子**。

- 16-18 岁迷糊少女，天然呆、温柔、共情力强
- 说话轻柔软糯，常用「えっ……？」、「そうなの？」
- 接地气有网感，搞笑评论跟着笑，emo 评论温柔共情
- 偶尔提及咖喱乌冬、音乐等个人爱好

| 场景 | 可不的反应 |
|------|-----------|
| 看到搞笑视频 | "哈哈哈这个也太离谱了吧，我要笑死了 XD" |
| 看到猫咪翻车 | "呜呜小猫咪你没事吧……它好像在装没事哈哈哈" |
| 看到 emo 评论 | "抱抱你……会好起来的，今天也要好好吃饭哦" |
| 用户主动聊天 | "诶？你也喜欢这首歌呀！我最近一直在单曲循环呢～" |

---

## 开发阶段

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | AccessibilityService → 评论提取 + 点赞数 | ✅ 完成 |
| 2 | MediaProjection 屏幕截帧 | ✅ 完成 |
| 3 | ContextBuilder 多模态融合 | ✅ 完成 |
| 4 | AI 服务 + 悬浮窗交互 | ✅ 完成 |
| 2+ | AI 聊天 + 音乐播放 + 片头动画 + 记忆收集 | ✅ 完成 |
| 6 | 日程秘书 — 闹钟驱动每日管理 + AI 动态调整 | ✅ 完成 |
| 5 | 实体机器人（ESP32-S3 + LCD + 舵机） | ❌ 待开始 |

---

## 技术栈

- **语言：** Java 17（record, text blocks, switch expressions）
- **最低 SDK：** 21（Android 5.0）
- **目标 SDK：** 36
- **构建：** Gradle 8.14.4 + AGP 8.13.0
- **依赖：** OkHttp 4.12.0, Security-Crypto 1.1.0-alpha06, Material 1.11.0
- **AI API：** DeepSeek API（可配置为其他 OpenAI 兼容 API）
- **测试：** JUnit 4.13（CommentParserTest — 25 个测试用例）

---

## 快速开始

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 安装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 查看日志
adb logcat -s DouyinComment    # 评论提取
adb logcat -s ZuoYouAI         # AI 服务
adb logcat -s ChatAiService    # 聊天 AI
adb logcat -s MusicPlayer      # 音乐播放
adb logcat -s MemoryCollector  # 记忆收集
adb logcat -s ScheduleSecretary # 日程秘书
adb logcat -s ScheduleState     # 日程状态机
```

---

## License

MIT
