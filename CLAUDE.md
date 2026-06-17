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
- **Test framework**: JUnit 4.13 (no tests written yet)
- **Dependencies**: appcompat 1.6.1, Material 1.11.0, ConstraintLayout 2.1.4

## Project Files

```
app/src/main/java/com/zuoyou/commentcollector/
├── AppContext.java              # Phase 3 — unified context data record + TimelineEvent
├── Comment.java                 # Phase 1 — data record (user, text, likeCount, time, location)
├── CommentCollector.java        # Phase 1 — tree traversal + parsing + JSON (extracted from service)
├── CommentParser.java           # Phase 1 — pure-Java contentDescription parser
├── ContextBuilder.java          # Phase 3 — fused pipeline (comments + screenshots + timeline)
├── DouyinCommentService.java    # Phase 1 — AccessibilityService (now delegates to CommentCollector)
├── ScreenCaptureService.java    # Phase 2 — MediaProjection screen capture service
├── AiPersonality.java           # Phase 4 — prompt engineering (3 personalities)
├── AiService.java               # Phase 4 — DeepSeek API scheduler (debounce + cooldown + dedup)
├── FloatingWindowService.java   # Phase 4 — system overlay floating bubble window
├── SettingsActivity.java        # Phase 4 — API key & personality config UI
└── MainActivity.java            # Launcher — service status & enable guide
```

## How Comment Extraction Works (current implementation)

1. **`onAccessibilityEvent()`** is triggered by `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED` events within the Douyin package (`com.ss.android.ugc.aweme`). It gets the root window node, verifies it belongs to Douyin, then delegates to `CommentCollector.collect()`.

2. **Event debounce**: `TYPE_WINDOW_CONTENT_CHANGED` events within 500ms of the last processed event are skipped to reduce unnecessary tree walks.

3. **`CommentCollector.collectRecursive()`** walks the AccessibilityNodeInfo tree. It identifies comment items by finding `FrameLayout` nodes whose `contentDescription` contains `"回复 按钮"`. Each child is recycled at the end of its iteration in the `for` loop. The entire traversal is wrapped in try-catch to guard against rare node-recycled exceptions.

4. **`CommentParser.parseFromDescription()`** parses the `contentDescription` string (format: `user,text,time, · location,回复 按钮,`) by:
   - Stripping the trailing `"回复 按钮,"` suffix
   - Searching only the **last 2/3** of the string (`body.length() / 3`) for a timestamp regex — this optimization avoids false matches on usernames containing digit patterns
   - Using the last-found timestamp position as an anchor to split user+text from time+location
   - Splitting on the first comma in the user+text segment to get username and comment text
   - Further splitting the time+location segment to extract both fields
   - Returns a `Comment` record (user, text, likeCount=0, time, location)

5. **`findLikeCount()`** uses a dual strategy:
   - **First**: checks if the current node is a `TextView` containing 1–6 digits, and if its parent's `contentDescription` contains `"赞"`
   - **Fallback**: recursively searches all child nodes with the same logic
   - This is **read-only** — never recycles any nodes; recycling is entirely handled by `collectRecursive`'s loop

6. **Deduplication**: Extracted comments are deduplicated by `(user, text)` key. A `LinkedHashMap` acts as a sliding window of 100 recent entries, automatically evicting the oldest.

### Key insights

**Douyin's comment content is NOT in `TextView.text`** — it's in `FrameLayout.contentDescription` as a comma-separated string. The per-comment frame layout's desc looks like:

```
MiSS,我有六万存款，月薪七千，能结婚不,昨天19:09, · 广东,回复 按钮,
```

The timestamp regex in `CommentParser` is the critical anchor point. Its format includes Chinese-relative times (`昨天HH:mm`, `X分钟前`, `刚刚`) and absolute formats. The search is deliberately scoped to the latter 2/3 of the string to avoid matching digit sequences in usernames.

**Node lifecycle is critical**: `collectRecursive` recycles its children via `try-finally` in the `for` loop. `findLikeCount` also recycles all `getChild()`/`getParent()` wrappers in its own `try-finally` blocks (each `getChild()` returns a new independent Java wrapper that must be recycled separately). After `collectRecursive` returns, the root node is recycled by `onAccessibilityEvent`. Never use a node after its `recycle()` call, and never recycle a node twice.

