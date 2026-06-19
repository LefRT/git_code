# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**可不 (KAFU)** — an Android app that gives AI a physical embodiment. The app uses Android AccessibilityService to extract content from Douyin (抖音) in real-time and MediaProjection to capture screen frames, fused into a structured context for downstream AI processing. The AI persona is 「可不（KAFU）」, a gentle, clumsy anime girl who comments on Douyin videos with warmth and humor.

The ultimate vision: a physical desktop robot (ESP32-S3 + LCD + servo) that reacts to what the user is watching with expressions, comments, and movements.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View comment extraction output
adb logcat -s DouyinComment

# View screen capture output
adb logcat -s ScreenCapture

# View fused context (Phase 3)
adb logcat -s ZuoYouContext

# View AI service logs
adb logcat -s ZuoYouAI

# View floating window logs
adb logcat -s ZuoYouFloat

# View chat service logs
adb logcat -s ChatAiService

# View music player logs
adb logcat -s MusicPlayer

# View animation logs
adb logcat -s AnimationPlayer

# View memory collector logs
adb logcat -s MemoryCollector

# Clear logcat buffer
adb logcat -c

# List connected devices
adb devices
```

**SDK Location**: Configured in `local.properties` (`sdk.dir=C\:\\Android\\Sdk`). Update this path if building on a different machine.

## Project Configuration

- **AGP**: 8.13.0
- **compileSdk / targetSdk**: 36
- **minSdk**: 21
- **Java**: 17 (source & target compatibility)
- **Namespace / applicationId**: `com.zuoyou.commentcollector`
- **Test framework**: JUnit 4.13
- **Dependencies**: appcompat 1.6.1, Material 1.11.0, ConstraintLayout 2.1.4, OkHttp 4.12.0, Security-Crypto 1.1.0-alpha06

## Project Files

```
app/src/main/java/com/zuoyou/commentcollector/
├── AppContext.java              # Phase 3 — unified context data record + TimelineEvent
├── Comment.java                 # Phase 1 — data record (user, text, likeCount, time, location)
├── CommentCollector.java        # Phase 1 — tree traversal + parsing + JSON (extracted from service)
├── CommentDiffer.java           # Comment diff algorithm + smart scoring
├── CommentParser.java           # Phase 1 — pure-Java contentDescription parser
├── Constants.java               # Global constants (prefs keys, package names, defaults, SYSTEM_PROMPT)
├── ContextBuilder.java          # Phase 3 — fused pipeline (comments + screenshots + timeline + video description)
├── DouyinCommentService.java    # AccessibilityService — 5s timer + three-state diff + AI trigger + video description extraction + memory collection
├── ScreenCaptureService.java    # Phase 2 — MediaProjection screen capture service
├── SecurePrefs.java             # EncryptedSharedPreferences wrapper for API Key storage
├── AiService.java               # DeepSeek API client (comment evaluation, retry + cancel safety)
├── FloatingWindowService.java   # System overlay — bubble / comment list / evaluation card
├── SettingsActivity.java        # Accessibility service toggle + API key config
├── MainActivity.java            # Launcher — DrawerLayout with service controls + chat history + memory toggle
└── feature/
    ├── ChatActivity.java        # 全屏聊天界面 — RecyclerView + 消息气泡 + 打字指示器
    ├── ChatAiService.java       # 聊天 AI 服务 — 完整对话历史 + 记忆上下文注入
    ├── ChatSessionManager.java  # JSON 文件持久化聊天记录（索引 + 会话文件）
    ├── MainImageHandler.java    # 主界面手势 — 双击→动画→聊天，左滑→音乐菜单
    ├── AnimationPlayer.java     # MP4 片头动画 — SurfaceView 覆盖层播放
    ├── MusicPlayer.java         # 音乐播放器 — MediaPlayer 单例 + 多监听器 + 封面联动
    ├── MusicMenuPopup.java      # 音乐菜单弹窗 — PopupWindow 歌曲列表
    └── MemoryCollector.java     # 记忆收集器 — 采集视频简介 + 高赞评论 → AI 上下文注入

