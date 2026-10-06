# Zenith Audio Server — Windows 11 Native Engine

Native ultra-low-latency audio streaming server for **Windows 11**, broadcasting desktop audio (WASAPI Loopback Capture) to Android devices running **Zenith Audio**.

---

### Features on Windows 11
- **WASAPI Hardware Loopback Capture**: Captures 100% bit-accurate system audio (games, YouTube, Spotify, Discord) with 0ms overhead.
- **Ultra-Low-Latency Opus Encoding**: 48 kHz stereo, 10ms frame size, dynamic bitrates from 64 to 320 kbps.
- **Universal Multi-Subnet Discovery**: Broadcasts discovery beacons on all Wi-Fi, Ethernet, and USB tethering adapters. The Android app discovers Windows 11 instantly without typing IPs.
- **Zero-Drop Auto-Pilot Compatible**: Works seamlessly with Zenith Android's Wi-Fi ⮂ USB tethering auto-failover.

---

### How to Build on Windows 11

#### Prerequisites:
1. **Windows 11** (64-bit)
2. **Visual Studio 2022** (with "Desktop development with C++") or **MinGW-w64**
3. **CMake** (3.20+)
4. **libopus** (e.g. via `vcpkg install opus:x64-windows` or prebuilt DLL/LIB)

#### 1-Click Build:
Double-click `build_windows.bat` or run in PowerShell / Command Prompt:
```cmd
mkdir build
cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
```

#### Running:
```cmd
zenith-server-win11.exe --port 59100 --bitrate 320
```

Once running, launch the **Zenith Audio** Android app on your phone. It will automatically detect your Windows 11 PC as `WIN11 (<PC-NAME>)` and connect in 1 tap!
