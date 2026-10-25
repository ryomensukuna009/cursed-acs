<p align="center">
  <img src="core/resources/src/main/res/drawable/logo_dual_aura.jpg" alt="CURSED ACS Logo" width="120" height="120" style="border-radius: 20px;"/>
</p>

<h1 align="center">CURSED ACS</h1>

<p align="center">
  <b>Advanced On-Device Android IDE with Offline Native NDK Toolchains, Full Gradle Build System & Powerful AI Agent</b>
</p>

<p align="center">
  <a href="https://github.com/ryomensukuna009/cursed-acs/releases"><img src="https://img.shields.io/badge/Release-v1.0.0--Beta-crimson?style=flat-square" alt="Release"></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Architecture-arm64--v8a%20%7C%20armeabi--v7a-blue?style=flat-square" alt="Architecture">
  <img src="https://img.shields.io/badge/Build%20System-Gradle%208.x%20%2B%20AGP-orange?style=flat-square&logo=gradle" alt="Gradle">
  <img src="https://img.shields.io/badge/Native-Clang%20%7C%20CMake%20%7C%20NDK-red?style=flat-square" alt="Native">
  <img src="https://img.shields.io/badge/License-GPL%20v3.0-blue.svg?style=flat-square" alt="License">
</p>

---

## ⚡ Core Capabilities

- **🛠️ Standalone Offline Native NDK & CMake:** Zero-internet C/C++ compilation via embedded Clang, LLVM, and CMake. Direct `.zip` / `.tar.gz` NDK archive import with zip-slip protection.
- **🧠 Jujutsu Cursed AI Coding Agent:**
  - Multiple API Support: Gemini, Claude, OpenAI, DeepSeek, xAI Grok, xkiro, CUSTOM PROVIDER.
  - Advance errors detection: Advance Error Detection system and fix them with AI in one click.
  - Autonomous Multi-File Editing: Context-aware codebase indexing and atomic code diff application.
  - One-Tap Compiler Diagnostics: Ingests Gradle/NDK build failures and auto-generates contextual fixes.
  - Token Telemetry: Live context usage metering and auto-compaction.
- **📦 Full Gradle Build System & OpenJDK 17:** Compiles production Java & Kotlin Android apps with official AGP 7.2–8.x, AAPT2, D8/R8, and one-tap on-device APK signing.
- **💻 Embedded Linux Terminal & Git:** Full Termux bash environment with package manager (`clang`, `make`, `git`, `openssh`). Built-in Git client for branches, diffs, commits, and remotes.
- **🩺 Build Toolchain Doctor:** 10-point automated IDE diagnostic auditing SDK, Java runtime, Gradle, NDK versions, CMake, storage, and permissions.
- **🎨 Modern Editor & Material 3 UI:** Sora Editor with Tree-Sitter highlighting, Eclipse JDT (Java) / Kotlin / Clangd LSP, visual XML layout designer, dynamic Day/Night theming, and edge-to-edge support.
- **🔒 Hardened Security & R8 Optimization:** Hardware-backed Keystore AES-256-GCM encryption for credentials, unexported sensitive services, and full R8 bytecode shrinkage.



**BEST FOR MODDERS, CHEAT MAKERS**


**SEPARATE - SEPARATE GRADLE BUILD SYSTEM AND NDK BUILD SYSTEM[YOU CAN USE aide_ndk_arm64.tar.gz or aide_ndk_arm32.tar.gz ]**
---

## 📱 System Requirements

| Parameter | Specification |
| :--- | :--- |
| **Android Version** | Android 8.0 (API 26) through Android 17[ONLY AN10 TO AN17 TESTED..] |
| **Supported ABIs** | `arm64-v8a` (Primary), `armeabi-v7a` (32-bit legacy) |
| **RAM** | 2 GB minimum (4 GB+ recommended for large Gradle/NDK builds) |
| **Storage** | 3 GB free storage for SDK, compilers, and dependencies[GRADLE(1.5 GB) AND NDK(1.5 GB)] |

---

## 🛠️ Building from Source

```bash
# Clone repository
git clone https://github.com/ryomensukuna009/cursed-acs.git
cd cursed-acs

# Build Debug APKs
./gradlew :core:app:assembleDebug

# Build Hardened & Obfuscated Release APKs (Dual ABI)
./gradlew :core:app:assembleRelease
```

Generated APKs:
- `core/app/build/outputs/apk/release/android-code-studio-arm64-v8a-*.apk`
- `core/app/build/outputs/apk/release/android-code-studio-armeabi-v7a-*.apk`

---

## 📜 Upstream Attribution & License

CURSED ACS is derived from and builds upon the open-source **[AndroidIDE](https://github.com/AndroidIDE-Official/AndroidIDE)** project by itsaky & contributors, utilizing [Sora Editor](https://github.com/Rosemoe/sora-editor), [Termux](https://github.com/termux), [Tree-Sitter](https://github.com/tree-sitter/tree-sitter), and [Eclipse JDT](https://github.com/eclipse-jdt/eclipse.jdt.core).

Licensed under **GNU General Public License v3.0 (GPL-3.0)**.
See [LICENSE](LICENSE) for full details.
