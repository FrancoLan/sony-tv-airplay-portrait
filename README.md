# Sony TV AirPlay Portrait

Portrait AirPlay mirroring for a sideways-mounted Sony Android TV, with automatic wake and sleep management.

> 中文简介：为竖装的 Sony Android TV 提供 AirPlay 竖屏镜像，并在投屏开始时自动亮屏、结束后自动熄屏。中文快速说明见文末。

## What it includes

- `airplay-portrait/` — a reproducible patch set for [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) that rotates mirrored video and reports exact session state.
- `AirPlayGuard/` — an Android TV companion app that keeps the receiver discoverable, wakes the panel for mirroring, and puts it back to sleep afterward.
- `setup-tv.sh` — installs both apps and grants Android TV permissions that have no graphical setup flow.

The receiver stays pinned to upstream commit `c8defdd` so builds remain reproducible.

## Compatibility

Developed and tested on a Sony KD-55X8000H running Android TV 10. Other Android TV devices may work, but power management and low-level audio behavior vary by vendor.

Requirements:

- Android TV with ADB enabled
- JDK 17
- Android SDK 36, NDK `27.0.12077973`, and CMake `3.22.1`
- macOS or Linux build host

## Build

```bash
# Receiver: clone pinned upstream source, apply the patch, and build an APK
cd airplay-portrait
./build.sh

# Wake/sleep companion
cd ../AirPlayGuard
./gradlew assembleRelease
```

The receiver build script installs missing Android SDK components with `sdkmanager` when available. APKs are debug-signed for sideloading; no signing key is stored in this repository.

## Install

Enable network debugging on the TV, then run:

```bash
./setup-tv.sh <TV-IP>
```

On the TV, also make these changes manually:

1. Disable Sony's built-in AirPlay receiver so it does not occupy port 7000. Disable AirPlay only, not the shared HomeKit app.
2. Enable **Remote start** so networking remains available while the panel is asleep.
3. In the patched receiver, set:
   - **Resolution:** `portrait`
   - **Picture rotation:** `90` or `270`
   - **Advertise AirPlay video support:** off

Recommended audio settings for the tested Sony TV:

| Setting | Value |
|---|---:|
| Low latency | Off |
| Override audio delay | 250 ms |
| Automatic software audio buffer | Off |
| Software audio buffer | 120 ms |
| Hardware output buffer | 0 (automatic) |

## How it works

The receiver patch rotates the decoded texture in the existing OpenGL composition pass, so rotation does not add another video copy. It also broadcasts mirroring state to AirPlayGuard.

AirPlayGuard holds the multicast and network locks needed for discovery during standby. During an active session it pauses its TCP health probe because the receiver treats a raw probe as a real AirPlay connection, which can otherwise flush playback every 30 seconds. When mirroring ends, the guard displays a cancellable countdown and uses Android device-admin locking to turn the panel off.

## Known limitations

- Rotation applies to screen mirroring, not AirPlay's remote-video/URL playback path.
- Some apps briefly stop sending audio while switching clips. The receiver recovers as soon as the new stream starts.
- Wake-on-traffic is a fallback heuristic. Unrelated heavy network traffic on the TV can also wake the panel.
- Other TVs may require different power or audio settings.

## 中文快速说明

这是给竖着安装的 Sony Android TV 使用的 AirPlay 镜像方案：接收端负责旋转画面，AirPlayGuard 负责待机可发现、投屏亮屏和结束熄屏。

```bash
cd airplay-portrait && ./build.sh
cd ../AirPlayGuard && ./gradlew assembleRelease
cd .. && ./setup-tv.sh <电视IP>
```

电视上需要手动关闭 Sony 原生 AirPlay、打开“远程启动”，并把接收端设为 `portrait` 与 `90/270` 度旋转。KD-55X8000H 的稳定音频参数见上表。

## License

GPL-3.0. The receiver patch is derived from GPL-3.0-licensed upstream code; attribution and source links are retained above.
