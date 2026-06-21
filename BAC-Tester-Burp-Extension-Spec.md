# BAC Time-Machine — Burp Suite Extension Specification (v2)

> **هدف هذا الملف:** مواصفات كاملة وقابلة للتنفيذ مباشرة بواسطة Claude Code لبناء إضافة Burp Suite لاختبار Broken Access Control (BAC) و IDOR.
> **التركيز الأساسي في v2:** *المقارنة اليدوية بين الردود هي الهدف الرئيسي*. التصنيف الآلي مجرد مُساعد فرز (triage) يقترح أيّ النتائج تستحق فحصاً يدوياً — وليس الحَكَم النهائي.

---

## 0. TL;DR — ما الذي نبنيه

إضافة Burp تحوّل اختبار التحكم في الوصول إلى عملية **مكتبة منظّمة + مقارنة يدوية مريحة + إعادة اختبار زمني**:

1. أثناء العمل ألتقط ريكويست (بـ **كليك يمين** أو **اختصار كيبورد**) من Proxy history أو Repeater → يُحفظ كـ **test case** مع **الريسبونس الأصلي (baseline)** في قاعدة بيانات دائمة.
2. أنظّم الـ test cases في **مجلدات/تصنيفات** (إعدادات / صفحة كذا / فانكشن كذا...) مع إعادة تسمية حرة.
3. أُعرّف **أكثر من مستخدم** بصلاحيات مختلفة (أحدهم عالي الصلاحية يعمل حالياً)، وأقدر أستخرج بيانات جلسة مستخدم مباشرة من ريكويست.
4. لاحقاً (بعد شهر مثلاً) أعيد إرسال الريكويستات بهوية حساب آخر → يُحفظ الرد الجديد. **الـ baseline القديم لا يُحذف أبداً — versioning.**
5. **القلب:** واجهة **مقارنة يدوية side-by-side** مريحة للعين، تقارن أي ردّين (baseline قديم / baseline محدّث / نتيجة run)، مع تظليل الفروق وملخص (status + size + similarity)، وزر إرسال للـ Burp Comparer.

**القيمة المميزة:** البُعد الزمني + المكتبة المصنّفة + تجربة مقارنة يدوية احترافية.

---

## 1. القرار التقني (ثابت — لا تغيير)

| البند | القرار | السبب |
|------|--------|-------|
| اللغة | **Java** | PortSwigger توصّي بها رسمياً |
| الـ API | **Montoya API `2026.4`** | الحديث والمدعوم. لا Legacy ولا Jython |
| البناء | **Gradle** + fat/shadow jar | لتضمين `sqlite-jdbc` |
| القاعدة | **SQLite** (`sqlite-jdbc`) | تخزين منظّم ودائم لمتطلب "بعد شهر" + الـ versioning |
| القالب | **Burp Extension Starter Project** | يحتوي `CLAUDE.md` لـ Claude Code |

**Maven coordinate:** `net.portswigger.burp.extensions:montoya-api:2026.4`

---

## 2. المصطلحات

- **Account / Identity:** هوية فيها auth material (cookies و/أو headers مثل `Authorization`). تُضاف بحرية (multi-user). كل حساب له اسم، دور، canary (اختياري)، و `expectedAccess` افتراضي.
- **Auth Material:** مجموعة cookies + headers تُستبدل لتغيير الهوية.
- **Test Case:** ريكويست محفوظ + ميتاداتا + ينتمي لمجلد.
- **Baseline (versioned):** نسخة ريسبونس محفوظة لـ test case. قد يكون لكل test case **عدة baselines** عبر الزمن؛ لا يُحذف القديم.
- **Folder:** عقدة تصنيف شجرية تحتوي test cases (إعدادات/صفحة/فانكشن...).
- **Run / Result:** تشغيل دفعة بهوية حساب، وكل نتيجة = ردّ جديد محفوظ.
- **Verdict:** تلميح فرز آلي (ليس قراراً نهائياً).

---

## 3. منطق المقارنة (مُساعد الفرز) + المقارنة اليدوية (الأساس)

### 3.1 المقارنة اليدوية — الهدف الرئيسي (انظر تفاصيل UI §5.4)
المستخدم يختار **أي ردّين** من المكتبة (baseline ضد baseline، أو baseline ضد result، أو result ضد result) ويراهما side-by-side مع تظليل الفروق وملخص. هذا هو مركز الأداة وأكثر ما يجب صقله.

