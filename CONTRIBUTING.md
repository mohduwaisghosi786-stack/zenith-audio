# Contributing to Zenith Audio

Thank you for your interest in contributing to **Zenith Audio**! We welcome contributions from developers, audio engineers, gamers, and audiophiles worldwide.

---

## 🛠️ How to Contribute

1. **Fork the Repository**:
   Click the **Fork** button at the top right of this page.

2. **Clone Your Fork**:
   ```bash
   git clone https://github.com/<your-username>/zenith-audio.git
   cd zenith-audio
   ```

3. **Create a Feature Branch**:
   ```bash
   git checkout -b feature/my-awesome-feature
   ```

4. **Make Your Changes**:
   - For C++ server changes (`server/` or `server_windows/`): adhere to modern C++20 conventions and maintain lock-free, zero-allocation real-time safety.
   - For Android client changes (`android/`): adhere to Kotlin style guidelines and Jetpack Compose best practices.

5. **Test Your Changes**:
   - Verify low-latency audio capture and streaming.
   - Run tests using `./scripts/latency_benchmark.sh`.

6. **Submit a Pull Request**:
   Push to your branch and open a PR with a clear description of the problem solved or feature added.

---

## 🐛 Reporting Bugs

Please open an issue via the [Bug Report Template](.github/ISSUE_TEMPLATE/bug_report.md) with:
- OS and version (Linux distribution, Windows 11 build)
- Android phone model and OS version
- Network type (5GHz Wi-Fi, 2.4GHz Wi-Fi, or USB Tethering)
- Relevant console logs or `adb logcat` output.

---

## 💡 Feature Suggestions

Have ideas for new DSP presets, spatial audio models, or UI themes? Open an issue using the [Feature Request Template](.github/ISSUE_TEMPLATE/feature_request.md)!