app/src/test/java/com/zuoyou/commentcollector/
└── CommentParserTest.java       # Unit tests for CommentParser (25 test cases)
```

## How Comment Extraction Works (current implementation)

1. **5-second timer**: `DouyinCommentService` runs a `Handler.postDelayed` timer that fires every 5 seconds. On each tick it calls `extractComments()` to walk the accessibility tree, then `CommentDiffer.diff()` to compare with the previous set, then sends the best-scored comment to AI.

2. **Three-state diff** (`CommentDiffer.diff()`):
   - `NO_UPDATE` — no new comments, skip AI call
   - `PARTIAL_UPDATE` — some new comments appended, send best new one
   - `FULL_UPDATE` — all comments changed (video switch), send best new one
   - Dedup by `(user, text)` key; only truly new comments are considered

3. **Smart scoring** (`CommentDiffer.score()`):
   ```
   score = 0.6 × log(likes+1)/log(maxLikes+2) + 0.4 × min(textLen,50)/50
   ```
   Balances popularity (likes) with content richness (text length). The highest-scoring new comment is sent to AI.

4. **`extractRecursive()`** walks the AccessibilityNodeInfo tree. It identifies comment items by finding `FrameLayout` nodes whose `contentDescription` contains `"回复 按钮"`. Each child is recycled at the end of its iteration in the `for` loop.

5. **`CommentParser.parseFromDescription()`** parses the `contentDescription` string (format: `user,text,time, · location,回复 按钮,`) by:
   - Stripping the trailing `"回复 按钮,"` suffix
   - Searching only the **last 2/3** of the string for a timestamp regex
   - Using the last-found timestamp position as an anchor to split user+text from time+location
   - Returns a `Comment` record (user, text, likeCount=0, time, location)

6. **`findLikeCount()`** uses a dual strategy:
   - **First**: checks if the current node is a `TextView` containing 1–6 digits, and if its parent's `contentDescription` contains `"赞"`
   - **Fallback**: recursively searches all child nodes with the same logic

7. **Deduplication**: Extracted comments are deduplicated by `(user, text)` key. A `LinkedHashMap` acts as a sliding window of 100 recent entries, automatically evicting the oldest.

8. **Screen capture**: `ScreenCaptureService.captureOnce()` is called once per timer tick (static method, called by `DouyinCommentService`), replacing the previous internal 3-second timer.

### Key insights

**Douyin's comment content is NOT in `TextView.text`** — it's in `FrameLayout.contentDescription` as a comma-separated string:

```
MiSS,我有六万存款，月薪七千，能结婚不,昨天19:09, · 广东,回复 按钮,
```

**Node lifecycle is critical**: `extractRecursive` recycles its children via `try-finally` in the `for` loop. `findLikeCount` also recycles all `getChild()`/`getParent()` wrappers in its own `try-finally` blocks. Never use a node after its `recycle()` call, and never recycle a node twice.

## Architecture

### Current Architecture (Phase 1-4)

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
│  │  + timeline(max 30)                      │ │
│  └───────┬──────────────────────────────────┘ │
│          │ pushCommentsSilent()               │
└──────────┼────────────────────────────────────┘
           │ sendComment(text)
┌──────────▼────────────────────────────────────┐
│           AiService                           │
│  SYSTEM_PROMPT (可不 KAFU persona)             │
│  content hash dedup + cancel safety            │
│  OkHttp POST → DeepSeek API                   │
│  3x exponential backoff retry (1s/2s/4s)      │
│  Cancelled request StreamResetException handled│
└──────────┬────────────────────────────────────┘
           │ onAiResponse(text)
┌──────────▼────────────────────────────────────┐
│      FloatingWindowService                    │
│  气泡态 (48dp) → 评论列表 (点击评价)           │
│  → AI 评价卡片 (自适应宽度 200~360dp)          │
│  气泡始终可见，卡片从气泡下方展开                │
│  打字指示器 ··· + 自适应收回 2.5~12s          │
│  初始位置: 左侧边 + 垂直30%                    │
│  消息缓冲 (max 3) + 边缘吸附                  │
└───────────────────────────────────────────────┘
```

### Planned: Phase 5 — Physical Robot

```
┌─────────────────────┐
│     Android APP     │
├─────────────────────┤
│ • 内容感知层        │  ← Phase 1-3
│ • AI 接口层         │  ← Phase 4
│ • 悬浮窗交互        │  ← Phase 4
└──────────┬──────────┘
           │ WiFi
┌──────────▼──────────┐
│     实体机器人      │
├─────────────────────┤
│ • 表情屏 (LCD/OLED) │
│ • 舵机控制          │
│ • 灯光系统          │
└─────────────────────┘
```

### Development Phases