### 3.2 التصنيف الآلي (triage hint فقط)
لكل result نحسب: `origStatus` vs `newStatus`، `origLength` vs `newLength`، `similarity` (0–100% على الـ body بعد التطبيع)، و verdict تلميحي:

التطبيع (Normalization): قبل حساب similarity، طبّق **Ignore Patterns** (regex قابلة للتحرير) على timestamps/nonces/CSRF tokens. الـ raw body يُحفظ كما هو دائماً. **في وضع normalized (الخيار ب):** السطور المطابِقة لأنماط التجاهل **تُعرض رمادية باهتة وعليها شطب خفيف** (لا تُخفى)، و**تُستثنى من حساب similarity**. فالمستخدم يرى كل شيء لكنه يعرف ما هو محسوب وما هو متجاهَل (في المثال: لو اتجاهلنا `ts` و `reqId` تطلع similarity = 100%).

```
isMatch = (newStatus == origStatus) AND (similarity >= MATCH_THRESHOLD)   // افتراضي 95%
```

| الشرط | Verdict (تلميح) | لون |
|-------|------|-----|
| `isMatch` AND `expectedAccess == DENIED` | 🚩 POTENTIAL_BAC | أحمر |
| `!isMatch` AND `expectedAccess == DENIED` | ✅ LIKELY_ENFORCED | أخضر |
| `isMatch` AND `expectedAccess == ALLOWED` | ⚪ EXPECTED_OK | رمادي |
| `!isMatch` AND `expectedAccess == ALLOWED` | ⚠️ ANOMALY | برتقالي |
| `expectedAccess == UNKNOWN` أو similarity رمادية (60–95%) | 🔍 REVIEW | أصفر |

> الـ verdict يُستخدم لـ **الفرز والفلترة** ("ورّيني الأحمر والأصفر بس") لتقليل الفحص اليدوي. القرار النهائي دايماً من المقارنة اليدوية.

### 3.3 Session Validity Check (canary) — ضد الـ false negatives
قبل أي run بحساب، شغّل ريكويست canary يجب أن ينجح لهذا الحساب. لو فشل (401/redirect login) → أوقف الـ run وحذّر: "auth material منتهية"، ولا تُصدر نتائج كاذبة.

### 3.4 Re-baseline + تحديث جلسة الـ baseline (versioned — لا يحذف القديم)
- **Re-baseline:** زر `Re-baseline selected` يعيد تشغيل test cases بهوية الـ Owner ويضيف **baseline نسخة جديدة** بتاريخها، مع الإبقاء على القديمة.
- **تحديث/إصلاح جلسة الـ baseline (مطلوب):** لو الـ baseline القديم فيه مشكلة (جلسة كانت منتهية وقت الالتقاط، رد خاطئ، أو تغيّر الـ owner account)، زر **`Fix / Update baseline session`** يتيح اختيار **جلسة Owner مصحّحة (auth material محدّث)** ثم يعيد جلب الرد ويضيفه كـ **نسخة baseline جديدة تُعيَّن primary**، والقديمة تبقى محفوظة للرجوع. متاح من: right-click في Library، وزرّ على عمود OLD في تاب Compare، وشاشة إدارة baselines.
- في المقارنة اليدوية تختار أي نسخة baseline (بما فيها المصحّحة) للمقارنة.

---

## 4. نموذج البيانات (SQLite Schema v2)

