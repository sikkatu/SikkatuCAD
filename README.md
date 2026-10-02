# SikkatuCAD

[Русский](README.ru.md) | [中文](README.zh.md)

![SikkatuCAD](app/src/main/res/drawable/ic_sikkatu_logo.png)

Mobile CAD drawing text editor and viewer for Android. Works with **DXF** and **DWG** drawings.
Interface: English / Russian / Chinese.

## Features

- **Open and view** DXF / DWG construction drawings (facades, plans, sections);
- **Translate text labels** (TEXT / MTEXT / ATTRIB) from Russian into English and Thai:
  export texts to JSON → translate → import back into the drawing;
- **Save** edited drawings as DXF (reliable) or DWG (experimental);
- Measurements: distance, angle, area; auto-dimensioning;
- Geometry editing: line, rectangle, circle, arc, polyline, text;
- Layer control, PNG view export.

## Privacy

The app **does not send anything to the internet**. All drawing operations run locally on the device.
You can verify this yourself — the full source code is in this repository.

## Building

```bash
git clone https://github.com/sikkatu/SikkatuCAD.git
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK 34. Native DWG engine (Rust):

```bash
cd rust-bridge
cargo build --release --target aarch64-linux-android --lib
# copy libcadbridge.so to app/src/main/jniLibs/arm64-v8a/
```

## License

Source-available: read, build and use freely — but **no forks, no modifications**.
See [LICENSE.md](LICENSE.md). Third-party components: [NOTICE.md](NOTICE.md).