## Architecture

### Current State (Phase 1-3 — content perception + context fusion)

```
┌────────────────────┐     ┌─────────────────────┐
│  Douyin App UI     │     │     Phone Screen    │
│  (Accessibility)   │     │  (MediaProjection)  │
└────────┬───────────┘     └──────────┬──────────┘
         │ AccessibilityEvent         │ 3s interval
┌────────▼───────────────────────────▼──────────┐
│           ContextBuilder (Phase 3)             │
│  ┌────────────────┐  ┌──────────────────────┐ │
│  │ CommentCollector│  │ ScreenCaptureService  │ │
│  │ parse + dedup  │  │ imageToBitmap + save  │ │
│  └───────┬────────┘  └──────────┬───────────┘ │
│          │ pushComments()       │ pushScreenshot│
│  ┌───────▼──────────────────────▼───────────┐ │
│  │     Ring buffers (in-memory)             │ │
│  │ ・recentComments (max 20)                │ │
│  │ ・recentScreenshots (max 5)              │ │
│  │ ・timeline (max 30 events)               │ │
│  └───────┬──────────────────────────────────┘ │
│          │ buildContext()                     │
│          ▼                                    │
│     AppContext{                                │
│       app, timestamp,                         │
│       comment_count,                          │
│       recent_comments[],                      │
│       latest_screenshot,                      │
│       timeline[]                              │
│     } → toJson() → Logcat + Phase 4           │
└───────────────────────────────────────────────┘
```

### Planned Architecture (from README)

