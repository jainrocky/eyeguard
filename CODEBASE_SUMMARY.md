# EyeGuard — Codebase Summary

> Generated: 2026-08-22 · Flutter app `eyeguard` v1.0.0+1 · Dart SDK `^3.11.5`

## 1. What This App Is

**EyeGuard** is a Flutter **eye-health / myopia-prevention** utility (Android-first). It uses the **front camera + ML Kit face detection** to continuously estimate the distance between the user's face and the phone screen. When the face stays **too close (< 35 cm) for more than 2 seconds**, the app blocks the screen with a full-screen warning overlay until the user moves back to a safe distance (≥ 40 cm).

The app runs in two modes:

| Mode | Engine | Purpose |
|---|---|---|
| **Prototype (in-app)** | Flutter `camera` plugin + `google_mlkit_face_detection` | Live camera preview, calibration, distance HUD, in-app warning overlay |
| **Native monitoring** | Kotlin `DistanceMonitorService` (foreground service, Camera2 + native ML Kit) | Background monitoring while the user uses other apps; system-level overlay warning |

## 2. Tech Stack & Dependencies

- **Flutter / Dart** — Material 3 UI, single-page app
- `camera: ^0.12.0+2` — Flutter-side camera stream (NV21 on Android, BGRA8888 on iOS)
- `google_mlkit_face_detection: 0.14.0` — Flutter-side face detection
- **Native Android (Kotlin)**: Camera2 API + `com.google.mlkit:face-detection:16.1.7` (declared in `android/app/build.gradle.kts`)
- Java 17 / Kotlin JVM target 17 · applicationId `com.rockyjain.eyeguard`
- Platform folders exist for android / ios / macos / linux / web / windows, but only **Android** has real native implementation

## 3. Project Structure

```
lib/
  main.dart                      # 1596 lines — ENTIRE Flutter app lives here
  app/routes.dart                # (empty placeholder)
  app/app.dart                   # (empty placeholder)
  features/home/home_page.dart   # (empty placeholder)
  features/calibration/…         # (empty placeholder)
  features/settings/…            # (empty placeholder)
  features/statistics/…          # (empty placeholder)
  models/distance_reading.dart   # (empty placeholder)
  models/monitoring_settings.dart# (empty placeholder)
  services/monitoring_service.dart # (empty placeholder)
  services/native_bridge.dart    # (empty placeholder)
  widgets/distance_indicator.dart# (empty placeholder)
  widgets/monitoring_status.dart # (empty placeholder)
test/
  widget_test.dart               # STALE — references `MyApp`, which no longer exists
android/app/src/main/kotlin/com/rockyjain/eyeguard/
  MainActivity.kt                # 199 lines  — MethodChannel bridge
  DistanceMonitorService.kt      # 1084 lines — foreground monitoring service
  CameraAnalyzer.kt              # 138 lines  — native ML Kit face detection
  DistanceEngine.kt              # 261 lines  — distance math + smoothing + status
  DistanceWarningController.kt   # 292 lines  — warning state machine (timers/hysteresis)
  OverlayManager.kt              # 474 lines  — system alert-window warning overlay
```

⚠️ The `lib/` folder structure (features / models / services / widgets) is **scaffolding only — all files are empty**. All Dart code is in `lib/main.dart`.

## 4. Core Algorithm — Distance Estimation

Distance is derived from the **face bounding-box width** (pinhole-style inverse proportion):

```
distance_cm = 40.0 × (reference_face_width_px / current_face_width_px)
```

- **Calibration**: user holds the phone at exactly **40 cm** and taps *CALIBRATE AT 40 CM*; the current face width becomes the reference and is persisted natively in `SharedPreferences("eye_guard_preferences")` (`reference_face_width` + `calibration_complete`).
- **Smoothing**: exponential moving average.
  - Flutter prototype: factor **0.25** (75% old / 25% new)
  - Native engine: factor **0.50** (50/50 — deliberately faster for warnings)