```sql
-- شجرة التصنيف
CREATE TABLE folders (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    parent_id  INTEGER REFERENCES folders(id),   -- NULL = جذر
    sort_order INTEGER DEFAULT 0,
    created_at INTEGER
);

CREATE TABLE accounts (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    role_desc     TEXT,
    auth_material TEXT NOT NULL,            -- JSON: {"cookies":{...},"headers":{...}}
    expected_access TEXT DEFAULT 'UNKNOWN', -- ALLOWED / DENIED / UNKNOWN (افتراضي)
    canary_request_id INTEGER REFERENCES test_cases(id),
    created_at    INTEGER,
    updated_at    INTEGER
);

CREATE TABLE test_cases (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT,                    -- قابل لإعادة التسمية
    notes          TEXT,
    folder_id      INTEGER REFERENCES folders(id),  -- NULL = Inbox/غير مصنّف
    owner_acct_id  INTEGER REFERENCES accounts(id),
    method         TEXT NOT NULL,
    url            TEXT NOT NULL,
    host           TEXT NOT NULL,
    port           INTEGER NOT NULL,
    is_https       INTEGER NOT NULL,
    request_raw    BLOB NOT NULL,
    is_state_changing INTEGER NOT NULL DEFAULT 0,
    dynamic_fields TEXT,                    -- JSON locators للحقول الديناميكية
    primary_baseline_id INTEGER,            -- FK -> baselines.id (النسخة المعروضة افتراضياً)
    created_at     INTEGER,
    updated_at     INTEGER
);

-- baselines متعددة النُسخ لكل test case (لا حذف للقديم)
CREATE TABLE baselines (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    test_case_id  INTEGER NOT NULL REFERENCES test_cases(id),
    account_id    INTEGER REFERENCES accounts(id),  -- الْتُقط بأي هوية
    label         TEXT,                    -- "original" / "rebaseline 2026-06-21"
    status        INTEGER,
    length        INTEGER,
    body_hash     TEXT,                    -- SHA-256 بعد التطبيع
    response_raw  BLOB NOT NULL,           -- الريسبونس الكامل (محفوظ دائماً)
    captured_at   INTEGER
);

CREATE TABLE runs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id  INTEGER REFERENCES accounts(id),
    started_at  INTEGER,
    finished_at INTEGER,
    total_cases INTEGER,
    canary_ok   INTEGER
);

CREATE TABLE results (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id          INTEGER NOT NULL REFERENCES runs(id),
    test_case_id    INTEGER REFERENCES test_cases(id),
    account_id      INTEGER REFERENCES accounts(id),
    compared_baseline_id INTEGER REFERENCES baselines(id), -- قُورن بأي نسخة
    expected_access TEXT,
    new_status      INTEGER,
    new_length      INTEGER,
    new_body_hash   TEXT,
    new_response_raw BLOB,
    similarity      REAL,
    verdict         TEXT,
    reviewed        INTEGER DEFAULT 0,
    user_note       TEXT,
    created_at      INTEGER
);

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT
);
-- settings: match_threshold, ignore_patterns(JSON), safe_mode, db_path, hotkey_combo, font_size
```

موقع القاعدة الافتراضي: `~/.bac-timemachine/store.db` (قابل للضبط).

---

## 5. تصميم واجهة المستخدم (Burp UI)

### 5.1 التكامل مع Burp
- **Suite Tab** رئيسي `BAC Time-Machine` عبر `registerSuiteTab(...)`.
- **Context Menu** عبر `registerContextMenuItemsProvider(...)` (يعمل في Proxy/Repeater/Target). عناصر:
  - `Send to BAC (save as test case)` — يفتح dialog حفظ (اختيار مجلد + اسم + owner + expectedAccess + notes).
  - `Quick-save to Inbox` — حفظ فوري بدون dialog (للسرعة).
  - `Create/Update account from this request's session` — يستخرج cookies+Authorization من الريكويست ويعبّي dialog حساب.
  - `Set as canary for account...`.
- **Keyboard Shortcut** عبر `registerHotKeyHandler(HotKeyContext.HTTP_MESSAGE_EDITOR, HotKey.hotKey("BAC: Quick-save request", "Ctrl+Alt+A"), handler)`:
  - يعمل عندما يكون الفوكس داخل HTTP message editor (Proxy history / Repeater).
  - الفعل الافتراضي: **quick-save فوري إلى Inbox** (أسرع تجربة)، والتصنيف لاحقاً من تاب Library.
  - يظهر تلقائياً في الـ Command Palette (Ctrl+K) أيضاً.
  - الاختصار قابل للتغيير من Settings (لازم يحوي Ctrl/Cmd).

