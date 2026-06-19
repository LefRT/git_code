# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**左右 (ZuoYou)** — an Android app that gives AI a physical embodiment. The app uses Android AccessibilityService to extract content from Douyin (抖音) in real-time and MediaProjection to capture screen frames, fused into a structured context for downstream AI processing.

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
├── Constants.java               # Global constants (prefs keys, package names, defaults)
├── ContextBuilder.java          # Phase 3 — fused pipeline (comments + screenshots + timeline)
├── DouyinCommentService.java    # AccessibilityService — 5s timer + three-state diff + AI trigger
├── ScreenCaptureService.java    # Phase 2 — MediaProjection screen capture service
├── SecurePrefs.java             # EncryptedSharedPreferences wrapper for API Key storage
├── AiService.java               # DeepSeek API client (fixed prompt + retry + cancel safety)
├── FloatingWindowService.java   # System overlay — bubble / comment list / evaluation card
├── SettingsActivity.java        # API key config UI
└── MainActivity.java            # Launcher — service status & enable guide

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
│  Fixed SYSTEM_PROMPT (抖音搭子风格)             │
│  content hash dedup + cancel safety            │
│  OkHttp POST → DeepSeek API                   │
│  3x exponential backoff retry (1s/2s/4s)      │
│  Cancelled request StreamResetException handled│
└──────────┬────────────────────────────────────┘
           │ onAiResponse(text)
┌──────────▼────────────────────────────────────┐
│      FloatingWindowService                    │
│  气泡态 (48dp) → 评论列表 (点击评价)           │
│  → AI 评价卡片 (320dp, 5s auto-hide)          │
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
- **Dedup key collision fix**: Changed from `user + "|" + text` to `Objects.hash(user, text)`.
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

## Phase 4: AI Service + Floating Window

### AI 服务（AiService）

固定 system prompt（抖音搭子风格），两种调用入口：
- `sendComment(String text)` — 自动模式（定时器触发，发送最高分评论文本）
- `evaluateComment(Comment comment)` — 手动模式（用户点击评论列表中的某条）

**核心机制：**
- 内容哈希去重：相同评论不重复调用
- 回复去重：维护最近 5 条回复，附在 user message 中让 AI 避免重复
- 指数退避重试：最多 3 次（1s/2s/4s），429/500/502/503/504 自动重试
- 取消安全：`sendComment()` 先取消旧请求，`onResponse` 检查 `call.isCanceled()` 忽略被取消的请求
- Temperature: 0.85，max_tokens: 200

### 悬浮窗（FloatingWindowService）

三态 UI：
- **气泡态** (48dp) — 可拖拽，初始位置左侧边 + 垂直 30%
- **评论列表态** — 点击气泡展开，显示 "昵称 (赞数)"，点击某条发给 AI 评价
- **评价结果态** — 320dp 宽卡片，最多 6 行，5s 自动收回

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

### 新依赖

- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP 客户端
- `androidx.security:security-crypto:1.1.0-alpha06` — EncryptedSharedPreferences
