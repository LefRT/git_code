# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**可不 (KAFU)** — an Android app that gives AI a physical embodiment. Uses Android AccessibilityService to extract content from Douyin (抖音) in real-time and MediaProjection to capture screen frames, fused into structured context for AI processing. The AI persona is 「可不（KAFU）」, a gentle, clumsy anime girl who comments on Douyin videos with warmth and humor.

The ultimate vision: a physical desktop robot (ESP32-S3 + LCD + servo) that reacts to what the user is watching with expressions, comments, and movements.

## Build & Run

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s DouyinComment      # Comment extraction
adb logcat -s ScreenCapture      # Screen capture
adb logcat -s ZuoYouContext      # Fused context
adb logcat -s ZuoYouAI           # AI service
adb logcat -s ZuoYouFloat        # Floating window
adb logcat -s ChatAiService      # Chat AI
adb logcat -s MusicPlayer        # Music player
adb logcat -s AnimationPlayer    # Animation
adb logcat -s MemoryCollector    # Memory collector
adb logcat -s ScheduleSecretary  # Schedule secretary service
adb logcat -s ScheduleState      # Schedule state machine
adb logcat -s ScheduleData       # Schedule data manager
```

**SDK**: `local.properties` → `sdk.dir=C\:\\Android\\Sdk`

## Project Configuration

- **AGP**: 8.13.0, **compileSdk/targetSdk**: 36, **minSdk**: 21, **Java**: 17
- **Namespace/applicationId**: `com.zuoyou.commentcollector`
- **Dependencies**: appcompat 1.6.1, Material 1.11.0, ConstraintLayout 2.1.4, OkHttp 4.12.0, Security-Crypto 1.1.0-alpha06

## Project Files

```
app/src/main/java/com/zuoyou/commentcollector/
├── AppContext.java              # Phase 3 — unified context data record + TimelineEvent
├── Comment.java                 # record (user, text, likeCount, time, location)
├── CommentCollector.java        # tree traversal + parsing + JSON
├── CommentDiffer.java           # diff algorithm + smart scoring
├── CommentParser.java           # contentDescription parser
├── Constants.java               # prefs keys, SYSTEM_PROMPT, SECRETARY_SYSTEM_PROMPT, timeouts
├── ContextBuilder.java          # fused pipeline (ring buffers)
├── DouyinCommentService.java    # AccessibilityService + 5s timer
├── ScreenCaptureService.java    # MediaProjection capture
├── AiService.java               # DeepSeek API client
├── FloatingWindowService.java   # System overlay — bubble/list/card
├── SettingsActivity.java        # Settings UI
├── MainActivity.java            # DrawerLayout + embedded chat + schedule secretary launch
├── ThemeHelper.java             # Dark/light mode toggle
└── feature/
    ├── ChatActivity.java        # Full-screen chat
    ├── ChatAdapter.java         # 3 viewType adapter + secretary mode
    ├── ChatAiService.java       # Chat AI with history + memory + custom prompt
    ├── ChatSessionManager.java  # JSON persistence (singleton) + pinned/type
    ├── MainImageHandler.java    # Gesture handler (double-tap, drag)
    ├── AnimationPlayer.java     # MP4 intro animation
    ├── MusicPlayer.java         # MediaPlayer singleton + play modes
    ├── MusicMenuPopup.java      # Music drawer panel
    ├── MemoryInfoPopup.java     # Memory panel — expandable sections + delete
    ├── MemoryCollector.java     # Memory collection — grouped by video (VideoEntry)
    ├── ScheduleData.java        # Schedule secretary — data model
    ├── ScheduleDataManager.java # Schedule secretary — singleton, plan loading
    ├── ScheduleStateManager.java # Schedule secretary — state machine + AlarmManager
    └── ScheduleSecretaryService.java # Schedule secretary — foreground service
```

## Architecture

```
Douyin App (Accessibility) + Phone Screen (MediaProjection)
    → DouyinCommentService (5s timer)
        → extractRecursive() + CommentDiffer.diff() + captureOnce()
        → ContextBuilder (ring buffers: comments×5, screenshots×5, timeline×30)
            → sendComment(text) → AiService (DeepSeek API, 3x retry, cancel-safe)
                → FloatingWindowService (bubble → comment list → evaluation card)
            → MemoryCollector (video-grouped: descriptions + high-like comments)
                → ChatAiService (memory context injection in chat)
```

### Schedule Secretary Architecture (Phase 6)

```
assets/daily_plan/schedule_data.json (pre-parsed from 暑期学习计划.md)
    → ScheduleDataManager (singleton, day-of-week rotation)
        → ScheduleStateManager (state machine + AlarmManager scheduling)
            → ScheduleSecretaryService (foreground service, alarm-driven)
                → ChatAiService (SECRETARY_SYSTEM_PROMPT persona)
                    → Secretary session (ChatSessionManager, type="secretary", pinned)
                    → Notification (if chat not visible)