- **Thresholds** (both layers): `SAFE = 40 cm`, `WARNING = 35 cm`, `TOO_CLOSE = 30 cm`
- **Status colors**: green (safe) / orange (warning or uncalibrated) / red (too close) / blue (no face)

## 5. Warning Logic (State Machine)

Implemented twice (Flutter prototype in `main.dart`, native in `DistanceWarningController.kt`):

1. Distance drops **below 35 cm** → start a **1-second confirmation timer** (`WARNING_DELAY_MS = 1000`).
2. If distance returns ≥ 35 cm before 1 s elapses → timer cancelled (no false alarms from momentary leans).
3. After 1 s confirmed → **warning shown**; it stays visible until distance reaches **≥ 40 cm** (hysteresis prevents flicker between 35–40 cm).
4. **Face lost while too close**: if the face disappears while the last reading was < 35 cm, the countdown is *preserved* and the warning still fires after 1 s — very close faces often leave the ML Kit detection frame entirely, so losing tracking must not defeat the alert.
5. **Face-lost grace at safe distance**: a missed ML Kit frame does **not** reset the timer for up to **500 ms** (motion / lighting / blur). After 500 ms the face is considered genuinely lost and the timer resets.

## 6. Flutter Layer (`lib/main.dart`)

Key components:

- `main()` — enumerates cameras, picks the **front** camera, launches the app.
- `EyeGuardApp` — MaterialApp, Material 3, deep-purple seed color, single page.
- `_FaceDetectionPageState` — the whole app:
  - **Camera**: `CameraController` (front, `ResolutionPreset.medium`, audio off, NV21 on Android / BGRA8888 otherwise) with a `startImageStream` frame pipeline.
  - **Frame → ML Kit**: converts `CameraImage` to `InputImage.fromBytes` (NV21 single-plane validated on Android); skips frames while a detection is in flight (`_isDetecting` guard).
  - **Calibration**: `_calibrate()` saves face width via MethodChannel `setCalibration`, seeds smoothing at 40 cm. On startup `_loadCalibrationStatus()` restores both the flag and the saved reference face width (`getCalibrationStatus` + `getCalibrationWidth`), so distance estimation works immediately after reopening; if the stored width is missing/zero the app falls back to "Please calibrate".
  - **Reset calibration**: the status card shows **RECALIBRATE | RESET** when calibrated. RESET (`_resetCalibration()`, red, confirmation dialog) invokes native `resetCalibration` to delete both SharedPreferences keys, then clears all local state (flag, reference width, smoothing, pending warning/timer) — returning the app to "Please calibrate at 40 cm". Blocked during native monitoring.
  - **Recalibration**: once calibrated, a compact `RECALIBRATE` button (refresh icon) appears under the "Calibration complete • 40 cm" row in the status card. It re-runs `_calibrate()` after a confirmation dialog (`_confirmRecalibrate()`) warning that the saved reference will be replaced; the success snackbar then reads "Recalibrated at …". Recalibration is blocked while native monitoring is active (the Flutter camera is released and owned by the foreground service) — both via a disabled button state and an early-return guard with a snackbar.
  - **In-app warning**: `_updateWarningState()` mirrors the native state machine with a `Timer`; `_buildWarningOverlay()` renders a full-screen red warning with live distance.
  - **HUD**: status text, estimated distance, face count, face width/height, calibration state.
  - **Session restore**: on startup `_restoreSessionState()` asks the native side (`isMonitoringActive`) whether the foreground service survived an app restart/process death. If yes, the app resumes directly in monitoring mode (STOP button available) and **never touches the camera** — otherwise the Flutter camera initializes normally. This prevents the reopened app from fighting the service over the front camera (which froze the preview and starved the service).
  - **Native handoff** (`_startNativeMonitoring()`): requires calibration → requests **overlay permission** (`requestOverlayPermission`) → flips `_nativeMonitoring` first, clears any **pending prototype warning/timer** (`_tooCloseTimer`, `_showWarning`, `_latestDistance`) so a stale Flutter overlay can never cover the monitoring view, then stops & disposes the Flutter camera → requests notification permission → invokes `startMonitoring` → shows a "monitoring" screen with a **STOP MONITORING** button (`_isStoppingMonitoring` disables it / shows a spinner while stopping). Defense in depth: both `build()`'s overlay condition and the countdown-timer callback also ignore warning state while `_nativeMonitoring` is true.
  - **Stop monitoring** (`_stopNativeMonitoring()`): double-tap guarded → invokes `stopMonitoring` (service releases its camera + overlay in `onDestroy()`) → resets prototype state (warning timer/overlay, smoothed distance, HUD values) → flips back to preview mode → calls `_initializeCamera()` to re-acquire the Flutter camera and restart the frame stream → restores status from stored calibration. `build()`'s loading branch checks `_cameraDisposed` as well as `isInitialized` — but only while `_nativeMonitoring` is false, since the Flutter camera is intentionally released during native monitoring. `_startNativeMonitoring()` also flips the UI flag *before* releasing the camera so no frame renders a dead preview. During native monitoring the prototype status card is hidden (its values would be frozen and it would cover the STOP MONITORING button).
  - **MethodChannel**: `com.rockyjain.eyeguard/monitoring` with methods `setCalibration`, `getCalibrationStatus`, `requestOverlayPermission`, `requestNotificationPermission`, `startMonitoring`, `stopMonitoring`.