### 5.2 التابات الفرعية
1. **Library** — العمود الأيسر **شجرة مجلدات** (folders)، الأيمن **جدول** الـ test cases للمجلد المختار. الأعمدة: name, method, host, path, owner, primary baseline status/length, captured_at. عمليات: New Folder، Rename (F2 / double-click) للمجلد والـ test case، Move to folder (right-click أو drag-drop)، Delete، View request/response، Re-baseline، إدارة baselines (عرض كل النُسخ).
   - **تلوين الخطورة (مطلوب):** الريكويستات المغيِّرة للحالة تُميَّز بصرياً ليأخذ المستخدم حذره عند تحديدها: **DELETE = شارة حمراء**، **PUT/PATCH = برتقالية**، **POST = صفراء**، **GET/HEAD/OPTIONS = محايد**. أيقونة `⚠` صغيرة بجانب الخطير. (مشتقّة من `is_state_changing`/method.)
   - **multi-select + شريط إجراء سفلي:** حدّد عدة ريكويستات (Ctrl/Shift-click أو select-all للمجلد) → شريط يظهر فيه: **منتقي جلسة `Session ▾`** + زر **`Run on selected ▶`** (يشغّل الجلسة المختارة على كل المحدّد — يمرّ بنفس مسار W5: canary ثم safe-mode ثم إرسال). + زر **`Add to working set`** (لفتحهم في Compare للتنقل بالأسهم).
2. **Accounts** — إدارة متعددة المستخدمين: قائمة الحسابات + محرر auth material (cookies + headers)، استيراد جلسة من ريكويست، اختيار canary، expectedAccess الافتراضي.
3. **Test Run** — اختيار حساب (جلسة) + اختيار test cases (مجلد/فلتر/الكل) + Run. أثناء: progress. بعد: جدول النتائج الملوّن بالـ verdicts + ملخص لكل صف. أزرار فلترة سريعة (أظهر POTENTIAL_BAC/REVIEW فقط).
   - **Overview Matrix (لتعدد الجلسات):** جدول **ريكويستات (صفوف) × جلسات (أعمدة)**، كل خلية مربّع ملوّن صغير بحُكمها (أحمر/أخضر/أصفر...) + similarity عند المرور. يعطي نظرة طائر على كل الجلسات دفعة واحدة. الضغط على خلية يفتح تاب Compare على ذلك (الريكويست، OLD مقابل تلك الجلسة).
4. **Compare** — ⭐ **النجم** (انظر §5.4).
5. **Settings** — match_threshold، ignore_patterns، safe_mode، مسار القاعدة، الاختصار، حجم الخط، وأزرار **Export/Import** (§5.5).

### 5.3 الراحة البصرية (مطلوبة في كل الواجهة)
- احترام ثيم Burp: `api.userInterface().currentTheme()` + `applyThemeToComponent(component)` لكل لوحات الإضافة (يدعم الـ dark mode تلقائياً).
- ألوان الفروق **هادئة ومخفّفة** (muted) لا فاقعة، عشان متتعبش العين في جلسات طويلة.
- خط monospace لمحتوى الردود، وحجم خط قابل للضبط (ابدأ من `messageEditorFontSize`).
- مسافات مريحة، وعناوين/هيدر قابلة للطيّ.

### 5.4 ⭐ تاب Compare — المقارنة اليدوية (الأولوية القصوى)
**فلسفة التصميم (مهمة جداً):** الواجهة **نظيفة وغير مزدحمة**. البطل الوحيد على الشاشة = **الردّان (الجلسة القديمة مقابل الجديدة) جنباً إلى جنب**. لا قوائم ريكويستات كثيرة ولا جداول تشتّت العين. الريكويست **مخفي افتراضياً** ويظهر فقط عند الطلب.

**العرض العميق = عمودان فقط (قرار مقصود):** حتى مع وجود **عدة جلسات**، يبقى العرض التفصيلي **OLD مقابل جلسة NEW واحدة** للحفاظ على قابلية القراءة. تعدّد الجلسات يُدار عبر **شريط الجلسات** (تحت)، لا بتكديس أعمدة. (toggle اختياري `multi-column` يعرض OLD + عدة NEW جنباً إلى جنب عند الحاجة، مع تحذير أنه يزدحم — ليس الافتراضي.)

**شريط الجلسات (Session Chips) — لتعدد الجلسات:** أسفل الشريط العلوي، صفّ chips: لكل جلسة اختُبر عليها هذا الريكويست chip يحمل (اسم الجلسة + status + similarity + **لون الحُكم**: أحمر/أخضر/أصفر). الضغط على chip يضع تلك الجلسة في عمود NEW ويقارنها بالـ OLD فوراً. كده ترى **كل الجلسات بلمحة** وتقرأ بعمق واحدة واحدة دون ازدحام.

