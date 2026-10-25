<p align="center">
  <img src="core/resources/src/main/res/drawable/logo_dual_aura.jpg" alt="CURSED ACS Logo" width="120" height="120" style="border-radius: 20px;"/>
</p>

<h1 align="center">CURSED ACS</h1>

<p align="center">
  <b>Advanced On-Device Android IDE with Offline Native NDK Toolchains, Jujutsu AI Agent & Full Gradle Build System</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen?style=flat-square&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Architecture-arm64--v8a%20%7C%20armeabi--v7a-blue?style=flat-square" alt="Architecture">
  <img src="https://img.shields.io/badge/Build%20System-Gradle%208.x%20%2B%20AGP-orange?style=flat-square&logo=gradle" alt="Gradle">
  <img src="https://img.shields.io/badge/Native%20Compilers-Clang%20%7C%20CMake%20%7C%20NDK-red?style=flat-square" alt="Native">
  <img src="https://img.shields.io/badge/License-GPL%20v3.0-blue.svg?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/Release-v1.0.0--Hardened-crimson?style=flat-square" alt="Release">
</p>

---

## Overview

**CURSED ACS** is a self-contained, enterprise-grade Integrated Development Environment (IDE) engineered to build, compile, debug, and run real Android applications directly on physical Android devices — without needing a PC or remote cloud server. 

Building upon the open-source **AndroidIDE** foundation, CURSED ACS introduces a **completely offline C/C++ native NDK build toolchain**, an integrated **Jujutsu AI Coding Agent** with multi-provider reasoning, a **modular 3-tab package configuration architecture**, an automated **Toolchain Doctor**, and **R8 release hardening**.

---

## What It Can Do & Key Features

### 1. On-Device Full Gradle Android Builds
- **Real Android Builds**: Compiles production Java and Kotlin projects with official Android Gradle Plugin (AGP 7.2.0 – 8.13.0+) and Gradle 8.x.
- **Embedded OpenJDK 17**: On-device Java Virtual Machine running javac, D8/R8 dexing, and AAPT2 resource compilation.
- **Live Terminal Build Streaming**: Standard output and error streams pipe in real-time to the bottom sheet terminal with colorized task progression.
- **One-Tap APK Signing & Installation**: Automatically signs assembled debug/release APKs and launches the Android package installer directly on device.
- **Background Execution**: Keep compilation running seamlessly in the background with native notification progress tracking.

### 2. Standalone Offline Native NDK Build Environment
- **Zero-Internet Native Compilation**: Build C and C++ native libraries (`.so`) using embedded Clang, LLVM, and CMake without requiring active internet access.
- **Offline NDK Archive Import**: Direct manual import of `.zip`, `.tar.gz`, `.tgz`, or `.tar.xz` NDK archives from device storage.
- **Zip Slip Path-Traversal Protection**: Safe archive extraction engine protecting system and app sandbox directories against malicious archive paths.
- **Active NDK Version Switching**: Seamlessly toggle between multiple installed NDK toolchains (`aide_ndk_arm64`, standalone NDKs).
- **NDK Smoke Testing**: Built-in verification compiling a native C++ test binary to validate compiler, sysroot, and header integrity.

### 3. Jujutsu Cursed AI Coding Agent
- **Multi-Provider Intelligence**: Direct, high-speed API integration with leading AI providers:
  - **Google Gemini**: Gemini 2.5 Pro, Gemini 2.5 Flash, Gemma 4
  - **Anthropic Claude**: Claude 3.7 Sonnet (Thinking & Standard), Claude 3.5 Haiku
  - **OpenAI**: GPT-4o, GPT-4o-mini, o1, o3-mini
  - **DeepSeek**: DeepSeek-R1 (Full Reasoning), DeepSeek-V3
  - **xAI Grok**: Grok 2, Grok 2 Vision
- **Collapsible `> ⌄` Thought Reasoning**: Parses model `<thought>` and `<think>` reasoning tags into a dedicated collapsible UI block (`#121722` background, crimson left accent bar) keeping chat responses clean.
- **Project-Aware Context Engine**: Indexes project layout, dependencies, manifests, and source trees for accurate multi-file edits.
- **One-Tap Diagnostics & Repair**: Automatically ingests compiler errors and build failures, suggesting and applying contextual source code repairs.
- **Token Telemetry**: Live context usage badges and token estimation per conversation.

