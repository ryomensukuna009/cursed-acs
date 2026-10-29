<p align="center">
  <img src="core/resources/src/main/res/drawable/logo_dual_aura.jpg" alt="CURSED ACS Logo" width="120" height="120" style="border-radius: 20px;"/>
</p>

<h1 align="center">CURSED ACS</h1>

<p align="center">
  <b>Advanced On-Device Android IDE with Offline Native NDK Toolchains, Full Gradle Build System & Powerful AI Agent</b>
</p>

<p align="center">
  <a href="https://github.com/ryomensukuna009/cursed-acs/releases"><img src="https://img.shields.io/badge/Release-v2.0.0--Beta-crimson?style=flat-square" alt="Release"></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Architecture-arm64--v8a%20%7C%20armeabi--v7a-blue?style=flat-square" alt="Architecture">
  <img src="https://img.shields.io/badge/Build%20System-Gradle%208.x%20%2B%20AGP-orange?style=flat-square&logo=gradle" alt="Gradle">
  <img src="https://img.shields.io/badge/Native-Clang%20%7C%20CMake%20%7C%20NDK-red?style=flat-square" alt="Native">
  <img src="https://img.shields.io/badge/License-GNU%20GPL%20v3.0-blue.svg?style=flat-square" alt="License">
</p>

---

## ⚡ What's New in V2.0

- **🚀 Direct OAuth & Account Integration:** Full ChatGPT subscriber/free account login via direct Codex wire protocol and xAI Grok login with seamless session persistence via hardware Keystore.
- **🎨 Unified Theming & Ergonomics:** Seamless layout parity across **Violet**, **Cyan**, and **Crimson** themes with standardized button positioning and clean prompt composer cards.
- **📊 Model Telemetry & Capability Badges:** Real-time usage budget tracking with sleek progress indicators and live capability chips (Tool Calling, Vision, Streaming, Reasoning, Auto-Patch, Web Grounding).
- **🌐 Dynamic Glowing Web Grounding:** Theme-aware glowing indicator for active web search grounding.
- **🩺 Package Configuration Dashboard:** Live emerald status indicators (`● Installed & Ready`) for Gradle, OpenJDK 17, Git, and OpenSSH runtimes.
- **🛡️ Hardened Autonomous Coordinator:** Strict regex boundaries preventing unintended build triggers from casual conversational prompts.

---

## ⚡ Core Capabilities

- **🛠️ Standalone Offline Native NDK & CMake:** Zero-internet C/C++ compilation via embedded Clang, LLVM, and CMake. Direct `.zip` / `.tar.gz` NDK archive import with zip-slip protection.
- **📦 Full Gradle Build System & OpenJDK 17:** Compiles production Java & Kotlin Android apps with official AGP 7.2–8.x, AAPT2, D8/R8, and one-tap on-device APK signing.
- **🧠 Cursed AI Coding Agent:**
  - Multiple API Support: Google Gemini, Anthropic Claude, OpenAI / ChatGPT OAuth, DeepSeek-R1, xAI Grok, and Custom Providers.
  - Advance errors detection: Advance Error Detection system and fix them with AI in one click.
  - Collapsible `<thought>` Block: Clean separation between reasoning CoT and response text.
  - Autonomous Multi-File Editing: Context-aware codebase indexing and atomic code diff application.
  - One-Tap Compiler Diagnostics: Ingests Gradle/NDK build failures and auto-generates contextual fixes.
  - Token Telemetry: Live context usage metering, budget remaining progress bar, and auto-compaction.
- **💻 Embedded Linux Terminal & Git:** Full Termux bash environment with package manager (`clang`, `make`, `git`, `openssh`). Built-in Git client for branches, diffs, commits, and remotes.
- **🩺 Build Toolchain Doctor:** 10-point automated IDE diagnostic auditing SDK, Java runtime, Gradle, NDK versions, CMake, storage, and permissions.
- **🎨 Modern Editor & Material 3 UI:** Sora Editor with Tree-Sitter highlighting, Eclipse JDT (Java) / Kotlin / Clangd LSP, visual XML layout designer, dynamic Day/Night theming, and edge-to-edge support.
- **🔒 Hardened Security & R8 Optimization:** Hardware-backed Keystore AES-256-GCM encryption for credentials, unexported sensitive services, and full R8 bytecode shrinkage.

**BEST FOR MODDERS, CHEAT MAKERS**
-There is NDK and Gradle build you will not get any error like - No application module found.....
**for NDK BUILD - aide_ndk_arm64.tar.gz or aide_ndk_arm32.tar.gz**

---

## 📱 System Requirements

| Parameter | Specification |
| :--- | :--- |
| **Android Version** | Android 8.0 (API 26) through Android 17 (tested on Android 10 to Android 17) |
| **Supported ABIs** | `arm64-v8a` (Primary), `armeabi-v7a` (32-bit legacy) |
| **RAM** | 2 GB minimum (4 GB+ recommended for large Gradle/NDK builds) |
| **Storage** | 3 GB free storage for SDK, compilers, and dependencies [Gradle (~1.5 GB) & NDK (~1.5 GB)] |

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
- `core/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- `core/app/build/outputs/apk/release/android-code-studio-arm64-v8a-*.apk`
- `core/app/build/outputs/apk/release/android-code-studio-armeabi-v7a-*.apk`

---

## 📜 Upstream Attribution & License

CURSED ACS is derived from and builds upon the open-source **[AndroidIDE](https://github.com/AndroidIDE-Official/AndroidIDE)** project by itsaky & contributors, utilizing [Sora Editor](https://github.com/Rosemoe/sora-editor), [Termux](https://github.com/termux), [Tree-Sitter](https://github.com/tree-sitter/tree-sitter), and [Eclipse JDT](https://github.com/eclipse-jdt/eclipse.jdt.core).

Licensed under **GNU General Public License v3.0 (GPL-3.0)**.
See [LICENSE](LICENSE) for full details. Detailed version history is available in [CHANGELOG.md](CHANGELOG.md).
