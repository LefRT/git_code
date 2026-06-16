# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**左右 (ZuoYou)** — an Android app that gives AI a physical embodiment. Phase 1 focuses on building the "AI companion watching" experience: an app that uses Android AccessibilityService to extract content from Douyin (抖音) in real-time, which will later feed into an AI personality system.

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

# View DouyinCommentService Logcat output
adb logcat -s DouyinComment

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
- **Java**: 1.8 (source & target compatibility)
- **Namespace / applicationId**: `com.zuoyou.commentcollector`
- **Test framework**: JUnit 4.13 (no tests written yet)
- **Dependencies**: appcompat 1.6.1, Material 1.11.0, ConstraintLayout 2.1.4 (layout currently uses `LinearLayout` — `activity_main.xml`)

## Project Files

```
app/src/main/java/com/zuoyou/commentcollector/
├── Comment.java                  # Data class — user, text, likeCount
├── CommentParser.java            # Pure-Java parser (no Android dependency)
├── DouyinCommentService.java     # AccessibilityService — Phase 1 core logic
├── ScreenCaptureService.java     # MediaProjection screen capture — Phase 2
└── MainActivity.java             # Launcher — service status & enable guide
```

## How Comment Extraction Works (current implementation)

1. **`onAccessibilityEvent()`** is triggered by `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED` events within the Douyin package (`com.ss.android.ugc.aweme`). It gets the root window node, verifies it belongs to Douyin, then delegates to the recursive walker.

2. **`collectCommentsRecursive()`** walks the AccessibilityNodeInfo tree. It identifies comment items by finding `FrameLayout` nodes whose `contentDescription` contains `"回复 按钮"`. Each child is recycled at the end of its iteration in the `for` loop.

3. **`CommentParser.parseFromDescription()`** parses the `contentDescription` string (format: `user,text,time, · location,回复 按钮,`) by:
   - Stripping the trailing `"回复 按钮,"` suffix
   - Searching only the **last 2/3** of the string (`body.length() / 3`) for a timestamp regex — this optimization avoids false matches on usernames containing digit patterns
   - Using the last-found timestamp position as an anchor to split user+text from time+location
   - Splitting on the first comma in the user+text segment to get username and comment text
   - `Comment` constructor sets `likeCount = 0`; the service fills it in afterward

4. **`findLikeCount()`** uses a dual strategy (comment-based implementation):
   - **First**: checks if the current node is a `TextView` containing 1–6 digits, and if its parent's `contentDescription` contains `"赞"` (this is the usual case when `collectCommentsRecursive` lands on the comment `FrameLayout` and the like-count `TextView` is itself a direct child in the tree)
   - **Fallback**: if the direct check fails, recursively searches all child nodes with the same logic
   - This is **read-only** — never recycles any nodes; recycling is entirely handled by `collectCommentsRecursive`'s loop

5. **`buildJson()`** outputs structured JSON to Logcat with fields: `app`, `"抖音"`, `timestamp` (ISO 8601: `yyyy-MM-dd'T'HH:mm:ss` with `Locale.CHINA`), `comment_count`, and a `comments` array of `{user, text, likes}`.

### Key insights

**Douyin's comment content is NOT in `TextView.text`** — it's in `FrameLayout.contentDescription` as a comma-separated string. The per-comment frame layout's desc looks like:

```
MiSS,我有六万存款，月薪七千，能结婚不,昨天19:09, · 广东,回复 按钮,
```

The timestamp regex in `CommentParser` is the critical anchor point. Its format includes Chinese-relative times (`昨天HH:mm`, `X分钟前`, `刚刚`) and absolute formats. The search is deliberately scoped to the latter 2/3 of the string to avoid matching digit sequences in usernames.

**Node lifecycle is critical**: `collectCommentsRecursive` recycles its children in the `for` loop. `findLikeCount` is read-only and does NOT recycle. After `collectCommentsRecursive` returns, the root node is recycled by `onAccessibilityEvent`. Never use a node after its `recycle()` call, and never recycle a node twice.

## Architecture

### Current State (Phase 1 — content perception layer)

```
┌─────────────────────┐
│  Douyin App UI      │
│  (com.ss.android    │
│   .ugc.aweme)       │
└────────┬────────────┘
         │ AccessibilityEvent
┌────────▼─────────────────────┐
│ DouyinCommentService         │
│  collectCommentsRecursive()  │──▶ JSON → Logcat
│  ─ recursively walks tree    │
│  ─ finds "回复 按钮" nodes   │
│  ─ parses desc→user+text     │
│  ─ finds likeCount from child│
└──────────────────────────────┘
```

### Planned Architecture (from README)

```
┌─────────────────────┐
│     Android APP     │
├─────────────────────┤
│ • 内容感知层        │  ← Current: AccessibilityService (text)
│ • Context Builder   │  ← Planned: fuses text + visual data
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
| 3 | Context Builder (fuse text + vision) | ❌ Pending |
| 4 | AI personality system +悬浮窗 | ❌ Pending |
| 5 | Physical robot (ESP32-S3) | ❌ Pending |

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
| `saveBitmapToCache()` | JPEG 80% 质量写入 `getCacheDir()`，文件名 `capture_yyyyMMdd_HHmmss_SSS.jpg` |
| `cleanupOldCaptures()` | 保留最新 50 帧，超出部分自动清理 |
| `stopCapture()` | 释放 MediaProjection、VirtualDisplay、ImageReader、后台线程 |

### 跨 Service 通信

MainActivity 通过 Intent Action 控制 ScreenCaptureService：
- `ACTION = "START_CAPTURE"` — 携带 `resultCode` + `data`（用户授权结果）
- `ACTION = "STOP_CAPTURE"` — 停止捕获

MediaProjection 权限通过 `ActivityResultLauncher<Intent>` 在 MainActivity 中获取，用户必须手动确认系统对话框。

### 显示 DPI

VirtualDisplay 使用设备实际 DPI，确保截取的画面布局比例与真实屏幕一致。捕获分辨率固定 480p（宽度 480px，高度按屏幕比例计算）。

### Android 14+ 兼容要点

1. **`MediaProjection.registerCallback()` 必须在 `createVirtualDisplay()` 之前调用** — Android 14 强制要求，否则抛出 `IllegalStateException: Must register a callback before starting capture`
2. **`RESULT_OK` 的陷阱** — `Activity.RESULT_OK` 的值是 `-1`，通过 Intent 传递后 `intent.getIntExtra("resultCode", -1)` 的默认值 `-1` 和 `RESULT_OK` 无法区分，**只能通过检查 `data != null` 来判断授权成功**，不能用 `resultCode != -1`
3. **`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限** — Android 14+ 专用前台服务类型，不声明则 `startForeground()` 会报错
4. **START_STICKY 重启恢复** — 服务被系统杀死后以 START_STICKY 重启（intent=null），通过静态字段 `sSavedResultCode` / `sSavedData` 保存授权数据来自动恢复捕获
