---
name: testing-tethershot
description: Test the TetherShot Android app (LUT presets, auto-export, PTP UI) end-to-end on an Android emulator. Use when verifying TetherShot changes.
---

# Testing TetherShot on an Android emulator

## Setup
1. Android SDK expected at `~/android-sdk` (cmdline-tools, platform-tools, platforms;android-34, build-tools;34.0.0, emulator, system-images;android-34;google_apis;x86_64). If missing, install via `sdkmanager`.
2. KVM permission might be missing for the emulator; workaround: `sudo chmod 666 /dev/kvm`.
3. Build: `./gradlew assembleDebug` (APK at `app/build/outputs/apk/debug/app-debug.apk`). Lint: `./gradlew lintDebug`.
4. Create/boot AVD:
   `avdmanager create avd -n test34 -k "system-images;android-34;google_apis;x86_64" -d pixel_6`
   `emulator -avd test34 -no-snapshot -gpu swiftshader_indirect &` then wait for `sys.boot_completed`.
5. `adb install -r` the APK; push test assets (`.cube` LUT + a colorful gradient JPEG) to `/sdcard/Download/`.

## Test flow (golden path)
- Launch: `adb shell am start -n com.tethershot.app/.ui.MainActivity` — should show main screen with no login.
- Import LUT: tap "Thêm LUT (.cube)", pick the .cube from the SAF picker. Expect status "Đã thêm preset: <name>".
- Process: tap "Xử lý ảnh từ bộ nhớ (thử preset)", pick the gradient JPEG. Expect preview color shift + status "Đã xuất ảnh vào Pictures/TetherShot (n)".
- Verify export: `adb pull /sdcard/Pictures/TetherShot/IMG_*.jpg` and compare pixels to the source with PIL. Use a strong LUT (e.g. red-boost/blue-suppress, LUT_3D_SIZE 2) so the change is unambiguous.
- Control: select "Original (no preset)", process again — exported pixels must be identical to source.
- Negative: tap "Kết nối máy ảnh" with no camera — expect the "Không tìm thấy máy ảnh PTP qua USB…" toast.

## Gotchas
- Host-side mouse clicks on the emulator window may not register in the guest; prefer `adb shell input tap <x> <y>` (device is 1080x2400; screencap output may be scaled — convert coordinates).
- The SAF "Recent" picker shows previously exported images FIRST after the first export. Picking the wrong (already-LUT'd) image makes the control test look broken. Pick by position carefully or browse to Downloads explicitly.
- Real USB PTP camera flow cannot be tested on an emulator — mark it untested; it needs a physical device + OTG cable.

## Devin Secrets Needed
- None (fully offline app; no accounts or API keys).
