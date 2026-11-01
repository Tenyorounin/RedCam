# UltraTone Camera

An Android camera app with an adaptive colour profile and selectable black-and-white finish.

## Features

- Camera controls for flash, front and back cameras, tap to focus, pinch to zoom, and Day, Night, and Close-up modes.
- A real-time GPU viewfinder effect. Captured photos retain full camera resolution and are saved to `Pictures/UltraTone`.
- Adaptive tone and colour processing that adjusts contrast, highlight rolloff, shadows, saturation, and selective hues to the scene.
- A black-and-white finish with its own channel mix and tonal curve.
- Close-up mode uses on-device relative depth and the tapped focus point to control background blur. A person mask helps preserve subject edges; depth estimation also supports non-person subjects.
- Night mode uses the phone's CameraX night extension where supported. Otherwise, the app captures two frames, aligns them, and averages stable pixels to reduce noise and limit ghosting from movement.
- Imported photos can be processed with the same modes and finishes.

## Processing notes

The colour profile applies a scene-aware tone curve, highlight rolloff, shadow shaping, saturation separation, and selective colour changes. It works on the phone's processed camera image; it does not recreate another camera's sensor, lens, or image pipeline. The live viewfinder and saved-photo renderer use matching tone and colour equations so the selected look carries through to the capture.

Close-up blur estimates relative depth rather than measured distance. Hair, thin objects, reflections, and occlusion boundaries can produce depth errors. Phone movement is not required. Imported images above 16 megapixels are downsampled for memory safety.

## Build and install

GitHub Actions builds an arm64 debug APK for initial Pixel validation. The APK is debug-signed for sideloading, not a Play Store release. The workflow downloads and bundles the depth model, so close-up blur works offline after installation; the first build needs network access.

Model attribution is listed in [MODEL_NOTICES.md](MODEL_NOTICES.md).