| Phase | Focus | Status |
|-------|-------|--------|
| 1 | AccessibilityService → comment extraction with likes | ✅ Done |
| 2 | MediaProjection (screen capture) | ✅ Done |
| 3 | Context Builder (fuse text + vision via ring buffers) | ✅ Done |
| 4 | AI service + floating window (comment list + evaluation) | ✅ Done |
| 2+ | AI Chat + Music Player + Animation + Memory | ✅ Done (UI待调) |
| 5 | Physical robot (ESP32-S3) | ❌ Pending |

### Optimization history (2026-06-16)

- **Java 8 → 17**: Records, text blocks, switch expressions available
- **Comment → record**: Immutable, added `time` and `location` fields
- **Event debounce**: 500ms minimum interval for `TYPE_WINDOW_CONTENT_CHANGED`
- **Comment dedup**: `LinkedHashMap` sliding window (100 entries)
- **CommentCollector extraction**: Decoupled tree traversal + JSON from AccessibilityService
- **Exception safety**: All node traversal wrapped in try-catch
- **Thread safety**: `isRunning` → `volatile`, synchronized ring buffers
- **UI**: ConstraintLayout + MaterialButton + colors.xml
- **API migration**: `requestPermissions()` → `ActivityResultLauncher`
- **ProGuard**: Rules for JSON/Service/Callback classes

### Bug fixes (2026-06-17) — code review round

- **AccessibilityNodeInfo leak fix**: `findLikeCount` and `collectRecursive` now use `try-finally` to guarantee `recycle()` on all `getChild()`/`getParent()` wrappers.
- **compress() return check**: `saveBitmapToCache()` now checks `bitmap.compress()` return value.
- **Data race fix**: `totalCommentCount` read in `buildContext()` is now inside `synchronized(recentComments)`.
- **Dedup key collision fix**: CommentCollector changed to `Objects.hash(user, text)` (CommentDiffer was fixed later on 2026-06-19).
- **Debounce completeness**: `TYPE_WINDOW_STATE_CHANGED` is now also debounced.
- **JSON exception logging**: `AppContext.toJson()` now logs the exception via `Log.e`.
- **I/O reduction**: `cleanupOldCaptures()` uses `mCaptureCount` counter to skip filesystem checks.
- **Dead allocation removed**: `CommentCollector.collect()` return type changed to `void`.

### Improvements (2026-06-17) — Phase 4 hardening + test coverage

- **Unit tests**: `CommentParserTest.java` (25 test cases)
- **API retry**: Exponential backoff (1s/2s/4s), up to 3 attempts on 429/500/502/503/504
- **Screen capture scoped to Douyin**: `setDouyinForeground(boolean)` controls capture
- **AI error feedback**: `Toast` on main thread for API/network errors
- **Android 14+ property tag**: FloatingWindowService manifest compliance
- **MediaProjection null handling**: `stopSelf()` when projection is null
- **Floating window message buffer**: Buffers up to 3 AI replies when service not running
- **Encrypted API Key storage**: `SecurePrefs` wraps `EncryptedSharedPreferences` (AES-256-GCM)
- **Bitmap reuse**: Pre-allocated `reusableBitmap` with `setPixels()` avoids per-frame allocation

### Redesign (2026-06-19) — Timer-driven architecture + AI simplification

**Timer-driven comment extraction:**
- Replaced event-driven collection with 5-second `Handler.postDelayed` timer
- Three-state comment diff (`CommentDiffer`): `NO_UPDATE` / `PARTIAL_UPDATE` / `FULL_UPDATE`
- Smart scoring formula: `0.6 × log(likes+1)/log(maxLikes+2) + 0.4 × min(textLen,50)/50`
- Only the best-scored new comment is sent to AI per cycle

**AI personality system removed:**
- Deleted `AiPersonality.java` entirely (ROAST/GENTLE/FUNNY modes were ineffective)
- Fixed `SYSTEM_PROMPT` constant — 抖音搭子风格, no emotion in response
- API response is plain text (no JSON parsing for `{"text","emotion"}`)

**Floating window three-state UI:**
- `BUBBLE` → click to show `COMMENT_LIST` (nicknames + like counts from ContextBuilder)
- Click comment → single-click sends to AI evaluation → `EVALUATION` card (5s auto-hide)
- Card width: 320dp, maxLines: 6, initial position: left edge + 30% vertical

**Cancel safety fix:**
- `sendComment()` calls `cancelCurrentCall()` before new request
- `onResponse` checks `call.isCanceled()` — cancelled requests silently ignored
- Prevents `StreamResetException: stream was reset: CANCEL` false error Toasts

