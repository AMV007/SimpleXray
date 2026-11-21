# SimpleXray

<img src="https://raw.githubusercontent.com/lhear/SimpleXray/main/metadata/en-US/images/icon.png" alt="icon" width="150">

[![GitHub Release](https://img.shields.io/github/v/release/lhear/SimpleXray)](https://github.com/lhear/SimpleXray/releases)
[![FDroid Release](https://img.shields.io/f-droid/v/com.simplexray.an.svg)](https://f-droid.org/packages/com.simplexray.an)
[![GitHub License](https://img.shields.io/github/license/lhear/SimpleXray)](LICENSE)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/lhear/SimpleXray/.github%2Fworkflows%2Fbuild.yml)](https://github.com/lhear/SimpleXray/actions)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/lhear/SimpleXray/total)

SimpleXray is a high-performance proxy client for Android, **built upon the robust Xray-core ([@XTLS/Xray-core](https://github.com/XTLS/Xray-core))**.

It features an **innovative approach**: **directly executing the official Xray-core binary**, unlike traditional JNI methods. This method isolates core logic from the app layer, boosting stability and maximizing Xray-core's native performance. SimpleXray aims to provide a stable and efficient network experience.

## Key Features

*   **Enhanced Stability**: By running Xray-core as an independent child process, SimpleXray avoids JNI complexities, potential memory issues, and app crashes linked to core library failures. This isolation significantly improves reliability.
*   **High Performance**: Leverages Xray-core's native speed and integrates [@heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) for efficient Tun2socks, ensuring low latency and high throughput.
*   **User-Friendly**: Offers a clean, intuitive UI and simplified setup, making it easy for users to configure and manage connections.

## Unique Technical Approach

Most Xray-core Android clients use JNI to call a compiled .so library. While easy to integrate, this can cause stability issues like performance overhead, cross-language complexity, and app crashes if the core library fails.

**SimpleXray's core difference is how it starts and manages the proxy:**

On installation/update, the embedded Xray-core binary (as `libxray.so`) is extracted. When connecting, the app uses standard Android APIs to **run this binary as a separate child process**, not via JNI calls. Communication happens via defined Inter-Process Communication (IPC).

This design preserves the original Xray-core binary's stability and performance while physically isolating the core process from the main app, enhancing reliability and security.

## Data Files (`geoip.dat` / `geosite.dat`)

The project **includes a simplified version** with basic rules (`"geoip:private"`, `"geoip:cn"`, `"geosite:gfw"`) from [@lhear/v2ray-rules-dat](https://github.com/lhear/v2ray-rules-dat).

## Quick Start

1.  **Requirement**: Android 10 or higher.
2.  **Get App**: Download the APK from the [Release Page](https://github.com/lhear/SimpleXray/releases) or get it from [F-Droid](https://f-droid.org/packages/com.simplexray.an).
3.  **Install**: Install the APK on your device.
4.  **Configure**: Launch the app, import or manually add server details.
5.  **Connect**: Select a config and tap connect.

## Build Guide (Developers)

1.  **Environment**: Install [Android Studio](https://developer.android.com/studio) and configure the Android SDK. The build runs on **Gradle 9.9** with **AGP 9.5** and requires **JDK 17 or newer** (JDK 21 recommended). AGP 9 compiles Kotlin itself, so the `org.jetbrains.kotlin.android` plugin is not applied.
2.  **Get Code**: Clone the repo and submodules:
    ```bash
    git clone --recursive https://github.com/lhear/SimpleXray
    ```
3.  **Import**: Open the project in Android Studio.
4.  **Integrate Core**: The Xray-core binary (`libxray.so`) is **not** committed. It is provisioned automatically into `app/src/main/jniLibs/[abi]/` by the `downloadXrayCore` Gradle task, which unpacks the official `Xray-android-*` release pinned by `XRAY_CORE_VERSION` in [version.properties](version.properties). No Go or NDK needed for this default path. To compile the core from source instead (requires Go `GO_VERSION` and the NDK `NDK_VERSION`), add the `xrayFromSource` property:
    ```bash
    ./gradlew app:assembleDebug -PxrayFromSource
    ```
    Both modes are wired into the native-libs merge, and each records what it produced in `jniLibs/.xray-core-version`, so bumping the pinned version re-fetches automatically.
5.  **Data Files**: `geoip.dat` and `geosite.dat` are required for routing and are **not** committed to the repository. They are downloaded automatically into `app/src/main/assets/` by the `downloadRulesDat` Gradle task, using the versions pinned in [version.properties](version.properties) (`GEOIP_VERSION` / `GEOSITE_VERSION`). The task is wired into the asset merge of every build, so no manual step is needed. To fetch them without building, run:
    ```bash
    ./gradlew app:downloadRulesDat
    ```
6.  **Build**: Sync Gradle and run the build task. The APKs land in `app/build/outputs/apk/renamed/<variant>/` under the release names (`simplexray-<abi>.apk`, `simplexray-universal.apk`).

## Contributing

Contributions are welcome! You can help by:
*   Submitting Bug Reports (Issues)
*   Suggesting Features
*   Submitting Code (Pull Requests)
*   Improving Documentation

## License

This project is licensed under the **[Mozilla Public License Version 2.0](LICENSE)**.