## 7. Native Android Layer (Kotlin)

### `MainActivity.kt`
- Registers the MethodChannel handler.
- `setCalibration` → validates `faceWidth > 0`, persists to `SharedPreferences("eye_guard_preferences")`.
- `getCalibrationStatus` → returns the persisted boolean.
- `getCalibrationWidth` → returns the persisted reference face width as double (null when unset), used to restore distance estimation after app restart.
- `resetCalibration` → deletes `reference_face_width` + `calibration_complete` from SharedPreferences (full calibration wipe).
- `isMonitoringActive` → returns whether `DistanceMonitorService` is currently alive (static `isServiceRunning` flag, same process), used by the app's session-restore logic.
- `requestOverlayPermission` → checks `Settings.canDrawOverlays`, opens `ACTION_MANAGE_OVERLAY_PERMISSION` for the package if missing.
- `requestNotificationPermission` → `POST_NOTIFICATIONS` runtime request on Android 13+.
- `startMonitoring` / `stopMonitoring` → start (foreground on O+) / stop `DistanceMonitorService`.

### `DistanceMonitorService.kt` (the production monitor)
- Foreground **`Service`** (`foregroundServiceType="camera"`, `START_STICKY`) with a low-importance ongoing notification ("Eye Guard is monitoring", `FOREGROUND_SERVICE_IMMEDIATE` on Android S+). Tapping the notification opens `MainActivity` via an immutable `PendingIntent`.
- **Camera eviction auto-resume**: when another app takes the camera (`onDisconnected`, `onError`, `CAMERA_IN_USE` while opening, or session configuration failure), the pipeline is torn down cleanly (device/session/`ImageReader`/analyzer) and `scheduleCameraRetry()` retries every **3 s** until the camera is free — monitoring resumes automatically. An `isShuttingDown` flag prevents retries after `onDestroy()`.
- **Camera2 pipeline**: finds front camera → `ImageReader` at **640×480 YUV_420_888** (max 3 images, `acquireLatestImage()` to drop stale frames) → dedicated `HandlerThread("EyeGuardCameraThread")` → capture session with `TEMPLATE_PREVIEW` + continuous autofocus + repeating request.
- **Frame flow**: `ImageReader` → `CameraAnalyzer.analyze()` → face width → `DistanceEngine.calculateDistance()` → `DistanceWarningController.update()` → `syncOverlay()`. `syncOverlay()` runs on **both** face and no-face frames, shows/hides the overlay from the warning state, and falls back to the controller's last known distance while the face is undetectable.
- Loads saved calibration from SharedPreferences in `onCreate`; full, defensive teardown in `stopCamera()` / `onDestroy()` (session, device, reader, analyzer, thread).