**New files:** `CommentDiffer.java`
**Deleted files:** `AiPersonality.java`

## AndroidManifest & Permissions

- `SYSTEM_ALERT_WINDOW` — for floating window overlay
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — ScreenCaptureService
- `FOREGROUND_SERVICE_SPECIAL_USE` — FloatingWindowService (Android 14+)
- `POST_NOTIFICATIONS` — Android 13+ 前台通知权限
- `<queries>` targeting `com.ss.android.ugc.aweme` — Android 11+ package visibility
- AccessibilityService bound with `BIND_ACCESSIBILITY_SERVICE`
- FloatingWindowService 声明 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` — Android 14+
- `ChatActivity` — `android:exported="false"`, `android:theme="@style/Theme.ZuoYou"`

## Accessibility Config (`accessibility_service_config.xml`)

- Event types: `typeWindowContentChanged` and `typeWindowStateChanged`
- Feedback type: `feedbackGeneric`
- Can retrieve window content: `true`
- Notification timeout: 1000ms (debounce)

## Phase 2: ScreenCaptureService (MediaProjection)

### 功能概述

通过 Android MediaProjection API 截取屏幕帧，保存为 JPEG 到应用缓存目录。由 `DouyinCommentService` 的 5 秒定时器触发 `captureOnce()` 静态方法，不再使用内部定时器。

### 关键方法

| 方法 | 作用 |
|------|------|
| `startCapture()` | 创建 MediaProjection → VirtualDisplay → ImageReader，预分配 Bitmap 缓冲区 |
| `captureOnce()` | 静态方法，由 DouyinCommentService 定时器调用，捕获单帧 |
| `captureFrame()` | `ImageReader.acquireLatestImage()` 获取最新帧，仅在抖音前台时捕获 |
| `imageToBitmap()` | RGBA_8888 Image → ARGB_8888 Bitmap（复用预分配缓冲区） |
| `saveBitmapToCache()` | JPEG 80% 质量写入 `getCacheDir()`，保存后通知 ContextBuilder |
| `cleanupOldCaptures()` | 保留最新 50 帧，超出部分自动清理 |
| `stopCapture()` | 释放所有资源 |
| `setDouyinForeground()` | 静态方法，控制是否捕获 |

### Android 14+ 兼容要点

1. **`MediaProjection.registerCallback()` 必须在 `createVirtualDisplay()` 之前调用**
2. **`RESULT_OK` 的陷阱** — 只能通过检查 `data != null` 来判断授权成功
3. **`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限** — Android 14+ 专用前台服务类型
4. **START_STICKY 重启恢复** — 通过静态字段保存授权数据来自动恢复

## Phase 3: ContextBuilder (Context Fusion)

### 架构

- `DouyinCommentService` 创建 `ContextBuilder` 实例（静态单例 `sInstance`）
- `pushCommentsSilent()` — 更新缓冲区但不触发 listener（供定时器使用）
- `pushComments()` — 更新缓冲区并触发 listener（供外部使用）
- `getLatestCommentsStatic()` — 静态方法，供 FloatingWindowService 获取评论列表
- 内部维护 3 个线程安全的环形缓冲区：recentComments(max 5)、recentScreenshots(max 5)、timeline(max 30)

### AppContext JSON 输出格式

```json
{
  "app": "抖音",
  "timestamp": "2026-06-16T23:52:37",
  "comment_count": 5,
  "recent_comments": [
    { "user": "...", "text": "...", "likes": 731, "time": "9小时前", "location": "· 河北" }
  ],
  "latest_screenshot": "/data/data/.../cache/capture_...jpg",
  "timeline": [
    { "type": "comment", "time": "...", "detail": "..." },
    { "type": "screenshot", "time": "...", "detail": "..." }
  ]
}
```

### Logcat 标签

| Tag | 来源 | 用途 |
|-----|------|------|
| `DouyinComment` | DouyinCommentService | 评论提取 + 定时器日志 |
| `ScreenCapture` | ScreenCaptureService | 截帧状态日志 |
| `ZuoYouContext` | ContextBuilder | 融合上下文 JSON 输出 |
| `ZuoYouAI` | AiService | AI 调用日志（配置、请求、响应） |
| `ZuoYouFloat` | FloatingWindowService | 悬浮窗生命周期与交互日志 |
| `ZuoYouMain` | MainActivity | 主界面 + 侧边栏操作日志 |

