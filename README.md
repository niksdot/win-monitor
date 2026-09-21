# Win Monitor for Android

A native, dependency-light Android companion to Win Monitor. It provides live CPU, GPU, and RAM telemetry with 60-second rolling graphs.

## Features

- Aggregate CPU utilization from `/proc/stat`.
- RAM used/total and utilization from `ActivityManager.MemoryInfo`.
- Best-effort GPU utilization using common Linux kernel/sysfs telemetry paths exposed by Android device vendors.
- 60-second rolling charts, sampled once per second.
- Dark native Material-era Android UI with no third-party runtime dependencies.
- Portrait layout optimized for phones.
- No network permission and no background service.
- MIT licensed.

## GPU support

Android does not expose a single public, vendor-neutral API for real-time GPU utilization. The app therefore probes a small set of common rootless-readable sysfs interfaces, including Qualcomm KGSL and several Mali/devfreq layouts. Availability depends on the device, Android build, kernel, and vendor permissions. When the kernel does not expose a compatible counter, the GPU panel reports that utilization is unavailable rather than pretending to have a value.

## Requirements

- Android 8.0 (API 26) or newer.
- ARM64 or ARMv7 Android device. The APK is not tied to a single CPU ABI because this app contains only Java bytecode.

## Build

The project uses Android Gradle Plugin 9.4.0, Gradle 9.6, Java 17, and compile/target SDK 36. Android's current documentation lists AGP 9.4.0 as a stable release and documents its Gradle/JDK compatibility. See the Android developer documentation for the corresponding SDK and build-tool setup.

Open the project in Android Studio and build:

```text
Build > Build APK(s)
```

Or from a configured Gradle installation:

```bash
./gradlew assembleRelease
```

The unsigned release APK is written to:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

For a distributable release, sign the APK with your own Android signing key.

## Project structure

- `app/src/main/java/com/niksdot/winmonitor/MainActivity.java` — UI, sampling loop, CPU/RAM/GPU collectors and rolling charts.
- `app/src/main/res/values/` — application theme and colors.
- `app/src/main/AndroidManifest.xml` — launcher activity and app metadata.

## License

MIT. See `LICENSE`.
