# Changelog

All notable changes to **CURSED ACS** (Android Code Studio) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [2.0.0] - 2026-10-28

### 🚀 Highlights & Architectural Overview
Version 2.0.0 represents a major evolutionary leap for CURSED ACS, introducing seamless ChatGPT OAuth Codex streaming, unified multi-theme ergonomics across all visual styles (Violet, Cyan, Crimson), an enriched model telemetry inspector with remaining budget tracking, hardened autonomous build triggers, and comprehensive package configuration diagnostics.

---

### 🛠️ Resolved Issues & Stability Enhancements

#### 1. ChatGPT OAuth Responses API Protocol & SSE Wire Streaming
* **Issue:** Direct ChatGPT subscriber accounts connecting via OAuth encountered HTTP 400 Bad Request, stream disconnections, or stalled responses when fetching models or generating responses.
* **Resolution:** Re-engineered `OpenAICompatibleAdapter.kt` and `UnifiedTransportClient.kt` to conform strictly to the Codex CLI backend protocol (`backend-api/codex/responses`).
* Implemented token synchronization with hardware-backed `SecureKeyStore` ensuring automatic session continuity and refresh token handling.
* Hardened Server-Sent Events (SSE) frame parsing to filter out internal system envelopes (`response.created`, metadata frames), preventing raw JSON payloads from polluting the user chat stream.

#### 2. Autonomous Build Trigger Hardening & False Build Prevention
* **Issue:** Conversational prompts containing keywords or instructions mentioning `FILE_TO_MODIFY:` inadvertently triggered background project builds and patch attempts on casual inputs (e.g., standard greetings like "Hello").
* **Resolution:** Replaced loose substring lookups with strict multiline regex boundaries (`^FILE_TO_(MODIFY|CREATE|PATCH):`) in `AIAgentManager.kt`.
* Updated `WritingRules.kt` to enforce clear operational directives: conversational responses never emit patch blocks unless the user explicitly requests code changes.

#### 3. Update Mechanism & Semantic Version Hierarchy
* **Issue:** Update checker prompted users to "update" (downgrade) from local V2 builds to older GitHub releases (`V1.0.0-beta`) due to version code scaling discrepancies.
* **Resolution:** Rewrote `isUpdateAvailable` in `TomIDEUpdater.kt` with a strict semantic version hierarchy. If the local version is newer than the remote release, update prompts are bypassed automatically. Bumped base version code to `20000` and main version to `2.0.0`.

---

### ✨ New Features & Ergonomic Improvements

#### 4. Unified Layout Ergonomics Across All Themes
* Standardized button positioning, prompt composer layout, and quick action bars across **Violet**, **Cyan**, and **Crimson** themes.
* Removed redundant floating card elements from the Violet theme, aligning its interaction model with Cyan and Crimson for a sleek, cohesive experience.

#### 5. Dynamic Glowing Web Grounding Indicator
* Integrated real-time dynamic styling on the Web Search toggle button:
  * **Active (`READY`):** Glows with the active theme primary color (Cyan / Crimson / Violet) and high-contrast text.
  * **Needs Configuration:** Displays an amber warning indicator.
  * **Disabled:** Renders cleanly in a muted container style.

#### 6. Model Telemetry & Capability Inspector Dialog
* Enriched the Model Information dialog (`dialog_model_info.xml`) with:
  * **Remaining Usage Budget Indicator:** A sleek, minimalist progress bar displaying live remaining token/request budget.
  * **Capability Badges:** Visual chips indicating support for *Tool Calling*, *Multimodal Vision*, *Realtime Streaming*, *Extended Reasoning*, *Auto-Patch*, and *Web Grounding*.

#### 7. Package Configuration & Gradle Environment Health Indicators
* Upgraded the **IDE Configurations -> Gradle** environment dashboard with real-time health verification.
* Displays explicit emerald green status indicators (`● Installed & Ready`) when Gradle distribution, OpenJDK 17, Git, and OpenSSH runtimes are verified and accessible.

#### 8. Multi-Provider & Account Switcher Architecture
* Integrated a dual-tab Provider Switcher dialog cleanly separating direct API key providers (OpenAI, Gemini, Claude, Grok, DeepSeek) from OAuth Account logins (ChatGPT, Grok accounts).
* Enhanced reasoning effort slider to automatically adapt to model-specific parameter profiles.

---

## [1.0.0] - 2026-10-25

### Initial Beta Release
* Autonomous Jujutsu Cursed AI coding agent with multi-file diff patch application.
* Standalone offline native NDK (Clang, LLVM, CMake) compilation on Android.
* Full AGP & Gradle 8.x build system with OpenJDK 17.
* Sora Editor integration with Tree-Sitter syntax highlighting.
* Hardware-backed keystore credential encryption.
