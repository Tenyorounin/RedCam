# UltraTone Camera (prototype 0.1)

An Android camera and photo-rendering prototype built around the visual target in the COMPUTER BILD Xiaomi 17 Ultra / Google Pixel 11 Pro XL comparison.

## What it does

- Captures a photo with the Android CameraX camera interface.
- Offers Day, Night, and Portrait modes. Night capture enhancement and portrait background blur use the phone's CameraX vendor extension when available; otherwise the app applies a mode-specific rendering profile and indicates that the capture effect is unavailable.
- Provides a zoom slider over the range reported by the active camera. Available optical lens switching and digital zoom depend on the phone.
- Imports an existing photo, including one processed by the phone's stock camera app.
- Applies an adjustable rendering profile calibrated against seven uploaded Pixel/Xiaomi side-by-side comparison panels and six matched Pixel/X17 Ultra photo pairs. It uses scene-dependent exposure, independent RGB white-balance and tint gains, saturation, midtone contrast, highlight rolloff, and restrained local detail.
- Saves the rendered JPEG in `Pictures/UltraTone`.
- Supports Android 8.0 (API 26) and newer in principle; available camera behavior and output quality depend on each device.

## Current limitations

The renderer was calibrated against seven labeled Pixel/Xiaomi composite panels and six matched Pixel/X17 Ultra JPEG pairs at 1536×1157. The matched set includes a toy, two detail crops, a portrait, and foliage. The X17 is darker on the foliage and toy samples but brighter on the portrait. It also shifts channel balance: for example, the toy target is less red and relatively more blue/green than the Pixel source. The Day profile now applies scene-dependent exposure plus separate red, green, and blue gains, alongside saturation, contrast, highlight rolloff, and local detail. The comparison sheet shows each X17 target, Pixel input, and an offline simulation of the current Day profile. Average brightness and channel means track these six samples closely, but this does not establish a general match across scenes. These 1536×1157 article JPEGs are not native sensor files, so they do not support reliable tuning of sensor noise or original fine detail.

The article does not provide the original full-resolution camera files, camera settings, or complete processing parameters. The profile is an original image adjustment and does not copy Xiaomi or Leica software. Results will vary with the input photo, camera sensor, lens, exposure, and phone-specific image processing.

It cannot recreate the Xiaomi's sensor, lens, optical zoom, autofocus, exposure decisions, or proprietary multi-frame capture. The comparison is an offline simulation and has not been verified on-device against fresh captures. For better Pixel results, import a Pixel stock-camera JPEG and apply this profile. Imported images are downsampled only when needed to keep decoded images near or below 16 megapixels, which limits memory use on older devices.

Portrait mode does not synthesize a software depth map when the phone lacks a Bokeh extension. Night mode falls back to a tonal adjustment when the phone lacks a Night extension; that fallback cannot recover detail that the original capture did not record or combine multiple exposures.

## Build

### Build an APK online with GitHub Actions

The included workflow builds a debug APK in GitHub's hosted runner and saves it as a downloadable workflow artifact.

1. Create a GitHub repository and upload the contents of this project folder to its root. Keep `.github/workflows/build-apk.yml` in place.
2. Push the files to the repository's `main` branch. The workflow will start automatically. You can also start it from the repository's **Actions** tab with **Run workflow**.
3. Open the completed **Build debug APK** run and download the `ultratone-debug-apk` artifact. Extract it to get `app-debug.apk`.
4. Transfer the APK to the Android phone and install it. Android may ask you to allow installation from the app used to open the APK.

The APK is debug-signed for personal testing. It is not a Play Store release build. GitHub Actions use is free for public repositories; private repositories use the account's included monthly compute allowance and may incur charges if that allowance is exceeded.

The current execution workspace did not include Android SDK/Gradle binaries, so I have not compiled this project into an APK here.

## Reference

The comparison article is at:
https://www.computerbild.de/artikel/cb-Tests-Handy-Vergleich-Test-Apple-Samsung-Xiaomi-Google-Fotovergleich-41232001.html