```
┌─────────────────────┐
│     Android APP     │
├─────────────────────┤
│ • 内容感知层        │  ← Phase 1-3: Accessibility + ScreenCapture + ContextBuilder
│ • AI 接口层         │  ← Planned: LLM API calls
│ • 悬浮窗交互        │  ← Planned: overlay UI for AI comments
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
| 4 | AI personality system + 悬浮窗 | ✅ Done |
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

- **AccessibilityNodeInfo leak fix**: `findLikeCount` and `collectRecursive` now use `try-finally` to guarantee `recycle()` on all `getChild()`/`getParent()` wrappers. Every `getChild()` returns a new Java wrapper that must be independently recycled regardless of other references to the same native node.
- **compress() return check**: `saveBitmapToCache()` now checks `bitmap.compress()` return value; failed JPEG compression no longer pushes an empty/corrupt file path to ContextBuilder.
- **Data race fix**: `totalCommentCount` read in `buildContext()` is now inside `synchronized(recentComments)`, eliminating the JMM data race between the HandlerThread and main thread.
- **Dedup key collision fix**: Changed from `user + "|" + text` string concatenation to `Objects.hash(user, text)` to avoid pipe-character delimiter conflicts.
- **Debounce completeness**: `TYPE_WINDOW_STATE_CHANGED` is now also debounced (shared `lastProcessedTime` with CONTENT_CHANGED), preventing burst tree walks during video navigation.
- **JSON exception logging**: `AppContext.toJson()` now logs the exception via `Log.e` instead of silently returning `"{}"`.
- **I/O reduction**: `cleanupOldCaptures()` uses `mCaptureCount` counter to skip `listFiles()` for the first 50 frames and then only every 10th frame, reducing filesystem I/O.
- **Dead allocation removed**: `CommentCollector.collect()` return type changed to `void` — callers use the `Listener` callback, so the unused return value was pure GC pressure.

## AndroidManifest & Permissions

- `SYSTEM_ALERT_WINDOW` — for future floating window overlay
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — ScreenCaptureService 前台服务必需
- `POST_NOTIFICATIONS` — Android 13+ 前台通知权限
- `<queries>` targeting `com.ss.android.ugc.aweme` — Android 11+ package visibility
- AccessibilityService bound with `BIND_ACCESSIBILITY_SERVICE`

## Accessibility Config (`accessibility_service_config.xml`)

- Event types: `typeWindowContentChanged` and `typeWindowStateChanged`
- Feedback type: `feedbackGeneric`
- Can retrieve window content: `true`
- Notification timeout: 1000ms (debounce)

## Phase 2: ScreenCaptureService (MediaProjection)

### 功能概述

通过 Android MediaProjection API 定时截取屏幕帧，保存为 JPEG 到应用缓存目录，供后续 AI 视觉分析使用。

### 关键类

**`ScreenCaptureService.java`** — 前台 Service

| 方法 | 作用 |
|------|------|
| `startCapture()` | 创建 MediaProjection → VirtualDisplay → ImageReader |
| `captureFrame()` | 每 3 秒调用 `ImageReader.acquireLatestImage()` 获取最新帧 |
| `imageToBitmap()` | 将 RGBA_8888 Image 转为 ARGB_8888 Bitmap（处理 R/B 通道重排 + row stride padding） |
| `saveBitmapToCache()` | JPEG 80% 质量写入 `getCacheDir()`，文件名 `capture_yyyyMMdd_HHmmss_SSS.jpg`，保存后通知 ContextBuilder |
| `cleanupOldCaptures()` | 保留最新 50 帧，超出部分自动清理 |
| `stopCapture()` | 释放 MediaProjection、VirtualDisplay、ImageReader、后台线程 |

### 跨 Service 通信

MainActivity 通过 Intent Action 控制 ScreenCaptureService：
- `ACTION = "START_CAPTURE"` — 携带 `resultCode` + `data`（用户授权结果）
- `ACTION = "STOP_CAPTURE"` — 停止捕获

MediaProjection 权限通过 `ActivityResultLauncher<Intent>` 在 MainActivity 中获取，用户必须手动确认系统对话框。

ContextBuilder 通过 `ScreenCaptureService.setContextBuilder(builder)` 静态方法注入，由 DouyinCommentService 在 `onCreate()` 中创建并注入。

### 显示 DPI

VirtualDisplay 使用设备实际 DPI，确保截取的画面布局比例与真实屏幕一致。捕获分辨率固定 480p（宽度 480px，高度按屏幕比例计算）。

### Android 14+ 兼容要点

1. **`MediaProjection.registerCallback()` 必须在 `createVirtualDisplay()` 之前调用** — Android 14 强制要求，否则抛出 `IllegalStateException: Must register a callback before starting capture`
2. **`RESULT_OK` 的陷阱** — `Activity.RESULT_OK` 的值是 `-1`，通过 Intent 传递后 `intent.getIntExtra("resultCode", -1)` 的默认值 `-1` 和 `RESULT_OK` 无法区分，**只能通过检查 `data != null` 来判断授权成功**，不能用 `resultCode != -1`
3. **`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限** — Android 14+ 专用前台服务类型，不声明则 `startForeground()` 会报错
4. **START_STICKY 重启恢复** — 服务被系统杀死后以 START_STICKY 重启（intent=null），通过静态字段 `sSavedResultCode` / `sSavedData` 保存授权数据来自动恢复捕获

## Phase 3: ContextBuilder (Context Fusion)

### 架构

ContextBuilder 通过监听者模式连接两个服务：

- `DouyinCommentService.onCreate()` 创建 `ContextBuilder` 实例
- 通过 `ScreenCaptureService.setContextBuilder()` 静态方法注入到截帧服务
- 评论提取后的去重回调中调用 `contextBuilder.pushComments()`
- 截图保存后调用 `contextBuilder.pushScreenshot()`
- 内部维护 3 个线程安全的环形缓冲区：recentComments(max 20)、recentScreenshots(max 5)、timeline(max 30)
- `buildContext()` 构建不可变的 `AppContext` 快照
- `Listener.onContextUpdated()` 回调供 Phase 4 AI 管线接入

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
| `DouyinComment` | CommentCollector | 评论提取 JSON 输出 |
| `ScreenCapture` | ScreenCaptureService | 截帧状态日志 |
| `ZuoYouContext` | ContextBuilder | 融合上下文 JSON 输出 |
| `ZuoYouAI` | AiService | AI 调用日志（配置加载、请求、响应） |
| `ZuoYouFloat` | FloatingWindowService | 悬浮窗生命周期与交互日志 |