```

**State machine per item**: PENDING → START_SENT → END_CHECK_SENT → ADJUSTING → COMPLETED

**Alarm-driven**: Zero polling. AlarmManager.setExactAndAllowWhileIdle() fires at precise times. Each action recomputes the next alarm.

### Key Constants (Constants.java)

| Constant | Value | Notes |
|----------|-------|-------|
| SYSTEM_PROMPT | KAFU persona text | AiService + ChatAiService shared (default) |
| SECRETARY_SYSTEM_PROMPT | Secretary persona text | ChatAiService custom prompt, 知性沉稳大姐姐 |
| API_TIMEOUT_MS | 15000 | OkHttp timeout |
| CHAT_HISTORY_MAX_MESSAGES | 40 | Chat history cap |
| KEY_MEMORY_ENABLED | "memory_collection_enabled" | SharedPreferences key |
| KEY_SECRETARY_ENABLED | "secretary_enabled" | SharedPreferences key |

## How Comment Extraction Works

1. **5-second timer**: `DouyinCommentService` fires every 5s → `extractComments()` → `CommentDiffer.diff()` → send best-scored comment to AI.

2. **Three-state diff** (`CommentDiffer.diff()`): `NO_UPDATE` / `PARTIAL_UPDATE` / `FULL_UPDATE`. Dedup by `Objects.hash(user, text)`.

3. **Smart scoring**: `score = 0.6 × log(likes+1)/log(maxLikes+2) + 0.4 × min(textLen,50)/50`

4. **Key insight**: Douyin comment content is in `FrameLayout.contentDescription` (comma-separated), NOT in `TextView.text`.

5. **Node lifecycle is critical**: `extractRecursive` and `findLikeCount` use `try-finally` for `recycle()`. Never use a node after `recycle()`, never recycle twice.

## Accessibility Config (`accessibility_service_config.xml`)

- Event types: `typeWindowContentChanged`, `typeWindowStateChanged`
- Feedback: `feedbackGeneric`, can retrieve window content: `true`
- Notification timeout: 1000ms (debounce)

## UI Design — Ice Blue Theme

- Primary: `#7EB6D9`, Background: `#EDF4F8`, Text: `#1A2A3A`
- App name: 可不 (KAFU)
- Dark mode: `values-night/colors.xml` with deep navy backgrounds
- Secretary chat: gray background (`secretary_chat_bg`), gray AI bubbles (`secretary_bubble_bg`)

## Key Architectural Decisions

### FloatingWindowService — Three-State UI
- **BUBBLE** (48dp) — ice-blue glow ring, draggable, left edge + 30% vertical
- **COMMENT_LIST** — avatar circles + nickname + ❤ like count
- **EVALUATION** — AI card below bubble, adaptive width 200-360dp, adaptive dismiss `clamp(chars×45ms+2000ms, 2.5s, 12s)`
- Typing indicator: three bouncing dots during AI thinking

### ScreenCaptureService — MediaProjection
- `captureOnce()` static, called by DouyinCommentService timer
- Pre-allocated `reusableBitmap` + `reusablePixels`
- Android 14+: `registerCallback()` before `createVirtualDisplay()`, check `data != null` not `RESULT_OK`

### AiService — DeepSeek API
- Content hash dedup + reply dedup (recent 5 responses)
- Exponential backoff: 1s/2s/4s, max 3 attempts on 429/500/502/503/504
- Cancel safety: `cancelCurrentCall()` before new request, `isCanceled()` check in `onResponse`
- `sendComment()` calls `loadConfig()` to pick up API key changes

### Chat System
- **ChatActivity** — full-screen, 3 message types (user/AI/typing)
- **ChatAiService** — full history + memory context injection, no retry, supports custom system prompt via `setSystemPrompt()`
- **ChatSessionManager** — JSON persistence, double-checked locking singleton, all methods `synchronized`, supports pinned/type on SessionInfo
- **Embedded chat** — double-tap image → topMargin animation + scale + chat slides up

### Music Player
- **MusicPlayer** — MediaPlayer singleton, `CopyOnWriteArrayList<MusicListener>`, PlayMode (SEQUENTIAL/RANDOM/SINGLE_LOOP)
- **MusicMenuPopup** — LinearLayout View, 180dp wide, drag-driven by MainImageHandler

### Memory System
- **MemoryCollector** — collects video descriptions + high-like comments **grouped by video** (VideoEntry), max 40 videos, max 20 comments/video, likes>50 threshold. `signalVideoChange()` on FULL_UPDATE, `removeVideo()` for deletion, persists to `memory_data.json` (v2 format)
- **MemoryInfoPopup** — white rounded card 280dp, **expandable UI** (▶/▼ per video), fixed height shows 5 items (scrollable to 40), 5 comments initial + "查看更多", ✕ delete button per video