## Phase 4: AI Service + Floating Window

### AI 服务（AiService）

固定 system prompt（可不 KAFU 性格设定），两种调用入口：
- `sendComment(String text)` — 自动模式（定时器触发，发送最高分评论文本）
- `evaluateComment(Comment comment)` — 手动模式（用户点击评论列表中的某条）

**可不（KAFU）角色设定：**
- 16-18岁迷糊少女，天然呆、温柔、共情力强
- 说话轻柔软糯，常用「えっ……？」、「そうなの？」
- 互动风格：接地气有网感，搞笑评论跟着笑，emo评论温柔共情，主打安慰治愈
- 偶尔提及咖喱乌冬、音乐等个人爱好

**核心机制：**
- 内容哈希去重：相同评论不重复调用
- 回复去重：维护最近 5 条回复，附在 user message 中让 AI 避免重复
- 指数退避重试：最多 3 次（1s/2s/4s），429/500/502/503/504 自动重试
- 取消安全：`sendComment()` 先取消旧请求，`onResponse` 检查 `call.isCanceled()` 忽略被取消的请求
- Temperature: 0.85，max_tokens: 200

### 悬浮窗（FloatingWindowService）

三态 UI：
- **气泡态** (48dp) — 冰蓝外发光环 + 呼吸脉动动画，可拖拽，初始位置左侧边 + 垂直 30%
- **评论列表态** — 点击气泡展开，显示评论者头像圆圈（首字符 + 冰蓝色）、昵称、❤ 赞数，可滚动
- **评价结果态** — AI 评价卡片从气泡下方展开（气泡始终可见），自适应宽度 200~360dp

**自适应宽度：**
- 根据 AI 回复最长一行使用 `Paint.measureText()` 测量像素宽度，动态计算
- 范围 200dp（短句）~ 360dp（长文本），高度按黄金比例（宽/φ）同步调整

**自适应收回时间：**
- 公式：`clamp(字数 × 45ms + 2000ms, 2.5s, 12s)`
- 短回复 2.5s，中长回复 5~8s，长文 12s
- `showEvaluationResult()` 设置计时，`showEvaluation()` 为占位文字（无自动收回）

**打字指示器：**
- AI 思考时显示三个跳动点 ···，真实回复到达后隐藏
- 循环淡入动画，随 typingIndicator 可见性自动启停

**其他特性：**
- 消息缓冲：服务未运行时暂存最多 3 条 AI 回复，启动后自动刷新
- 边缘吸附：拖拽结束后滑向最近的屏幕边缘
- 屏幕参数：API 30+ 使用 `WindowManager.getCurrentWindowMetrics()`

### 配置

通过 `SettingsActivity` 写入 `SharedPreferences`（文件：`zuoyou_prefs` + `zuoyou_secure_prefs`）：

| Key | 默认值 | 说明 | 存储位置 |
|-----|--------|------|----------|
| `api_key` | "" | DeepSeek API Key | `SecurePrefs`（AES-256 加密） |
| `api_base_url` | `https://api.deepseek.com/v1` | API 地址 | 普通 SharedPreferences |
| `model_name` | `deepseek-chat` | 模型名 | 普通 SharedPreferences |
| `memory_collection_enabled` | false | 记忆收集开关 | 普通 SharedPreferences |

### 共享常量（Constants.java）

| 常量 | 值 | 说明 |
|------|-----|------|
| `SYSTEM_PROMPT` | KAFU 角色设定文本 | AiService + ChatAiService 共用 |
| `API_TIMEOUT_MS` | 15000 | OkHttp 超时（AiService + ChatAiService 共用） |
| `CHAT_HISTORY_MAX_MESSAGES` | 40 | 聊天历史最大消息数（避免 API token 溢出） |

### 新依赖

- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP 客户端
- `androidx.security:security-crypto:1.1.0-alpha06` — EncryptedSharedPreferences

## UI Redesign (2026-06-19) — 冰蓝色调 + DrawerLayout

**App 名称变更：** 左右 → 可不 (KAFU)

**主界面重设计：**
- 移除：运行状态卡片、快速上手指南、顶部品牌头部
- 新增：DrawerLayout 右侧抽屉导航
- 主内容：居中展示品牌图片 + App 名称
- 侧边栏：合并服务开关（悬浮窗+屏幕捕获）+ 设置入口