**التخطيط (من أعلى لأسفل):**

1. **شريط علوي رفيع (slim header) — سطر واحد:**
   - يسار: منتقي مدمج "OLD" (الجلسة القديمة/baseline + اختيار النسخة) — dropdown صغير، مش قائمة مفرودة.
   - يمين: منتقي مدمج "NEW" (الجلسة الجديدة/result).
   - في النص: شارات ملخّص صغيرة وهادئة: `200 → 200` · `4.2KB → 4.2KB` · `Similarity 100%`. ألوان مخفّفة (مش فاقعة).

2. **المسرح الرئيسي — الردّان side-by-side:** يأخذان **معظم ارتفاع الشاشة**.
   - عارضان `createHttpResponseEditor(EditorOptions.READ_ONLY)`، **scroll رأسي متزامن**.
   - **تظليل diff مخفّف** على مستوى السطور/الكلمات (أخضر/أحمر باهتين على خلفية الثيم).
   - زرّا تنقل صغيران `‹ diff › ` للقفز بين الفروق فقط (مفيد للردود الطويلة).
   - عند تطابق الردّين 100%: لافتة لطيفة "Identical" بدل بحر فاضي.

3. **الريكويست عند الطلب فقط:** شريط سفلي قابل للطيّ `▸ Show request` (مطويّ افتراضياً). عند الضغط ينزلق ليُظهر الريكويست المقابل (read-only) للجانب الذي ضغطت عليه. يُطوى تلقائياً ليبقى التركيز على الردود.

4. **شريط أدوات صغير (أيقونات فقط، tooltips):** `⇄ Send to Burp Comparer` (`api.comparer().sendToComparer(...)`) · toggle `normalized ↔ raw` (في normalized: السطور المتجاهَلة رمادية + مشطوبة + مستثناة من similarity — الخيار ب §3.2) · `✓ Reviewed` + ملاحظة منبثقة · `⤢ swap sides`.

5. **التنقل (محوران من الأسهم — مطلوب):** تاب Compare يحمل **"working set"** = قائمة مرتّبة من test cases. شريط تنقّل **رفيع جداً**: `‹ prev` · **`3 / 120`** · `next ›` + اسم/مسار الـ test case الحالي.
   - **↑ / ↓ = التنقل بين الريكويستات:** ينتقل للـ test case السابق/التالي في الـ working set، فيُحدَّث العمودان بنفس الشكل الجميل دون مغادرة الصفحة.
   - **← / → = تبديل الريسبونس داخل العمود المركّز عليه (focused pane):** يلفّ محتوى العمود ذي الفوكس بين **كل الردود المتاحة لهذا الريكويست** (كل نُسخ الـ baselines + رد كل جلسة). فيمكن وضع أي ردّين في أي عمودين والتقليب بينهما بحرية.
   - **الفوكس بين العمودين:** بالضغط على العمود أو بـ `Tab`؛ العمود المركّز عليه له حد مميّز خفيف. عنوان كل عمود يبيّن أي رد معروض فيه الآن (مثلاً "baseline v1" أو "viewer_session").
   - binding عبر Swing `InputMap`/`ActionMap` على لوحة الإضافة (لا قيد Montoya هنا). اختياري: شريط قائمة جانبي نحيف قابل للطيّ للقفز المباشر؛ مطويّ افتراضياً.

**جماليات إلزامية:** يحترم ثيم Burp (`currentTheme()` + `applyThemeToComponent`)؛ مسافات سخية؛ بلا حدود ثقيلة؛ خط monospace للردود؛ شارات ملخّص بحروف صغيرة وألوان هادئة؛ كل شيء "يتنفّس". لا تكدّس عناصر تحكم — أخفِ الثانوي خلف طيّ/أيقونة.

يعمل على **أي ردّين مخزّنين** (old baseline / new baseline / result)، فلا يلزم run كامل لاستخدامه. (مخطّط بصري مرفق في ملف منفصل.)

### 5.5 التصدير والاستيراد (Export / Import) — إلزامي

