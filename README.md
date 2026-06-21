# BAC Time-Machine — Burp Suite Extension

Broken Access Control (BAC) / IDOR testing through an **organised request library + comfortable manual comparison + time-based re-testing**.

## Installation

```bash
./gradlew build          # compiles and produces the fat jar
# Output: build/libs/bac-timemachine.jar
```

In Burp Suite: **Extensions → Installed → Add → Java → select** `bac-timemachine.jar`

For quick reload during development: **Ctrl/⌘ + click** the "Loaded" checkbox.

> Database is stored at `~/.bac-timemachine/store.db` by default (configurable in the Settings tab).

---

## Workflows

### W1 — Capture a test case
- **Keyboard shortcut** `Ctrl+Alt+A` while focused in any HTTP message editor (Proxy history / Repeater) → saves immediately to **Inbox**.
- **Right-click → "Send to BAC (save as test case)"** → opens a dialog to choose folder, name, owner account, expected access, and notes.
- **Right-click → "Quick-save to Inbox"** → immediate save without dialog.

### W2 — Organise into folders
Open the **Library** tab. Use **New Folder**, **Rename** (F2 or double-click), and **Move to folder** (right-click or drag-drop) to classify test cases by feature / page / function.

### W3 — Define user accounts
Open the **Accounts** tab → **New account**. Paste auth material (cookies + headers) manually, or use right-click → **"Create/Update account from this request's session"** to auto-extract `Cookie` and `Authorization` headers. Set `expectedAccess` (ALLOWED / DENIED / UNKNOWN) and optionally assign a canary request.

### W4 — Import a session from a captured request
Right-click any request → **"Create/Update account from this request's session"** — the extension parses all `Cookie` values and the `Authorization` header and pre-fills the account dialog.

### W5 — Run tests (replay with a different identity)
Open the **Test Run** tab. Select an account and test cases (folder / filter / all) → click **Run**. The engine:
1. Performs a **canary check** — aborts if the session is expired (no false negatives).
2. Skips state-changing requests (`POST` / `PUT` / `PATCH` / `DELETE`) when **Safe Mode** is ON (default).
3. Replaces `Cookie` and `Authorization` headers with the selected account's auth material.
4. Sends each request and computes **similarity** against the primary baseline.
5. Assigns a triage **verdict** (POTENTIAL_BAC / LIKELY_ENFORCED / EXPECTED_OK / ANOMALY / REVIEW).

### W6 — Manual comparison (the core feature)
Open the **Compare** tab. The two-column view shows any two stored responses side-by-side with:
- Muted diff highlighting (line and word level).
- Synchronised vertical scroll.
- `↑ / ↓` arrows to navigate between test cases in the working set.
- `← / →` arrows to cycle responses inside the focused pane (all baselines + all session results).
- **Send to Burp Comparer** button for deeper inspection.
- **Normalised mode** — ignore-pattern lines are shown greyed-out / struck-through and excluded from similarity.

---

## Security notes

- **Authorised targets only.** Use this tool only on targets you are explicitly permitted to test (signed engagement / bug-bounty scope).
- The built-in scope check (`api.scope().isInScope`) warns before sending out-of-scope requests, but does not replace your responsibility.
- **Safe Mode** is ON by default to prevent accidental data modification on production targets.
- All auth material (session cookies, tokens) is stored **locally** in SQLite on your machine and is never sent to any third party.
