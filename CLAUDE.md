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

## Project Files

```
app/src/main/java/com/zuoyou/commentcollector/
├── Comment.java                  # Data class — user, text, likeCount
├── CommentParser.java            # Pure-Java parser (no Android dependency)
├── DouyinCommentService.java     # AccessibilityService — core logic
└── MainActivity.java             # Launcher — service status & enable guide
```

## How Comment Extraction Works (current implementation)

1. **`onAccessibilityEvent()`** is triggered by `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED` events within the Douyin package (`com.ss.android.ugc.aweme`).

2. **`collectCommentsRecursive()`** walks the AccessibilityNodeInfo tree. It identifies comment items by finding `FrameLayout` nodes whose `contentDescription` contains `"回复 按钮"`.

3. **`CommentParser.parseFromDescription()`** parses the `contentDescription` string (format: `user,text,time, · location,回复 按钮,`) by locating the timestamp with a regex and splitting on commas to extract username and comment text.

4. **`findLikeCount()`** searches within the same comment node for a child `TextView` whose parent has a `contentDescription` containing `"赞"` — this gives the like count (including 0).

5. **`buildJson()`** outputs structured JSON to Logcat with fields: `app`, `timestamp`, `comment_count`, and a `comments` array of `{user, text, likes}`.

### Key insight

Douyin's comment content is NOT in `TextView.text` — it's in `FrameLayout.contentDescription` as a comma-separated string. The per-comment frame layout's desc looks like:

```
MiSS,我有六万存款，月薪七千，能结婚不,昨天19:09, · 广东,回复 按钮,
```

Nodes that are collected/recycled during tree traversal: be careful not to double-recycle or use nodes after recycling. The current design recycles children in `collectCommentsRecursive`'s loop, while `findLikeCount` is read-only and does NOT recycle.

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
| 2 | MediaProjection (screen capture) | ❌ Pending |
| 3 | Context Builder (fuse text + vision) | ❌ Pending |
| 4 | AI personality system +悬浮窗 | ❌ Pending |
| 5 | Physical robot (ESP32-S3) | ❌ Pending |

## AndroidManifest & Permissions

- `SYSTEM_ALERT_WINDOW` — for future floating window overlay
- `<queries>` targeting `com.ss.android.ugc.aweme` — Android 11+ package visibility
- AccessibilityService bound with `BIND_ACCESSIBILITY_SERVICE`

## Accessibility Config (`accessibility_service_config.xml`)

- Event types: `typeWindowContentChanged` and `typeWindowStateChanged`
- Feedback type: `feedbackGeneric`
- Can retrieve window content: `true`
- Notification timeout: 1000ms (debounce)