### Schedule Secretary (Phase 6) — Alarm-Driven Daily Manager
- **ScheduleData** — Data model: `ScheduleItem` (id, startTime, endTime, plannedHours, taskName, category, priority), `DailySchedule` (date, items list)
- **ScheduleDataManager** — Singleton. Copies `assets/daily_plan/schedule_data.json` → `filesDir/daily_plan/` on first launch. Generates daily schedule by combining fixed items + day-of-week rotations (Mon-Sun evening tasks). Reads original `E:\daily_plan\暑期学习计划.md` structure.
- **ScheduleStateManager** — State machine per item: PENDING → START_SENT → END_CHECK_SENT → ADJUSTING → COMPLETED/SKIPPED. Handles cross-midnight times (22:00→02:00). AlarmManager.setExactAndAllowWhileIdle() for precise wakeup. State persisted to `schedule_state.json`. Timeouts: 15min for END_CHECK, 30min for ADJUSTING.
- **ScheduleSecretaryService** — Foreground service (`specialUse`). On alarm: queries state machine → builds prompt → calls ChatAiService with `SECRETARY_SYSTEM_PROMPT` → saves to secretary session → shows notification (if chat not visible) → reschedules next alarm.
- **Persona switching** — `ChatAiService.setSystemPrompt(null)` = default KAFU. `setSystemPrompt(SECRETARY_SYSTEM_PROMPT)` = secretary. Service always uses secretary prompt. MainActivity/ChatActivity detect secretary session and switch accordingly.
- **Secretary session** — `ChatSessionManager.SessionInfo.type="secretary"`, `pinned=true`. Always on top of chat history, gray background, cannot be deleted. Created eagerly on app start via `getOrCreateSecretarySession()`.
- **Notification suppression** — `ScheduleSecretaryService.setSecretaryChatVisible(true)` when secretary chat is open; alarm notifications suppressed but messages still saved to chat.

### MainImageHandler Gestures
- Double-tap → embedded chat
- Horizontal drag → music panel (bidirectional)
- Vertical drag → memory panel (up opens, down closes)
- Left edge right-swipe → open left drawer (chat history)
- Direction lock: first move beyond touchSlop locks axis
- Public constants: `V_MAX_TRANSLATE_Y_DP` (230), `V_MIN_SCALE` (0.4f), `V_OPEN_THRESHOLD` (0.4f)

## Settings Keys

| Key | Default | Storage |
|-----|---------|---------|
| api_key | "" | SecurePrefs (AES-256-GCM) |
| api_base_url | https://api.deepseek.com/v1 | Normal prefs |
| model_name | deepseek-chat | Normal prefs |
| memory_collection_enabled | false | Normal prefs |
| secretary_enabled | true | Normal prefs |

## AndroidManifest & Permissions

- `SYSTEM_ALERT_WINDOW` — floating window overlay
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — ScreenCaptureService
- `FOREGROUND_SERVICE_SPECIAL_USE` — FloatingWindowService + ScheduleSecretaryService (Android 14+)
- `POST_NOTIFICATIONS` — Android 13+
- `<queries>` for `com.ss.android.ugc.aweme` — Android 11+
- `ChatActivity`: `exported="false"`, `MainActivity`: `windowSoftInputMode="adjustPan"`

## Logcat Tags

| Tag | Source | Purpose |
|-----|--------|---------|
| DouyinComment | DouyinCommentService | Comment extraction |
| ScreenCapture | ScreenCaptureService | Capture status |
| ZuoYouContext | ContextBuilder | Fused context JSON |
| ZuoYouAI | AiService | AI calls |
| ZuoYouFloat | FloatingWindowService | Floating window |
| ZuoYouMain | MainActivity | Main UI + chat |
| ChatActivity | ChatActivity | Chat lifecycle |
| ChatAiService | ChatAiService | Chat AI calls |
| ChatSession | ChatSessionManager | Session persistence |
| MusicPlayer | MusicPlayer | Playback state |
| AnimationPlayer | AnimationPlayer | Animation |
| MemoryCollector | MemoryCollector | Memory collection |
| ScheduleSecretary | ScheduleSecretaryService | Secretary service |
| ScheduleState | ScheduleStateManager | State machine + alarms |
| ScheduleData | ScheduleDataManager | Plan loading |

## Development Phases

| Phase | Focus | Status |
|-------|-------|--------|
| 1 | AccessibilityService → comment extraction with likes | ✅ Done |
| 2 | MediaProjection (screen capture) | ✅ Done |
| 3 | Context Builder (fuse text + vision via ring buffers) | ✅ Done |
| 4 | AI service + floating window (comment list + evaluation) | ✅ Done |
| 2+ | AI Chat + Music Player + Animation + Memory | ✅ Done |
| 6 | Schedule Secretary — alarm-driven daily manager | ✅ Done |
| 5 | Physical robot (ESP32-S3) | ❌ Pending |
