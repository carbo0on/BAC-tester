# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This is the **BAC Tester** Burp Suite Extension – a tool for automated Broken Access Control testing using the Montoya API.

## Architecture

- **Main Entry Point**: `src/main/java/Extension.java` - implements `BurpExtension` interface
- **Build System**: Gradle with Kotlin DSL, Java 21 compatibility
- **Dependencies**: Montoya API 2026.4 (compile-only), no runtime dependencies
- **Extension Pattern**: Single-class extension that initializes through `initialize(MontoyaApi montoyaApi)` method

## Key Development Commands

```bash
./gradlew build    # Build and test the extension
./gradlew jar      # Create the extension JAR file
./gradlew clean    # Clean build artifacts
```

The built JAR file will be in `build/libs/` and can be loaded directly into Burp Suite.

## Extension Loading in Burp

1. Build the JAR using `./gradlew jar`
2. In Burp: Extensions > Installed > Add > Select the JAR file
3. For quick reloading during development: Ctrl/⌘ + click the Loaded checkbox

## Documentation Structure

- See @docs/bapp-store-requirements.md for BApp Store submission requirements
- See @docs/montoya-api-examples.md for code patterns and extension structure  
- See @docs/development-best-practices.md for development guidelines
- See @docs/resources.md for external documentation and links

## Project Specification

See @BAC-Tester-Burp-Extension-Spec.md for the full feature specification, architecture, UI layout, and implementation milestones.

## Current State

Functional implementation of Phases 1–7. Key packages:

- `capture/` – `CaptureService`: quick-save / save-with-metadata from context menu & hotkey.
- `db/` – `DatabaseManager` (SQLite schema + migrations) and repositories (`TestCaseRepository`,
  `AccountRepository`, `RunRepository`, `FolderRepository`).
- `engine/` – `RunEngine` (canary check, identity swap, dynamic-field rewrite, send, similarity,
  verdict), `DiffUtil` (line-based side-by-side diff), `DynamicField` (CSRF/nonce locators).
- `ui/` – `MainTab` host + `LibraryTab`, `AccountsTab`, `TestRunTab` (+ `OverviewMatrix`),
  `LiveTab`, `CompareTab` (+ `DiffView`), `DashboardTab`, `SettingsTab`, plus `SaveDialog`,
  `DynamicFieldsDialog`, `IdorFuzzDialog`, `ExportImportManager`, and shared `CollapsibleSection`
  / `VerdictStyle` helpers.

Recent additions:

- **Live mode (`LiveTab`)** – Autorize-style passive testing. An `HttpHandler` registered in
  `Extension` routes in-scope **Proxy** responses to `LiveTab`, which replays each under a chosen
  low-priv/anonymous identity via `RunEngine.replayOnce` and shows live verdicts + dual response
  viewers. Replays go out as the Extensions tool, so they never loop back through the handler.
- **Anonymous identity** – an account with no cookies and no headers is treated as unauthenticated;
  identity swap strips the original cookie jar entirely instead of merging (forced-browsing tests).
- **Reflected-identity detection** – `RunEngine.detectReflectedIdentity` flags when victim-specific
  identifiers (emails / UUIDs / long ids / tokens) appear verbatim in the attacker response.
- **IDOR enumeration (`IdorFuzzDialog`)** – context-menu "Fuzz IDOR" marks an identifier, enumerates
  a value list or numeric range under any identity, and flags reachable ids. Self-contained on
  `RunEngine`'s static helpers.
- **Dashboard (`DashboardTab`)** – project-wide verdict tallies as clickable cards.
- **Library Last-Verdict column** – latest (most severe) triage verdict per test case, color-coded.
- **Compare polish** – intra-line **word-level** highlighting (only the changed characters) plus a
  clickable change **minimap** in `DiffView`.
- **Collapsible sections** – unified, fully-clickable `CollapsibleSection` shared across tabs.

Notable behaviours:

- **Similarity** is JSON-aware (minified JSON is pretty-printed before diffing) and strips the
  configured **ignore-patterns** (timestamps / nonces / CSRF tokens) so volatile noise doesn't
  distort the score or the triage verdict.
- **Identity swap** merges cookies (account values override, other cookies preserved) and replaces
  known session headers. **Dynamic fields** (per test case) rewrite CSRF tokens / nonces before replay.
- **Canary** marks a session dead only on 401/403 or a redirect to a login page (not on any non-2xx).
- **Safe Mode** scope is configurable: DELETE-only (default) or all state-changing requests.
- **Compare** offers a highlighted side-by-side `DiffView` (synced scroll, prev/next jump,
  normalized↔raw toggle, Identical banner) plus the Burp-native editors; ↑/↓ move through the
  working set, ←/→ cycle the focused pane's response, Tab/click switches the focused pane.
- **Run pair (A vs B)** from the Library replays the selection under multiple accounts and loads
  them into the Compare working set for direct horizontal comparison.

Refer to the spec for the full intended feature set.
