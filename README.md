# NetCam

A modern Android camera app with a frosted-glass UI and an AI-powered Portrait mode.

[![Android CI](https://github.com/devfahim00/NetCam/actions/workflows/android-ci.yml/badge.svg)](https://github.com/devfahim00/NetCam/actions/workflows/android-ci.yml)

## Features

- **Modern glass UI** — frosted-glass controls, hairline light-catching borders, spring
  animations and an edge-to-edge dark design, built entirely with Jetpack Compose.
  No runtime backdrop blur is used, so the UI stays light and runs smoothly on
  every device (Android 7.0+).
- **Portrait mode** — ML Kit *Subject Segmentation* detects the main subject of the
  frame (a person, pet, food, product — anything salient), then a fast pyramid blur
  is applied to the background and the sharp subject cut-out is composited on top
  for a professional depth-of-field look.
- **Full camera controls** — tap to focus with an animated focus ring, pinch to zoom
  (with a live zoom chip), flash modes (Off / Auto / On / Torch) and instant
  front/back switching.
- **Gallery integration** — every shot is saved to `Pictures/NetCam` and the last
  photo appears as a circular thumbnail on the capture screen.

## Tech stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Camera | CameraX (Preview + ImageCapture) |
| AI / Vision | ML Kit Subject Segmentation (`play-services-mlkit-subject-segmentation`) |
| Async | Kotlin Coroutines |

## How portrait mode works

1. The shot is captured and capped at 2560 px on the long side (keeps memory and
   processing time sane on low-end devices).
2. ML Kit returns a subject cut-out bitmap (alpha matted).
3. The whole photo is blurred with a progressive downscale/upscale pyramid — a
   strong, smooth bokeh that costs almost nothing on the CPU.
4. The sharp cut-out is composited over the blurred background.

If no clear subject is found — or the ML model is unavailable (it is downloaded
through Google Play services on first use) — the app gracefully saves the normal
photo and tells the user.

## Build

```bash
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

Every push runs the [Android CI](.github/workflows/android-ci.yml) workflow which
builds the APK and uploads it as an artifact.

## Project structure

```
app/src/main/java/com/devfahim00/netcam/
├── MainActivity.kt                  # entry point, edge-to-edge setup
├── camera/                          # CameraMode / FlashMode / FocusTarget types
├── processing/
│   ├── SegmentationManager.kt       # ML Kit subject segmentation wrapper
│   └── PortraitProcessor.kt         # blur + compositing pipeline
├── save/ImageSaver.kt               # MediaStore / legacy gallery saving
├── ui/
│   ├── CameraScreen.kt              # preview, gestures, capture flow
│   ├── PermissionScreen.kt          # permission rationale card
│   ├── components/                  # glass surfaces, shutter, mode selector…
│   └── theme/                       # colors + dark scheme
└── util/                            # bitmap helpers, futures, intents
```

## Notes

- Package: `com.devfahim00.netcam`, minSdk 24, targetSdk 34.
- The segmentation model requires Google Play services and is downloaded the
  first time Portrait mode is used on a device.