**Export (bundle):** من Library، اختر مجلدات/test cases (أو الكل) → تصدير ملف **JSON bundle** (امتداد `.bac.json`) يحتوي:
- شجرة الـ **folders** (التصنيفات كاملةً).
- كل **test case**: الميتاداتا + الـ **request (base64)** + **كل نُسخ الـ baselines (versioned)** بما فيها الـ **response القديم (base64)**.
- (الحسابات: auth material **مُستبعَد افتراضياً** من الـ export لأمان الجلسات؛ checkbox اختياري لتضمينه عند الحاجة، مع تحذير.)

**Import (multi-file + categories):**
- file chooser بـ **multi-select** (`JFileChooser.setMultiSelectionEnabled(true)`) — استورد **عدة ملفات دفعة واحدة**.
- **يحافظ على التصنيفات:** يُعيد بناء شجرة المجلدات كما هي. عند تعارض الأسماء، خيار للمستخدم: **(أ) دمج** في المجلدات الموجودة، أو **(ب) عزل** تحت جذر `Imported/<filename>/`.
- **منع التكرار:** test case يُعتبر مكرراً لو تطابق (host + method + path + request hash) — خيار: تخطٍّ أو إنشاء نسخة.
- **ID remapping:** أعد تعيين كل المفاتيح الأجنبية (folders/baselines/owner) عند الإدماج لتفادي التصادم.
- يحافظ على **كل نُسخ الـ baselines** المستوردة (لا يفقد التاريخ القديم).

> الفايدة العملية: تصدّر مكتبة تارجت معيّن، تنقلها لجهاز/مشروع آخر أو تشاركها، وتدمج عدة مكتبات (من عدة تارجتس أو عدة جلسات عمل) في أداة واحدة بضغطة.

---

## 6. سير العمل (Workflows)

### W1 — حفظ test case
- بـ context menu (مع dialog: مجلد/اسم/owner/expectedAccess/notes) أو **Quick-save/الاختصار** (فوري إلى Inbox).
- استخرج request+response، حدّد host/port/https/method، علّم is_state_changing من الـ method.
- أنشئ baseline نسخة "original" (status/length/hash/raw)، واربط primary_baseline_id.

### W2 — التصنيف
- في Library: أنشئ مجلدات، أعد التسمية، انقل test cases بين المجلدات (drag-drop / right-click).

### W3 — تعريف مستخدمين (multi-user)
- Accounts → New: لصق auth material يدوياً، أو **استيراد جلسة من ريكويست** (يستخرج Cookie + Authorization تلقائياً). اربط canary و expectedAccess.

### W4 — استخراج جلسة من ريكويست
- right-click → `Create/Update account from this request's session`: parse الـ Cookie header (كل الكوكيز) + `Authorization` + هيدر جلسة معروفة → عبّي/حدّث حساباً.

### W5 — Test Run (متطلب "بعد شهر")
1. اختر حساب + test cases.
2. (موصى) Re-baseline أولاً بهوية الـ Owner (نسخة جديدة).
3. canary check؛ لو فشل أوقف.
4. لكل test case: انسخ الريكويست → **استبدل الهوية** (احذف Cookie/Authorization القديمة، احقن الجديدة) → عالج الحقول الديناميكية → لو safe_mode و is_state_changing تخطّ (verdict SKIPPED_SAFE) → أرسل → احسب similarity/verdict مقابل primary baseline → خزّن.
5. اعرض النتائج للفرز، ثم افتح المثيرة للريبة في تاب Compare.

### W6 — المقارنة اليدوية (الأساس)
افتح Compare، اختر الجانبين (أي نسختين)، راجع الفروق المظلّلة، استخدم Burp Comparer لو احتجت، علّم reviewed + note.

---

## 7. معالجة الحالات الحرجة (إلزامي)

| المشكلة | الأثر | الحل |
|---------|------|------|
| CSRF/dynamic tokens في الريكويست | false negative | تعليم dynamic_fields + حذف/تحديث القيمة قبل الإرسال |
| انتهاء الجلسة | كل النتائج كاذبة | canary check (§3.3) إلزامي |
| timestamps/nonces في الرد | similarity زائف | Ignore Patterns / Normalization |
| State-changing requests | تلف داتا الهدف | Safe Mode افتراضي ON + تأكيد + علم is_state_changing |
| baseline قديم | مقارنة بحقيقة منقرضة | Re-baseline versioned (§3.4) — القديم يبقى محفوظ |
| bodies ضخمة | بطء UI | اقتطاع المقارنة عند 200KB + كل العمل على worker threads |
| خارج الـ scope | اختبار غير مصرّح | `api.scope().isInScope(url)` تحذير/منع |