### 4. Modular Package Configuration & Lifecycle
- **3 Dedicated Configuration Tabs**:
  - **Gradle Download**: Overview of Gradle distributions, OpenJDK 17, Git, and OpenSSH with individual download and safe removal options.
  - **Offline NDK**: Archive file picker, archive path input, ZIP import button, live active NDK status badge, version dropdown, and Smoke Test.
  - **Miscellaneous**: Modular, decoupled cards for **SDK Manager NDK (Online)**, **CMake Build System**, **Kotlin Language Server**, **Clang Language Server (C/C++)**, and **Eclipse JDT Java Language Server**.
- **Truthful Binary Detection**: Verifies binary presence on disk (`usr/bin`). Automatically greys out and disables the Delete button when a package is not installed.
- **Universal Red Warning Deletion Protocol (`#E53935`)**: Every deletion triggers a high-visibility red confirmation dialog explaining exact files removed and all developer consequences.

### 5. Build Toolchain Doctor
- **Multi-Component Health Diagnostics**: Audits 10 distinct IDE subsystems (SDK Platform, Build Tools, Java Runtime, Gradle, Active NDK, CMake, Compilers, File Permissions, Storage Space, Environment Paths).
- **Mode-Aware Evaluation**: Automatically switches verification rules depending on the active build mode (Gradle vs. Offline NDK vs. Both).
- **Actionable Diagnostic Reporting**: Distinguishes between critical errors, warnings, and passed checks with one-click repair suggestions.

### 6. Intelligent Code Editor & Language Servers
- **High-Performance Editor**: Powered by the Sora Editor engine with smooth scrolling, code mini-map, line numbers, and bracket matching.
- **Multi-Language LSP Support**:
  - **Java**: Eclipse JDT Language Server providing code completion, real-time syntax checking, quick fixes, and AST resolution.
  - **Kotlin**: Kotlin Language Server with symbol navigation, completion, and error diagnostics.
  - **C/C++**: Clangd Language Server offering header navigation, completion, and semantic diagnostics.
  - **XML**: Attribute auto-completion, resource ID resolution, and syntax validation.
- **Tree-Sitter Highlighting**: Native Tree-Sitter grammars for lightning-fast syntax parsing across multiple programming languages.

### 7. Integrated Linux Terminal (Termux Engine)
- **Full Linux Command-Line**: Powered by the Termux terminal subsystem built directly into the IDE.
- **Developer Package Ecosystem**: Pre-configured environment with access to `git`, `openssh`, `clang`, `make`, `tar`, and standard shell utilities.
- **Custom Environment Variables**: Define custom build flags, PATH overrides, and execution variables.

### 8. Visual UI Designer
- **Real-Time Layout Preview**: Instant on-device rendering of Android XML layouts.
- **Visual Drag & Drop**: Place buttons, text fields, cards, and layouts visually with coordinate guides.
- **Attribute Inspector**: Interactively edit view dimensions, margins, paddings, colors, and resource references.

### 9. Version Control & Project Management
- **Full Git Integration**: Clone repositories via HTTPS/SSH, manage branches, inspect diffs, stage files, and commit directly.
- **Non-Destructive Project Removal**: Dedicated option to remove projects from the IDE workspace while keeping all source files intact on disk.
- **Project Templates**: Built-in scaffolding for Empty Views Activity, Compose, C++ Native (CMake), and Modular Libraries.

### 10. Security Hardening & Release Optimization
- **Hardware-Backed Credential Storage**: AI provider keys are encrypted at rest using **Android Keystore AES-256-GCM**. Master keys never leave device hardware security modules (TEE / StrongBox).
- **Protected Components**: Sensitive internal services (like `LogReceiverService`) secured via custom signature permissions; internal activities explicitly unexported (`exported="false"`).
- **R8 Minification & Obfuscation**: Release builds fully shrunk and obfuscated with package flattening (`com.tom.rv2ide.obf.*`), reducing DEX bytecode footprint by **79.0%** (from 142.5 MB to 29.98 MB).
- **100% Local-First Privacy**: Zero tracking pixels, zero analytics telemetry, and zero intermediary proxy servers.