**配色方案（冰蓝色调）：**
- 主色：`#7EB6D9`（冰蓝）
- 背景：`#EDF4F8`（淡蓝灰）
- 文字：`#1A2A3A`（深蓝灰）
- 状态栏：冰蓝色，深色图标

**设置页重构：**
- 新增无障碍服务状态卡片（开启/关闭按钮，跳转系统设置）
- 移除头部橙色渐变和底部提示文字
- 保存按钮改为蓝色渐变

**悬浮窗：**
- 边框色从橙色改为冰蓝色
- 图标更新为新的粉发动漫角色

**侧边栏服务控制：**
- 悬浮窗 + 屏幕捕获合并为一个开关
- 开启顺序：先悬浮窗 → 再屏幕捕获
- 关闭顺序：先屏幕捕获 → 再悬浮窗

**删除的 drawable 文件（11个）：**
bg_header.xml, bg_settings_header.xml, bg_button_orange.xml, bg_button_blue.xml, bg_button_green.xml, ripple_button_orange.xml, ripple_button_blue.xml, ripple_button_green.xml, bg_button_outline.xml, bg_chip_active.xml, spinner_bg.xml

**新增的 drawable 文件（6个）：**
bg_button_primary.xml, bg_button_secondary.xml, bg_button_close.xml, bg_drawer_item.xml, selector_drawer_item.xml, ic_menu.xml

## Phase 2+: AI Chat + Music Player + Animation + Memory (2026-06-20)

### 概述

在 Phase 4 基础上新增三大交互功能：AI 聊天、音乐播放、片头动画，以及记忆收集系统。

### 主界面手势（MainImageHandler）

- **双击角色图片** → 播放 MP4 片头动画（AnimationPlayer）→ 动画完成进入聊天（ChatActivity）
- **左滑角色图片**（velocityX < -1000）→ 弹出音乐菜单（MusicMenuPopup）
- 防重入：动画播放中忽略双击
- 角色图片 ImageView 引用缓存在构造函数中（不使用 `content.getChildAt(0)`，避免拿到 DrawerLayout）

### AI 聊天（ChatActivity + ChatAiService）

**ChatActivity** — 全屏聊天界面：
- 布局：顶栏（返回+标题）→ 角色头像（100dp）→ 分割线 → RecyclerView → 输入框+发送按钮
- 3 种消息类型：用户（右对齐蓝色气泡）、AI（左对齐白色气泡）、打字指示器（···）
- 接收 `EXTRA_SESSION_ID`（-1=新建会话）
- 消息流程：保存用户消息 → 显示打字指示器 → 调用 AI → 保存回复 → 刷新列表

**ChatAiService** — DeepSeek API 聊天客户端：
- 与 AiService 的区别：支持完整消息历史 + 记忆上下文注入 + 单次请求不重试
- 消息数组：[system prompt, memory context, history..., user message]
- 历史消息上限：`Constants.CHAT_HISTORY_MAX_MESSAGES`（40 条），避免超出 API token 限制
- 使用共享 `Constants.SYSTEM_PROMPT`（不再重复定义）

**ChatSessionManager** — JSON 文件持久化：
- 文件结构：`getFilesDir()/chats/chat_index.json` + `chat_N.json`
- 所有公开方法 `synchronized` 保护线程安全
- 内部记录：`ChatMessage(role, content, timestamp)` 和 `SessionInfo(id, preview, count, updatedAt)`

### 音乐播放（MusicPlayer + MusicMenuPopup）

**MusicPlayer** — MediaPlayer 单例：
- 双重检查锁定单例模式
- 多监听器支持：`CopyOnWriteArrayList<MusicListener>` + `addListener()`/`removeListener()`
- 封面联动：播放某首歌时通知所有监听器切换封面图
- 5 首歌曲：`R.drawable.cover_01~05`（封面）+ `R.raw.song_01~05`（音频）
- 方法：`init(context)`、`play(index)`、`pause()`、`resume()`、`stop()`、`togglePlayPause()`

**MusicMenuPopup** — PopupWindow 浮窗：
- 纯代码构建（无 XML），白色圆角卡片风格
- 歌曲列表：封面小图 + 歌名 + 歌手 + 播放指示（▶/❚❚）
- 播放/暂停按钮
- 使用 `addListener()`/`removeListener()` 模式，dismiss 时移除监听器

### 片头动画（AnimationPlayer）