## Phase 4: AI Personality System + Floating Window

### 功能概述

通过 DeepSeek API 将 AppContext 中的评论实时转化为 AI 吐槽，并通过系统悬浮窗展示。

### 数据流

```
AppContext (评论 + 时间线)
  → AiService.onContextUpdated()
      → 内容哈希检测（相同评论跳过）
      → 2s 防抖 + 8s 冷却
  → AiService.callApi()
      → 构建 system prompt（人格模板）
      → 构建 user message（评论按热度排序 + 时间线 + 历史回复去重）
      → OkHttp POST → DeepSeek API
  → parseResponse() → {"text":"...","emotion":"..."}
  → FloatingWindowService.showComment(text)
      → 悬浮窗气泡 → 展开卡片显示吐槽（5s 自动收回）
```

### 关键类

**`AiPersonality.java`** — Prompt 工程

| 方法 | 作用 |
|------|------|
| `buildSystemPrompt()` | 返回三种人格的 system prompt：吐槽搭子（ROAST）/ 温柔陪伴（GENTLE）/ 搞笑段子手（FUNNY） |
| `buildUserMessage(AppContext)` | 将评论按热度排序 + 时间线摘要格式化，附上已说过的话避免重复 |
| `parseResponse(rawContent)` | 解析 LLM 返回的 `{"text":"...","emotion":"..."}` JSON |
| `getTemperature()` | 各人格对应 temperature（0.85 / 0.70 / 0.95） |

**`AiService.java`** — API 调度器

| 功能 | 实现 |
|------|------|
| 防抖 | `onContextUpdated` 后等 2s 无新事件再触发 |
| 冷却 | 两次 API 调用至少间隔 8s |
| 内容去重 | `contentHash()` 计算最近 5 条评论的哈希，相同则跳过 |
| 回复去重 | 维护最近 5 条回复列表，附在 user message 中让 AI 避免重复 |
| 视频切换 | `resetVideoContext()` 重置哈希和冷却 |
| 配置 | `loadConfig()` 每次调用前从 SharedPreferences 读取 |

**`FloatingWindowService.java`** — 系统悬浮窗（前台 Service）

| 状态 | 描述 |
|------|------|
| 气泡态 | 48dp 半透明橙色圆点，可拖拽到屏幕任意位置 |
| 展开态 | 白色卡片（max 240dp 宽），显示 AI 吐槽文字，5s 后自动收回 |
| 边缘吸附 | 拖拽结束后滑向最近的屏幕边缘 |
| 触摸 | 展开态点击收回 + 气泡态点击展开最新吐槽 |

### 配置

通过 `SettingsActivity` 写入 `SharedPreferences`（文件：`zuoyou_prefs`）：

| Key | 默认值 | 说明 |
|-----|--------|------|
| `api_key` | "" | DeepSeek API Key |
| `api_base_url` | `https://api.deepseek.com/v1` | API 地址 |
| `model_name` | `deepseek-chat` | 模型名 |
| `personality` | `ROAST` | 人格（ROAST / GENTLE / FUNNY） |

### 自适应采样（DouyinCommentService）

当连续 3 次节点遍历都无新评论时，自动拉长事件间隔（1s → 2s → 4s → 最长 5s）。
窗口切换（`TYPE_WINDOW_STATE_CHANGED`）时立即恢复 1s 并清空去重缓存。

### 新文件清单

- `AiPersonality.java` — Prompt 工程
- `AiService.java` — DeepSeek API 调度器
- `FloatingWindowService.java` — 系统悬浮窗
- `SettingsActivity.java` — API 配置界面
- `activity_settings.xml` — 设置页布局
- `spinner_bg.xml` — 下拉框背景 shape

### 新依赖

- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP 客户端

### AndroidManifest 新增

- `INTERNET` + `ACCESS_NETWORK_STATE` 权限
- `FOREGROUND_SERVICE_SPECIAL_USE` 权限
| `ZuoYouAI` | AiService | AI 调用日志（配置加载、请求、响应） |
| `ZuoYouFloat` | FloatingWindowService | 悬浮窗生命周期与交互日志 |