### 11. Zero-Delay Sukuna Domain Expansion Startup Engine
- **Instant Launch Bloom**: HTML5/WebGL vector animation starts at the very first millisecond of launch with true MAPPA anime proportions and 0 vertical distortion.
- **Concurrent Loading**: Background IDE initialization tasks run parallel to the startup visuals.
- **Skip Control**: A sleek `"Tap anywhere to skip ➔"` pill appears as soon as background initialization completes.

---

## Architecture & System Requirements

| Specification | Requirement |
| :--- | :--- |
| **Operating System** | Android 8.0 (Oreo / API 26) or higher |
| **Supported ABIs** | `arm64-v8a` (64-bit recommended), `armeabi-v7a` (32-bit legacy) |
| **RAM** | 3 GB minimum (4 GB+ recommended for large Gradle builds) |
| **Storage** | 3 GB free internal storage for SDK, compilers, and toolchains |
| **Permissions** | Storage Management (`MANAGE_EXTERNAL_STORAGE` / Media Permissions), Post Notifications |

---

## Getting Started

1. **Download**: Obtain the latest release APK (`arm64-v8a` or `armeabi-v7a`) from [GitHub Releases](../../releases).
2. **First-Run Setup**: Choose your preferred development setup mode:
   - **Both (Offline NDK & Gradle)**: Full IDE environment with internet-downloaded Gradle tooling and offline native compilers.
   - **Offline NDK Only**: Immediate zero-internet native C/C++ environment without downloading full terminal packages.
   - **Gradle Only**: Standard Android Java/Kotlin build environment.
3. **Packages Configurations**: Navigate to **Packages Configurations** from the home screen at any time to import offline NDK archives, manage CMake, or install optional language servers.

---

## Building from Source

Ensure you have JDK 17 installed and Android SDK configured with Build Tools 35.0.0+:

```bash
# Clone the repository
git clone https://github.com/AndroidCSOfficial/android-code-studio.git
cd android-code-studio

# Assemble Debug APKs
./gradlew :core:app:assembleDebug

# Assemble Hardened & Obfuscated Release APKs (Dual ABI)
./gradlew :core:app:assembleRelease
```

Release APKs and deobfuscation mappings are generated under:
- `core/app/build/outputs/apk/release/`
- `core/app/build/outputs/mapping/release/mapping.txt`

---

## Upstream Attribution & Open-Source Licenses

CURSED ACS is an advanced fork derived from and built upon the open-source **AndroidIDE** project. We express our deep appreciation and credit to the upstream authors and open-source community:

- **Original Project**: [AndroidIDE](https://github.com/AndroidIDE-Official/AndroidIDE) by **[itsaky](https://github.com/itsaky)** & contributors (Licensed under **GPL-3.0-or-later**).
- **Code Editor**: [Sora Editor](https://github.com/Rosemoe/sora-editor) by **Rosemoe** (Apache 2.0).
- **Terminal Emulator**: [Termux](https://github.com/termux) by **Fredrik Fornwall** & Termux Contributors (GPL-3.0).
- **Syntax Parsing**: [Tree-Sitter](https://github.com/tree-sitter/tree-sitter) by **Max Brunsfeld** (MIT).
- **Eclipse JDT**: [Eclipse JDT Core](https://github.com/eclipse-jdt/eclipse.jdt.core) (EPL-2.0).
- **Networking**: [OkHttp](https://github.com/square/okhttp) by Square, Inc. (Apache 2.0).

In compliance with the **GNU General Public License v3.0**, all source code modifications, scripts, and build tools remain open and licensed under GPL-3.0.

---

## License

```text
CURSED ACS — On-Device Android Integrated Development Environment
Copyright (C) 2021-2024 itsaky & AndroidIDE Contributors
Copyright (C) 2024-2026 CURSED ACS Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
```