- 在 ImageView 位置覆盖 SurfaceView 播放 MP4（`R.raw.animation_intro`）
- 通过 `anchor.getLocationOnScreen()` 定位，覆盖在角色图片上方
- `sPlaying` 为 `volatile static boolean`，在 SurfaceView 创建成功后才设为 true
- 播放完成/失败 → 移除 SurfaceView → 触发回调
- 播放期间拦截触摸事件

### 记忆收集（MemoryCollector）

- 从 ContextBuilder 采集视频简介（最多 5 条）和高赞评论（likeCount > 50，最多 20 条）
- 由 `DouyinCommentService` 5 秒定时器调用 `tryCollect()`
- 数据持久化到 `memory_data.json`，Activity 重建不丢失
- 所有公开方法 `synchronized` 保护线程安全
- `buildMemoryContext()` 生成文本块供 ChatAiService 注入 AI 上下文
- 开关存储在 SharedPreferences（`KEY_MEMORY_ENABLED`）

### 视频简介提取（DouyinCommentService）

- 在 `extractRecursive()` 中通过状态机提取视频简介
- 状态机：找到 `@` 作者行 → 标记 `pendingDescription` → 取后续最长文本（>20 字，排除"相关"/"推荐"前缀）
- 提取结果通过 `ContextBuilder.setVideoDescription()` 共享

### 左侧抽屉（聊天记录）

- `activity_main.xml` 新增 `chatDrawerView`（左抽屉）
- 显示聊天记录列表（序号 + 时间 + 预览 + 消息数）
- "+ 新聊天" 按钮创建新会话
- 点击已有会话打开 ChatActivity
- `onResume()` 时刷新列表

### 右侧抽屉（记忆收集开关）

- 新增记忆收集开关区域（标题 + 状态 + 开启/关闭按钮）
- 点击切换开关状态

### 新增布局文件

| 文件 | 用途 |
|------|------|
| `activity_chat.xml` | 聊天界面布局 |
| `item_chat_user.xml` | 用户消息气泡（右对齐蓝色） |
| `item_chat_ai.xml` | AI 消息气泡（左对齐白色带边框） |
| `item_chat_typing.xml` | 打字指示器 ··· |
| `bg_chat_bubble_user.xml` | 用户气泡背景（蓝色圆角） |
| `bg_chat_bubble_ai.xml` | AI 气泡背景（白色圆角+边框） |
| `bg_chat_input.xml` | 输入框背景 |
| `ic_back.xml` | 返回箭头图标 |

### 新增资源文件

| 文件 | 用途 |
|------|------|
| `cover_01~05.jpg` | 5 首歌曲封面图（drawable） |
| `song_01~05.mp3` | 5 首歌曲音频（raw） |
| `animation_intro.mp4` | 片头动画（raw） |

### Logcat 标签

| Tag | 来源 | 用途 |
|-----|------|------|
| `ChatActivity` | ChatActivity | 聊天界面生命周期 |
| `ChatAiService` | ChatAiService | 聊天 AI 调用日志 |
| `ChatSession` | ChatSessionManager | 会话持久化日志 |
| `MusicPlayer` | MusicPlayer | 音乐播放状态日志 |
| `AnimationPlayer` | AnimationPlayer | 动画播放日志 |
| `MemoryCollector` | MemoryCollector | 记忆采集日志 |

## Code Review Fixes (2026-06-19) — 15 findings + UI redesign

**ContextBuilder fixes:**
- **Singleton guard**: Constructor no longer unconditionally overwrites `sInstance`. On service restart, `DouyinCommentService.onCreate()` reuses existing instance via `ContextBuilder.getInstance()` — prevents FloatingWindow from seeing blank data mid-session
- **Thread safety**: `buildContext()` now synchronizes `SimpleDateFormat.format()` like all other callers (latent race)
- **Comment cap**: `getCurrentVisibleComments()` caps at 10 entries to prevent floating window overflow
- **Dead code removed**: `getLatestNewComments()` removed — no callers after static entry point was changed

**MainActivity fixes:**
- **Stale drawer state**: `updateDrawerStatus()` now does optimistic UI update on close + `postDelayed(150ms)` sync correction (service STOP is async)
- **Missing Toasts**: Restored `"屏幕捕获权限被拒绝"` and `"悬浮窗已开启"` Toast calls that were lost in the refactor
- **Accessibility status**: Drawer status now shows "⚠ 运行中（无障碍服务未开启）" when accessibility service is off

