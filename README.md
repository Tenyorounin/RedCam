# UltraTone Camera (prototype 0.2)

An Android camera and photo-rendering prototype built around the visual target in the COMPUTER BILD Xiaomi 17 Ultra / Google Pixel 11 Pro XL comparison.

## What it does

- Uses a camera-first interface inspired by the iOS Camera app: live viewfinder, flash control, front/back camera switch, focus tap, pinch zoom, lens zoom stops, mode selection, gallery shortcut, and a large shutter button.
- The live viewfinder renders a throttled, lower-resolution preview of the selected colour profile. The shutter captures, applies the profile at the photo's processing resolution, and saves the finished photo directly to `Pictures/UltraTone`.
- Offers Day, Night, and Portrait modes. Night enhancement uses a CameraX extension where the phone provides one; otherwise it applies the available single-frame profile.
- Portrait mode estimates a relative-depth map on-device, uses the tapped focus point as the focus plane, and increases blur with estimated depth difference. ML Kit's person mask helps protect people edges; the depth model also supports non-person subjects such as toys and flowers. The model is downloaded during build and bundled into the APK, so portrait capture works offline after installation. It adds about 27 MB before APK compression and may take longer on slower phones.
- Zoom stops are shown only when supported by the active lens. Pinch zoom is also available; optical lens changes and maximum zoom depend on the phone.
- The gallery thumbnail opens the image picker so an existing photo can be processed with the same modes.
- Applies an adjustable rendering profile calibrated against seven uploaded Pixel/Xiaomi side-by-side comparison panels and six matched Pixel/X17 Ultra photo pairs. It uses scene-dependent exposure, independent RGB white-balance and tint gains, saturation, midtone contrast, highlight rolloff, and restrained local detail.
- Saves the rendered JPEG in `Pictures/UltraTone`.
- Supports Android 8.0 (API 26) and newer in principle; available camera behavior and output quality depend on each device.

## Current limitations

The renderer was calibrated against seven labeled Pixel/Xiaomi composite panels and six matched Pixel/X17 Ultra JPEG pairs at 1536×1157. The matched set includes a toy, two detail crops, a portrait, and foliage. The X17 is darker on the foliage and toy samples but brighter on the portrait. It also shifts channel balance: for example, the toy target is less red and relatively more blue/green than the Pixel source. The Day profile now applies scene-dependent exposure plus separate red, green, and blue gains, alongside saturation, contrast, highlight rolloff, and local detail. The comparison sheet shows each X17 target, Pixel input, and an offline simulation of the current Day profile. Average brightness and channel means track these six samples closely, but this does not establish a general match across scenes. These 1536×1157 article JPEGs are not native sensor files, so they do not support reliable tuning of sensor noise or original fine detail.

The article does not provide the original full-resolution camera files, camera settings, or complete processing parameters. The profile is an original image adjustment and does not copy Xiaomi or Leica software. Results will vary with the input photo, camera sensor, lens, exposure, and phone-specific image processing.

It cannot recreate the Xiaomi's sensor, lens, optical zoom, autofocus, exposure decisions, or proprietary multi-frame capture. The comparison is an offline simulation and has not been verified on-device against fresh captures. The live processed view is throttled and lower resolution to keep preview responsive; it is a guide to the saved rendering, not a pixel-exact match. Portrait blur estimates relative depth from one image; it does not calculate physical distance, and thin objects, reflections, hair, and occlusion boundaries can produce depth errors. Phone movement is not required for a portrait capture. Multi-frame parallax may be explored later as an optional refinement, but moving subjects and camera rotation can make it less reliable than single-image depth estimation. Imported images are downsampled only when needed to keep decoded images near or below 16 megapixels, which limits memory use on older devices.

Night mode falls back to a tonal adjustment when the phone lacks a Night extension; that fallback cannot recover detail that the original capture did not record or combine multiple exposures.

## Build

### Build an APK online with GitHub Actions

The included workflow builds a debug APK in GitHub's hosted runner and saves it as a downloadable workflow artifact.

1. Create a GitHub repository and upload the contents of this project folder to its root. Keep `.github/workflows/build-apk.yml` in place.
2. Push the files to the repository's `main` branch. The workflow will start automatically. You can also start it from the repository's **Actions** tab with **Run workflow**.
3. Open the completed **Build debug APK** run and download the `ultratone-debug-apk` artifact. Extract it to get `app-debug.apk`.
4. Transfer the APK to the Android phone and install it. Android may ask you to allow installation from the app used to open the APK.

The APK is debug-signed for personal testing. It is not a Play Store release build. GitHub Actions use is free for public repositories; private repositories use the account's included monthly compute allowance and may incur charges if that allowance is exceeded.

The build downloads the UINT8 256×256 depth model from the public `Depth-Anything-Android` model-v2 release. A network connection is required for the first build; the model file is ignored by Git and cached in the local project after download. Model attribution and limitations are listed in [MODEL_NOTICES.md](MODEL_NOTICES.md).

The GitHub Actions workflow compiles a debug APK on every push to `main` and on demand. The APK is debug-signed for personal testing and is not a Play Store release build.

## Reference

The comparison article is at:
https://www.computerbild.de/artikel/cb-Tests-Handy-Vergleich-Test-Apple-Samsung-Xiaomi-Google-Fotovergleich-41232001.html