---

## 8. مرجع Montoya API (للتنفيذ)

```java
public class Extension implements BurpExtension {
    @Override public void initialize(MontoyaApi api) {
        api.extension().setName("BAC Time-Machine");
        // 1. init SQLite + schema §4
        // 2. build Swing UI + applyThemeToComponent
        // 3. registerSuiteTab(...)
        // 4. registerContextMenuItemsProvider(...)
        // 5. registerHotKeyHandler(HotKeyContext.HTTP_MESSAGE_EDITOR,
        //        HotKey.hotKey("BAC: Quick-save request","Ctrl+Alt+A"), handler)
    }
}
```

الواجهات المحورية:
- **التقاط (context menu):** `ContextMenuItemsProvider.provideMenuItems(ContextMenuEvent)` → `event.selectedRequestResponses()` / `event.messageEditorRequestResponse()`.
- **التقاط (hotkey):** `UserInterface.registerHotKeyHandler(HotKeyContext, HotKey, HotKeyHandler)`؛ داخل الـ handler: `event.messageEditorRequestResponse().ifPresent(editor -> editor.requestResponse())`. (يعمل فقط عند فوكس داخل HTTP message editor.)
- **تعديل الهوية:** `HttpRequest.withRemovedHeader("Cookie")`, `withRemovedHeader("Authorization")`, `withAddedHeader(...)`, وإعادة بناء `Cookie` header كاملاً من auth material.
- **الإرسال:** `api.http().sendRequest(httpRequest)` → `HttpRequestResponse` → `.response().statusCode()/.body()/.toByteArray()`.
- **عارض الردود:** `api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)` (جانبان في Compare).
- **Comparer:** `api.comparer().sendToComparer(byte[]... )` لإرسال الردود لأداة Burp Comparer.
- **الثيم:** `api.userInterface().currentTheme()` و `applyThemeToComponent(component)`.
- **scope:** `api.scope().isInScope(String url)`.
- **تنقّل Compare بالأسهم:** binding داخل لوحة الإضافة عبر Swing `InputMap`/`ActionMap` (KeyStroke لـ UP/DOWN/LEFT/RIGHT) — لا علاقة له بقيد Montoya hotkeys لأنه على مكوّناتنا.
- **Export/Import:** `JFileChooser` مع `setMultiSelectionEnabled(true)` للاستيراد المتعدد؛ التسلسل عبر JSON (مثلاً Gson/Jackson) + base64 للـ request/response bytes.
- **تخزين المسار:** `api.persistence().preferences()` لمسار قاعدة SQLite (والبيانات نفسها في SQLite).
- **لوج:** `api.logging().logToOutput/logToError`.

**Threading:** كل I/O وكل sendRequest على `ExecutorService`؛ تحديث UI عبر `SwingUtilities.invokeLater` فقط. لا تحجب EDT.

---

## 9. البناء والتسليم
- `build.gradle`: `montoya-api:2026.4` (compileOnly) + `sqlite-jdbc` (implementation داخل fat jar عبر shadow plugin).
- المخرَج: `bac-timemachine.jar`. التحميل: `Extensions > Installed > Add > Java`.
- `README.md`: التحميل، W1→W6، الملاحظات الأمنية §12.

---

## 10. خطة التنفيذ المرحلية (نفّذ بالترتيب، مرحلة كل مرة)

**Phase 1 — الهيكل:** starter project + initialize + suite tab فارغ + SQLite + كل جداول §4 (folders/baselines/results...).
**Phase 2 — الالتقاط + المكتبة المصنّفة:** context menu (save + quick-save) + **hotkey** quick-save + إنشاء baseline "original" + تاب Library بشجرة مجلدات + جدول + Rename + Move + New Folder.
**Phase 3 — الحسابات (multi-user):** تاب Accounts + محرر auth material + **استيراد جلسة من ريكويست** + canary linking + expectedAccess.
**Phase 4 — محرك الإرسال:** identity swap + sendRequest + worker threads + safe mode + canary check.
**Phase 5 — ⭐ المقارنة اليدوية (أعلى صقل):** تاب Compare side-by-side + synced scroll + تظليل diff مخفّف + **التنقل بين test cases بأسهم الكيبورد ↑/↓ مع شريط `3/120`** + اختيار أي نسختين (baselines/results) + Send to Burp Comparer + toggle normalized(خيار ب)/raw + reviewed/note + احترام الثيم.
**Phase 6 — التصنيف المساعد:** verdict matrix §3.2 + جدول النتائج الملوّن + فلاتر الفرز.
**Phase 7 — الصقل:** Settings (threshold/ignore patterns/safe mode/hotkey/font) + Re-baseline versioned UI + **Export/Import متعدد الملفات مع حفظ التصنيفات (§5.5)** + dynamic fields §7 + scope check.