**SettingsActivity fix:**
- **Save order**: Saves `apiBaseUrl` and `modelName` before checking API key — edits no longer silently dropped on empty key

**AiService fixes:**
- **Retry history**: Moved `recentResponses` append from `callApiWithRetry` to `callApi` — retry path no longer duplicates history 3x into the prompt
- **Config reload**: `sendComment()` (auto timer) now calls `loadConfig()` — API key changes take effect immediately

**DouyinCommentService fix:**
- **Debounce completeness**: Added 500ms debounce for `TYPE_WINDOW_CONTENT_CHANGED` (was missing, only STATE_CHANGED had one)

**CommentDiffer fix:**
- **Collision-free dedup**: `makeKey()` changed from `user + "|" + text` to `Objects.hash(user, text)` — the fix documented in CLAUDE.md but never actually applied to CommentDiffer

**ScreenCaptureService fix:**
- **Pre-allocated pixel array**: Added `reusablePixels` alongside `reusableBitmap` — avoids ~2MB int[] allocation per capture frame

**FloatingWindowService UI redesign:**
- **Bubble always visible**: Root layout changed from FrameLayout to LinearLayout (bubble above, card below)
- **Scrollable evaluation**: `evaluationText` wrapped in ScrollView (max height by golden ratio w/φ)
- **Comment avatars**: Avatar circle (initial char + hash-based ice-blue color) + username + heart like count
- **Smooth transitions**: Card fades in from below on expand, scales out on collapse
- **Adaptive width**: Card width 200~360dp based on `Paint.measureText()` of longest text line
- **Adaptive display time**: Auto-dismiss = `clamp(chars x 45ms + 2000ms, 2.5s, 12s)`
- **Typing indicator**: Three bouncing dots during AI thinking, hidden on real response

## Code Review Fixes (2026-06-20) — Phase 2+ hardening

**MusicPlayer fixes:**
- **Multi-listener**: Changed from single `volatile MusicListener` to `CopyOnWriteArrayList<MusicListener>` with `addListener()`/`removeListener()`. Prevents MusicMenuPopup from overwriting MainActivity's cover-image listener.
- **Thread safety**: `isPlaying()` now `synchronized` — prevents NPE/ISE race with `release()`.
- **Cleanup**: `release()` clears listener list.

**MusicMenuPopup fixes:**
- **Listener lifecycle**: Uses `addListener()` in `show()`, `removeListener()` in `dismiss()`. No longer permanently overwrites MainActivity's listener.

**AnimationPlayer fixes:**
- **sPlaying volatile**: Field made `volatile` for cross-thread visibility.
- **sPlaying inside try**: `sPlaying = true` moved after `content.addView()` inside the try block. OOM during SurfaceView creation no longer leaves sPlaying stuck true.

**MainImageHandler fixes:**
- **Cached ImageView**: Constructor caches the `imageView` reference. `onDoubleTap()` and `onFling()` no longer use `content.getChildAt(0)` which returned DrawerLayout instead of ImageView.

**MemoryCollector fixes:**
- **Thread safety**: All public methods `synchronized`. Prevents `ConcurrentModificationException` on LinkedHashMaps.
- **Persistence**: Data saved to `memory_data.json`. Survives Activity recreation (device rotation).
- **Singleton guard**: Constructor preserves existing instance data instead of overwriting.

**ChatAiService fixes:**
- **History limit**: Caps to last `CHAT_HISTORY_MAX_MESSAGES` (40) messages. Prevents API token overflow.
- **Shared constants**: Uses `Constants.SYSTEM_PROMPT` and `Constants.API_TIMEOUT_MS`.

**AiService fixes:**
- **Shared constants**: Uses `Constants.SYSTEM_PROMPT` and `Constants.API_TIMEOUT_MS`. No more duplicate definitions.

**DouyinCommentService fixes:**
- **dumpRecursive try-catch**: Node property reads (`getClassName`, `getText`, etc.) wrapped in try-catch per CLAUDE.md convention.
- **Memory collection timer**: `MemoryCollector.tryCollect()` called in `doTimerTick()` after screen capture. Memory feature now functional.

**Constants.java additions:**
- `SYSTEM_PROMPT` — shared KAFU persona text (AiService + ChatAiService)
- `API_TIMEOUT_MS` — shared OkHttp timeout (15000ms)
- `CHAT_HISTORY_MAX_MESSAGES` — chat history cap (40 messages)
- `KEY_MEMORY_ENABLED` — memory collection toggle key