### `CameraAnalyzer.kt`
- Native ML Kit detector (`PERFORMANCE_MODE_FAST`, tracking enabled).
- `@Volatile isProcessing` guard drops frames while busy; always `image.close()`.
- Reports the **largest face** by bounding-box area: `(faceCount, width, height)` callback; `(0, null, null)` when no face.

### `DistanceEngine.kt`
- Pure math class: calibration setter, inverse-proportional distance, 50/50 EMA smoothing, `resetSmoothing()`.
- `DistanceStatus` enum: `UNKNOWN / SAFE / WARNING / TOO_CLOSE`.

### `DistanceWarningController.kt`
- Thread-safe (`@Synchronized`) warning state machine implementing the 2 s confirmation, 500 ms face-lost grace, and 40 cm hysteresis described in §5.
- **Close-range face-loss handling**: tracks `lastKnownDistance` (never cleared on face loss). If the face disappears while the last reading was below the warning threshold (< 35 cm), the countdown is *preserved* and the warning is confirmed once 2 s elapse — even with no detectable face. Rationale: a very close face often leaves the ML Kit detection frame entirely; resetting the timer on face-loss would let users defeat the alert by leaning in.

### `OverlayManager.kt`
- `TYPE_APPLICATION_OVERLAY` full-screen window (`FLAG_NOT_FOCUSABLE`, `FLAG_KEEP_SCREEN_ON`, touch-consuming).
- Programmatic card UI: title "You're holding your phone too close", large red live distance readout, footer "Move back to continue using your phone".
- `show()` reuses the existing view and just updates the distance; `hide()`/`destroy()` for teardown.

## 8. Platform Configuration

### Android (`AndroidManifest.xml`)
Permissions: `CAMERA`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`.
Service declared: `.DistanceMonitorService` (`exported=false`, `foregroundServiceType="camera"`).

### iOS (`Info.plist`)
⚠️ **No `NSCameraUsageDescription`** — the Flutter prototype would crash on iOS. The app is effectively Android-only today (native monitoring exists only on Android).

## 9. Known Gaps / Observations

1. **Stale test**: `test/widget_test.dart` still tests the old counter template (`MyApp`) — it won't compile against the current `main.dart`.
2. **Empty scaffolding**: the entire `lib/` package structure is unused; a refactor of `main.dart` into it appears planned but not started.
3. **iOS parity**: no camera permission string, no native monitoring service, no overlay equivalent.
4. **Heuristic accuracy**: distance is a single-axis (face-width) approximation; sensitive to calibration pose, lens FOV, and face size differences. Re-calibration is available on the main screen (confirmation dialog required), but not while native monitoring runs.
5. **Smoothing mismatch**: Flutter prototype uses 0.25 EMA vs native 0.50 — intentional per comments, but worth unifying if the prototype is removed.
6. **Release signing**: Android release build still signs with debug keys (template TODO).
7. **No persistence of settings/thresholds**: thresholds are hard-coded constants on both layers.

## 10. How to Run / Build

```bash
flutter pub get
flutter run                 # debug on a connected Android device (camera required)
flutter build apk           # release (currently debug-signed)
flutter analyze             # static analysis
flutter test                # NOTE: widget_test.dart is stale and will fail
```

Calibration flow on device: launch → allow camera → hold phone ~40 cm away → *CALIBRATE AT 40 CM* → *START MONITORING* → grant "Display over other apps" + notifications → app hands off to the native foreground service. Tapping *STOP MONITORING* stops the service, returns to the live preview, and re-enables recalibration.

## 11. Release & Distribution (GitHub, no store)

- **Signing**: `android/app/upload-keystore.jks` (alias `eyeguard-upload`) + `android/key.properties`. Both are **git-ignored — back them up privately**; losing the keystore means existing users can never install updates.
- **Build**: `flutter build apk --release` → `build/app/outputs/flutter-apk/app-release.apk`.
- **Versioning**: bump `version:` in `pubspec.yaml` (e.g. `1.0.1+2`) before each release build.
- **Automated builds**: `.github/workflows/build-release.yml` builds a signed APK on every `v*` tag push and attaches it to the matching GitHub Release. Requires repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.