> بعد كل Phase: توقّف، أعطِ ملخصاً + أمر بناء + قائمة فحص يدوي، ولا تنتقل قبل تأكيد المستخدم. **Phase 5 هي الأهم — استثمر فيها أكبر قدر من الجودة والتفاصيل البصرية.**

---

## 11. معايير القبول (سيناريوهات اختبار)
1. ✅ التقاط بـ **اختصار كيبورد** من Proxy history (فوكس في message editor) يحفظ test case في Inbox فوراً.
2. ✅ كليك يمين → save with dialog يحفظ في مجلد مختار باسم وميتاداتا.
3. ✅ إنشاء مجلدات، إعادة تسمية مجلد و test case، ونقل test case بين المجلدات.
4. ✅ إضافة حسابين بصلاحيات مختلفة + **استيراد جلسة من ريكويست** يملأ الكوكيز/Authorization صحيحاً.
5. ✅ Run بحساب أقل صلاحية: تطابق + DENIED → POTENTIAL_BAC؛ اختلاف → LIKELY_ENFORCED.
5b. ✅ **تلوين الخطورة:** DELETE شارة حمراء + ⚠، PUT/PATCH برتقالي، POST أصفر، GET محايد.
5c. ✅ **Run from Library:** تحديد عدة ريكويستات + اختيار جلسة + `Run on selected` يشغّلها على المحدّد فقط.
5d. ✅ **تعدد الجلسات:** Overview Matrix (ريكويستات×جلسات) يعرض الأحكام، والضغط على خلية يفتح Compare؛ شريط الجلسات يبدّل عمود NEW بالضغط على chip.
6. ✅ كوكيز ميتة → canary يفشل → توقف بتحذير، لا نتائج كاذبة.
7. ✅ **Compare:** فتح أي ردّين side-by-side، scroll متزامن، فروق مظلّلة بهدوء، toggle normalized (سطور متجاهَلة رمادية مشطوبة ومستثناة من similarity)، و"Send to Comparer" يفتح Burp Comparer بالردين.
8. ✅ **التنقل:** ↑/↓ تنقل بين test cases (working set)؛ ←/→ تلفّ ريسبونس العمود المركّز عليه بين كل ردود الريكويست (baselines + جلسات)؛ Tab/click ينقل الفوكس بين العمودين.
8b. ✅ **تحديث جلسة baseline:** `Fix/Update baseline session` بجلسة owner مصحّحة يضيف نسخة primary جديدة **دون حذف** القديمة.
9. ✅ Re-baseline يضيف نسخة جديدة **دون حذف** القديمة، وأقدر أقارن بأي نسخة.
10. ✅ **Export** ملف bundle فيه المجلدات + الريكويستات + كل الـ baselines القديمة.
11. ✅ **Import** عدة ملفات دفعة واحدة، مع الحفاظ على التصنيفات ومنع التكرار وإعادة ربط المفاتيح.
12. ✅ Safe Mode ON + DELETE test case → يُتخطّى.
13. ✅ إعادة فتح Burp → المكتبة/المجلدات/الـ baselines/النتائج محفوظة (SQLite).
14. ✅ كل الواجهة تحترم ثيم Burp (dark mode) والألوان مريحة.

---

## 12. ملاحظات أمنية (في README)
- للاستخدام على أهداف **مصرّح** بها فقط (bug bounty scope / engagement موقّع).
- scope check مدمج، لكنه لا يُغني عن مسؤولية المستخدم.
- Safe Mode افتراضي ضد تعديل بيانات الإنتاج.
- كل auth material محلي في SQLite على جهاز المستخدم؛ لا يُرسَل لأي طرف ثالث.

---
*نهاية المواصفات v2.*
