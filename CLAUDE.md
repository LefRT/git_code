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

# Uninstall and install debug build on connected device
adb uninstall com.zuoyou.commentcollector && adb install app/build/outputs/apk/debug/app-debug.apk

# View DouyinCommentService Logcat output
adb logcat -s DouyinComment

# List connected devices
adb devices
```

**SDK Location**: Configured in `local.properties` (`sdk.dir=C\:\\Android\\Sdk`). Update this path if building on a different machine.

## Architecture

### Current State (Phase 1 — content perception layer)

```
app/src/main/java/com/zuoyou/commentcollector/
├── MainActivity.java              # Launcher activity — shows service status, navigates
│                                   # user to Accessibility settings to enable the service
└── DouyinCommentService.java      # AccessibilityService — listens to Douyin UI events,
                                    # extracts comment text from the view hierarchy,
                                    # and logs structured JSON to Logcat
```

### Key Design Decisions

- **AccessibilityService is the primary content source**: listens for `TYPE_WINDOW_CONTENT_CHANGED` and `TYPE_WINDOW_STATE_CHANGED` events inside `com.ss.android.ugc.aweme` (Douyin).
- **Comment extraction heuristic**: collects all `android.widget.TextView` nodes, then finds comments by looking for the "回复" (Reply) pattern — a username followed by a "回复" label, with the comment text as the preceding sibling.
- **No AI integration yet**: Phase 1 only dumps structured comment JSON to Logcat. AI API calls (OpenAI/DeepSeek/Qwen) and the Context Builder will come in later phases.
- **Output format**: JSON with `app`, `timestamp`, `comment_count`, and a `comments` array of `{user, text}` objects.
- **No external networking/library dependencies yet** — only standard AndroidX (appcompat, material, constraintlayout).

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
| 1 | AccessibilityService → Logcat | ✅ Done |
| 2 | MediaProjection (screen capture) | ❌ Pending |
| 3 | Context Builder (fuse text + vision) | ❌ Pending |
| 4 | AI personality system +悬浮窗 | ❌ Pending |
| 5 | Physical robot (ESP32-S3) | ❌ Pending |

## Key Implementation Details

### DouyinCommentService.java

- `onAccessibilityEvent()` — filters events to Douyin package only, walks the view tree, collects comments, logs JSON.
- `extractComments()` — finds all TextViews, filters UI noise (labels like "回复", "赞", "大家都在搜", numeric counters), then pairs usernames with their adjacent comment text.
- Node traversal uses `AccessibilityNodeInfo.obtain()` and explicit `recycle()` — be careful to avoid leaking `AccessibilityNodeInfo` objects when modifying tree-walking code.
- The `Comment` inner class is package-private with public fields — simple data holder.

### AndroidManifest

- Requires `SYSTEM_ALERT_WINDOW` permission (for future floating window).
- Declares `<queries>` for `com.ss.android.ugc.aweme` (Android 11+ package visibility).
- AccessibilityService is bound with `BIND_ACCESSIBILITY_SERVICE` permission.

### Accessibility Config (`accessibility_service_config.xml`)

- Event types: `typeWindowContentChanged` and `typeWindowStateChanged`
- Feedback type: `feedbackGeneric`
- Can retrieve window content: `true`
- Notification timeout: 1000ms (debounce)
