# UltraTone Camera (prototype 0.4)

An Android camera prototype that applies an adaptive, Leica M9-inspired rendering to every captured photo, with a selectable black-and-white finish.

## What it does

- Camera-first controls: live viewfinder, flash, front/back camera switch, tap focus, pinch zoom, supported zoom stops, Day/Night/Portrait modes, and a shutter button.
- The live viewfinder shows a lower-resolution preview of the selected M9-inspired finish. Captures are processed at photo resolution and saved to `Pictures/UltraTone`.
- The colour rendering analyzes each frame's tonal range and colour saturation, then adapts contrast, highlight rolloff, shadow toe, colour saturation and hue response. It is designed to carry the same overall rendering across bright scenes, dark scenes, foliage, portraits and mixed lighting rather than applying a fixed overlay or gamma adjustment.
- Colour and B&W finishes can be selected before capture. The B&W path uses a channel-weighted monochrome conversion and its own tonal curve rather than simply removing saturation.
- Portrait mode estimates relative depth on-device, uses the tapped focus point as the focus plane, and varies blur with the estimated distance. ML Kit's person mask helps protect subject edges; the depth estimate also works with non-person subjects. The model is downloaded during build and bundled into the APK so portrait processing works offline after installation.
- Night mode uses the phone's CameraX night extension where supported. If unavailable, the app captures two frames, estimates hand movement, aligns the second frame, and averages only pixels that appear stable to reduce noise without smearing visibly moving areas. It then estimates a restrained colour-cast correction from neutral midtones, reduces chroma in deep shadows, and applies the same M9-inspired colour or B&W rendering. This fallback is intentionally conservative; it is not equivalent to Pixel Night Sight or the phone maker's full ISP pipeline.
- Imported photos can also be processed with the same modes and colour / B&W finishes.

## Rendering basis and limitations

The Leica M9 is often described as combining a contrasty tone curve, saturated high-chroma colours, a smooth deep-shadow rolloff, and characteristic hue shifts. Published testing describes cyan moving toward blue, red toward orange, orange toward yellow, and yellow toward green. The renderer uses these as directional targets while preserving more moderate colours and adapting its curve to each input scene. These behaviours are described in the M9 review and its color test results: https://www.imaging-resource.com/cameras/leica-m9-review/image/800914/?section=gallery

This is an original software rendering applied to the Pixel's already-processed camera JPEG, not a recreation of the M9 sensor, lens, demosaicing, optical response, or Leica's proprietary JPEG pipeline. The M9's CCD/sensor stack and camera processing affect what data is captured; post-processing cannot reconstruct information that a Pixel capture did not retain. The current profile has not yet been calibrated with matched RAW/DNG files from both a Leica M9 and the target Pixel under controlled light. So it aims for an M9-like rendering, not an exact camera match.

The renderer uses per-image luminance percentiles and saturation statistics to adjust its tone and colour response. Night-mode white-balance estimation is a restrained post-capture correction on the processed JPEG, not a learned illuminant model. It does not make semantic decisions (for example, identify a face and force a preferred skin tone), and it does not use a learned style model. Results will vary with the Pixel's HDR, white balance, exposure and JPEG sharpening. Preview output is throttled / lower resolution and is a guide to the final render, not a pixel-exact simulation.

Portrait blur uses single-image relative depth, not measured distances. Hair, thin objects, reflections and occlusion boundaries can produce depth errors. Phone movement is not required. Images above 16 megapixels are downsampled for memory safety when imported.

## Build and install

GitHub Actions builds a debug APK in the hosted runner. The current build is arm64-only for initial Pixel validation. The APK is debug-signed for sideloading, not a Play Store release. The workflow also downloads a 256×256 Depth Anything model at build time and bundles it for portrait blur; the first build needs network access and the APK includes the model.

When testing on a Pixel, install `app-arm64-v8a-debug.apk` from the Actions artifact. Other Android processor architectures will be added after the arm64 version has been evaluated.

Model attribution is listed in [MODEL_NOTICES.md](MODEL_NOTICES.md).
