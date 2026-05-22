# Changelog

Format : [Keep a Changelog](https://keepachangelog.com/) — versionning [SemVer](https://semver.org/).

## [0.2.0] — 2026-05-22

### Added
- **Alt+T on a contextually-declared CSS variable lists its declaration sites** : when the caret sits on a `var(--c)` whose `--c` is declared *outside* the registered token sources (consumer override, Angular host binding, React/Vue inline style, vanilla `setProperty` call), Alt+T now surfaces a navigable list of every declaration site instead of the historical "No alternatives found." message. Each row shows the innermost CSS selector (`<runtime>` for runtime injection), the raw value as written, the project-relative file path and 1-based line number, and a colour swatch when the value parses as a colour (`ColorParser`). Clicking a row opens the file at the exact offset via `OpenFileDescriptor`. Static CSS declarations are listed before runtime occurrences so the most actionable sites (concrete values) come first. The popup honours `JBPopupFactory.createPopupChooserBuilder` semantics — type-to-filter by selector / value / path, arrow keys to move, Enter to jump, Esc to dismiss.
- **`DynamicCssVarIndex` now stores full occurrences** : the project-wide index introduced in 0.1.9 was reshaped from `Set<String>` to `Map<String, List<CssVarOccurrence>>`. Each `CssVarOccurrence` carries the variable name, raw value, file path, byte offset, 1-based line, innermost selector (CSS only) and a `isRuntime` flag. Broken-ref detection still reads the `keys` view via `get()` so the 0.1.9 callers are untouched — no behaviour change there. Innermost selector is best-effort : a backwards walk over balanced `{}` from the declaration offset, returning `null` for top-level declarations / malformed input / Sass indent files. Nested SCSS rules return the innermost selector only — walking the full parent chain would require a real parser, and the innermost is enough to identify the declaration at a glance.
- **Pre-warmed lookup** : `TokenAlternativesShower.show` now reads `occurrencesOf(name)` inside its existing `Task.Backgroundable`, so the EDT branch that displays the popup never pays the first-walk cost. The lookup is no-op when the hit's name doesn't start with `--`.

## [0.1.9] — 2026-05-21

### Added
- **CSS variables declared outside token sources are no longer flagged as broken** : Token Flow now recognises any `--name` declared anywhere in the codebase, not just in files registered as token sources. A new project-level `DynamicCssVarIndex` service walks `.ts`/`.tsx`/`.js`/`.jsx`/`.html`/`.vue`/`.css`/`.scss`/`.sass` once and caches the result against the IDE's PSI modification tracker — subsequent reads are free until the user edits something. Two families are detected :
  - **Runtime injection by component code** — Angular host bindings (`[style.--var]`, `[attr.style.--var]`), Angular template property syntax (`style.--var="…"`), DOM `element.style.setProperty('--var', …)` calls (single / double / back-tick quotes), React inline styles (`<div style={{ '--var': value }}>`), Vue `:style` shorthand.
  - **Contextual CSS overrides** — the *CSS Custom Property API* pattern where a generic component reads `var(--c, fallback)` and consumer components set `--c` locally in their own stylesheets. Any bare `--name: value` declaration inside a rule body counts (`:root`, host selectors, BEM-style consumer selectors, …). BEM identifiers like `.block__elem--mod:hover` are correctly ignored thanks to a negative lookbehind on alphanumerics.
  Both the **Analyser** (broken references / reference-integrity score) and the **Hardcoded Values** panel honour the index. A fallback expression alone (`var(--x, inherit)`) is *not* enough — a variable that resolves to nothing anywhere in the project is still a broken reference. Closes [#16](https://github.com/robinlopez/token-flow/issues/16).
- **New setting** : Settings → Token Flow → Analyser → "Recognise CSS variables declared outside token sources (runtime injection + contextual CSS rules)". On by default; uncheck to fall back to the pre-0.1.9 strict behaviour where every reference must point at a declaration inside a registered token source.

## [0.1.8] — 2026-05-20

### Added
- **External token prefixes per scope** : new <b>External</b> tab in Settings → Token Flow → Scopes that lets you list CSS custom-property prefixes injected at runtime by an external framework (PrimeNG `--p-`, Ionic `--ion-`, Angular Material `--mat-`, Material Web `--mdc-`, Vuetify `--v-`, Bootstrap `--bs-`, Radix `--radix-`, Quasar `--q-`, Element Plus `--el-`, Mantine `--mantine-`, Carbon `--cds-`, …). References matching one of these prefixes are treated as known-external and never flagged as broken in the Analyser or the Hardcoded Values panel. Closes [#15](https://github.com/robinlopez/token-flow/issues/15).
- **Auto-detect external prefixes from `package.json`** : the External tab gains an *Auto-detect from package.json…* link that scans the project tree (up to 12 `package.json` files, skipping `node_modules`/`.git`/build outputs), maps known dependencies to their CSS-var prefix and adds them to the current scope in one click. A summary popup lists what was added vs. already-present.
- **Project-open detection notification** : the first time the plugin opens a project that depends on a known UI framework but has no `externalPrefixes` configured, a balloon offers three actions — *Add to all scopes* (one-click acceptance, creates a Common scope if none exists), *Configure…* (opens Settings), *Don't show again* (silences the prompt forever). The "notified" state is persisted per-project so the user is never nagged twice.
- **JSON config schema v2** : `ScopeConfigIO.CURRENT_VERSION` is now `2` — exports include `externalPrefixes` per scope. Importing a v1 file still works (the field defaults to empty list).
- **Hardcoded Values — token-source files are skipped with an explanatory banner** : when the active editor is registered as a `sourcePaths` entry of any scope, the panel now shows *"This file is declared as a token source — hardcoded value detection is disabled here"* and bails out before scanning. No more noise on `_tokens-*.scss` catalogs where every literal is by definition a token declaration. Editing the scope's sources in Settings → Token Flow re-enables the scan if needed.
- **Hardcoded Values — SCSS map values are no longer flagged** : literals living inside a `$name: (…)` or `"key": (…)` SCSS map (the Style-Dictionary / theme-config pattern used by `$themes-config: ("themeX": ("light": ("token": #001a70, …)))`) are now recognised as token *definitions* and skipped. Detection is purely lexical, handles nested maps to any depth, and ignores parens / colons that live inside strings or comments. Works in files that aren't formally declared as token sources too, so mixed files (style + inline map) are handled cleanly.
- **Hardcoded Values — search & property filter** : the panel gains a search field (above the rows) that filters by literal, selector, surrounding CSS property and any suggested token's name / resolved value (multi-term, AND, case-insensitive). A new filter button (round, next to the search field) opens a popup listing every CSS property detected in the current scan, grouped by category (`Spacing`, `Sizing`, `Typography`, `Color`, `Radius`, `Shadow`, `Border`, `Effects`, `Duration`, `Layout`, `Z-index`, `Opacity`, `Icon`, `Other`), with a per-property count. A nested search field inside the popup narrows the property checklist as you type and hides group headers that no longer have a visible row. A *Reset* button clears everything (property excludes + hide-unmatched).
- **Filter popup — "Hide rows without a token suggestion" checkbox** : the toolbar toggle moved into the new filter popup so every view-narrowing control lives in the same place. Broken references stay visible even when the option is on — they're actionable.

### Changed
- **Suggestion engine — token tier awareness** : tokens are now classified as *primitive* (`units-`, `palette-`, `base-`, `primitive-`, `core-`, `scale-`, `raw-`), *component* (`token-`, `comp-`, `component-`) or *semantic* (everything else), and the suggestion ranker prefers semantic tokens when several candidates share the same resolved value. Fixes the long-standing case where `padding: 32px` proposed `var(--units-xl)` instead of `var(--spacing-xl)` — both being legitimately equal to `32px` but the latter being the semantic spacing token.
- **Suggestion engine — property-to-role matching for colors** : the inspection now derives an expected *role* (surface / content / stroke / effect) from the CSS property at the literal's position and matches it against the token name. Tokens carrying the right role segment (`-surface-`, `-content-`, `-stroke-`, `-shadow-`, `-focus-`, etc.) get a strong score boost ; tokens carrying a conflicting role are actively demoted. So `background: #005bff` now prefers `token-actions-high-surface-default` over a same-value `*-content-*` token, and `color: …` does the opposite. Properties mapped : `background[-color]`/`fill` → surface ; `color`/`caret-color`/`accent-color`/`text-decoration-color` → content ; `border-color`/`outline-color`/`stroke` → stroke ; `box-shadow`/`text-shadow`/`outline`/`filter`/`backdrop-filter` → effect.
- **Suggestion engine — multi-criterion ranking** : `score()` was rewritten from "category match minus shortest name wins" to a weighted combination of (category alignment, role alignment, tier, exact-vs-fuzzy, length). Token-name length is now a *last-resort* tiebreaker, so a longer-but-more-semantic name can outrank a shorter primitive one — which the previous ranking made impossible.
- **Suggestion engine — fuzzy color tiebreaking** : when no exact color match exists and the engine falls back to RGB-distance suggestions, ties at near-identical distance are now broken by the semantic score, so a `background: #006cff` surfaces a `-surface-` token rather than a same-distance `-content-` one.

## [0.1.7] — 2026-05-19

### Added
- **Scope configuration import / export** : Settings → Token Flow → Scopes now exposes *Import…* and *Export…* links above the scope list. Export writes every configured scope (name, root, sources, whitelist, analysis excludes) to a versioned JSON file. Import reads such a file and asks whether to **Replace** the current list or **Merge** it (case-insensitive name match — existing scopes get overwritten, new ones are appended). Closes [#12](https://github.com/robinlopez/token-flow/issues/12).
- **Versioned config file** : the JSON carries a `version` field. Older plugin builds refuse files produced by a newer schema rather than silently dropping fields. Current schema version is `1`.
- **Vue Single File Components (`.vue`) support** : tokens declared inside `<style>` and `<style lang="scss">` blocks of a Vue SFC are now indexed, surfaced in the Library, and counted in audit coverage. Hardcoded value detection, code completion, Alt+T alternatives and Go-to-declaration all operate inside the style blocks — and stay silent in `<template>` / `<script>` to avoid corrupting the surrounding HTML / JS. Implementation is text-based (no dependency on the Vue plugin), so it works identically in Community and Ultimate. Multiple `<style>` blocks per SFC are supported; `<style scoped>` and `<style module>` behave the same. `<style src="…">` blocks defer to the regular extension-based walker on the referenced file. Closes [#3](https://github.com/robinlopez/token-flow/issues/3).
- **Library family / sub-family grouping** : inside each big category accordion (Color, Spacing, …), the Library can now insert a two-level hierarchy of headers — family (e.g. `HIGH`, `LOW`, `PRIMARY`) and sub-family (`SURFACE`, `CONTENT`, `STROKE`, …) — derived from the structure of token names alone. No hard-coded vocabulary: the algorithm strips the longest common prefix shared by tokens in a category, trims the trailing state segment, and buckets by what's left, so it adapts to any naming convention. Token rows indent under their header so the alignment reads like a tree, not a flat list. A new toolbar button (group-by icon next to the view-mode toggle) toggles the grouping; **off by default**, the preference persists per-project and lives in the panel rather than in Settings.
- **Sticky category header in the Library** : when scrolling past a category, its header pins to the top of the viewport (with the same chevron behaviour as the in-list row) so the active context is always visible in long catalogues.
- **Justified category bar** : category header is now laid out as `[chevron] TITLE … (count)` — title left-aligned, count badge right-aligned and tinted with the accent foreground so it no longer reads like a resolved token value.

### Changed
- **Variant tooltip — vertical layout** : the per-variant tooltip on token rows (Library, hover popup, inspection tooltips) used to render a horizontal HTML table that blew up width-wise as soon as a token had more than 2-3 variants. Replaced with a justified one-line-per-variant layout: variant label on the left, swatch / category glyph + resolved value right-aligned, themes grouped with discrete subheaders when multiple themes are present. Same data, easier to compare side-by-side.
- **Hardcoded Values — tighter row layout** : rows now indent under their selector header (so the grouping reads as a small tree), the literal column is narrower (110 vs 140 px, recovers space for the suggestion combo), and the suggestion combo grows to 30 px tall so the selected value never has its descender clipped.
- **Hardcoded Values — hide rows without a suggestion** : a new toolbar toggle (filter icon) suppresses literals that have no matching design token; broken references stay visible because they are actionable. **On by default**, persisted per project. The status line reports `… · N hidden` so it's obvious the filter is shrinking the view.
- **Library — Token-kind filter syncs with file chips** : ticking / unticking a kind in the filter popup now flips every file chip of that kind in the strip below the search bar, and vice-versa (the last muted file of a kind also unticks the kind). The two filter surfaces no longer drift.
- **Library — Grid view indent under family / sub-family headers** : card wraps inherit the same left padding as their heading label, with extra vertical spacing between families so blocks read as distinct sections instead of a single grid.
- **Scope chip vertical alignment** : in the Library and Hardcoded Values toolbars, the `Scope: [Name] (?)` chip is now vertically centred against the action-toolbar buttons on its left.

### Fixed
- **Light-theme adaptation in the Library** : sub-family header foreground was previously inverted (`JBColor(light, dark)` was set as `JBColor(pale, dark)` — illegible in both themes); category / family / sub-family palette rebuilt with proper light-vs-dark contrast steps. The resolved-value text, scope label and filter-popup section headers now use the platform's adaptive muted foreground instead of the fixed `JBColor.GRAY` (which is `Gray._128` and never adapts).
- **Light-theme dark bands behind Library category headers** : `gridContainer`, the sticky header panel and the grid scroll-pane viewport now follow `list.background`, so the section separators no longer paint a dark band over a cream container on light themes where `JPanel.background` and `List.background` diverge.

## [0.1.6] — 2026-05-18

### Added
- **Multi-term library search** : the Library search field now tokenises the query on spaces, dashes and underscores. Each term must match the token name or value (AND), order-insensitive — typing `informative content` finds `--token-informative-highlight-content-hover`.
- **Stale-analysis banner** : after running the Analyser, the panel watches every file referenced by the report (broken refs + token-source files). When any of them changes on disk, a yellow banner appears inviting a re-run, so fixing a broken reference no longer leaves the list stale.
- **Scope reordering** : up/down arrows in Settings → Token Flow → Scopes let you reorder the list. The order is reflected immediately in the Analyser scope picker.
- **Analysis-exclude paths per scope** : new *Excludes* tab in the scope detail. Folders or files added here are skipped by the Analyser (coverage, broken refs, hardcoded clusters) — useful when a wide scope root contains unrelated sub-modules.
- **Library kind filter** : the existing funnel popup now opens with a *Token kind* section above *Families*. Toggle CSS / SCSS / JS-JSON to slice mixed-stack catalogs without unchecking files one by one. The funnel button switches to its active state whenever any kind/family is excluded.
- **Context-aware token drop & paste** : dragging or pasting a JS_OBJECT_PATH token (`'{path}'`) into a backtick template literal in TS/JS now inserts `${dt('path')}` (or `dt('path')` when the caret is already inside `${…}`). Driven by a new `CopyPastePostProcessor` that recognises a custom transferable shipped by the Library.

### Changed
- **Scope detail layout** : the three lists (Sources / Whitelist / Excludes) are now grouped under a tabbed pane instead of stacked sections, removing the vertical scroll in the Settings dialog.
- **Analyser toolbar** : redundant *Refresh* and *Re-sync scopes* buttons removed. A single *Run analysis* action flips to *Re-run analysis* once a report exists; the new banner covers the auto-detect case the legacy buttons tried to address.
- **Accordion headers** : in the Analyser, the chevron and title stay left while the item count and the contextual `(?)` icon are pushed to the right edge — easier to scan.
- **Broken-reference row** : token name on line 1 (red, bold), `filename:line` on line 2 in muted grey — consistent with other Analyser rows.
- **Token copy/drag transferable** : every Library copy or drag of a JS object-path token now ships an extra clipboard flavor so paste-side rewrapping kicks in across copy/paste, drag-and-drop, the context menu, and the card's copy button.

### Fixed
- **Alt+T preserves the `dt(` wrapper** : selecting an alternative on a `${dt('token.x.y')}` reference no longer rewrites the call into a bare Style-Dictionary alias (`'{…}'`) — the `dt(…)` shell is detected from the hit text and kept.
- **VFS subscription bug** : the stale-analysis banner now actually fires. The previous wiring subscribed `VirtualFileManager.VFS_CHANGES` on the project message bus, but it is an application-level topic and never delivered events.
- **Scope combo reorder reflection** : the Analyser scope picker is rebuilt with an atomic `ComboBoxModel` swap rather than a series of `removeAllItems` / `addItem` calls, so reordering scopes in settings always reflects in the dropdown.


## [0.1.5] — 2026-05-17

### Added
- **Broken Reference Detection** : the Analyser now lists references that don't resolve to any known token (`var(--foo)`, `'{token.x.y}'`, `dt('x.y')`). Surfaced in both the dedicated *Broken references* accordion and inline in the Hardcoded Values panel (yellow warning icon, dedicated row state).
- **Reference Integrity score** : new 5th axis in the global note (weight 20 %), heavily penalising broken references. Existing weights rebalanced (Semantic 30→20, Usage 30→25, Duplication 20→15, Hardcoded 20).
- **Smart Suggestions with fallbacks** : when a `var(--token, #fff)` reference is broken, the suggestion engine treats the `#fff` fallback as a value hint and proposes matching colour tokens first. Name-similarity (Levenshtein) suggestions surface even without a fallback.
- **Ignored Library Files** : Settings → Token Flow now accepts a list of paths to exclude (e.g. vendored design-system packages). Symbols declared inside are dropped from the analyser to keep the false-positive count down.
- **CSS Named Colors detection** : scanner now identifies standard CSS named colors as hardcoded values.
- **Scope display in Hardcoded Values** : active scope is shown in the panel toolbar.
- **Dynamic list expansion** : long Analyser sections now have a clickable `+x more` link.

### Fixed
- **alt+T on JS token references** : `'{token.modeLight.x.y}'` and `dt('token.x.y')` no longer fall through to "No alternatives found". `TokenNameParser.resolveReference` tolerates a leading export-binding segment (`token.`), a mode segment (`modeLight` / `modeDark`), and a single camelCase merge / split per path (so `default.high` matches `defaultHigh` and vice versa). Completion, goto and the broken-reference check use the same resolver.
- **CSS `var()` inside TS template literals** : `var(--name)` written inside a backtick string in `.ts/.tsx` is now recognised by alt+T. The detector used to mistake `var(...)` for a JS helper call; CSS functional notations (`var`, `calc`, `rgb*`, `hsl*`, gradients, transforms…) are now excluded from helper-call detection.
- **Hardcoded suggestions stay in-category** : `padding: 0 6px` used to suggest `--shadow-md-blur: 6px` because the value index ignored category. The index is now keyed by `(category, normalised value)`, with controlled fallback inside the length-bearing family (SPACING, RADIUS, SIZING, TYPOGRAPHY, BORDER). No more bleed into SHADOW, EFFECTS, DURATION, OPACITY or COLOR.
- **Semantic incoherences respect the categorizer** : `--stroke-default: 1px` is no longer flagged. When `TokenCategorizer` has already reconciled a name-vs-value clash (here: name hints COLOR, value is length → BORDER), the incoherence detector trusts that verdict instead of flagging on the literal name reading.
- **Analyser accordion spacing** : collapsing every section no longer inflates the gap between headers. The width-cap wrapper now follows the child's preferred height instead of stretching vertically.
- **Comments ignored in scanner** : values inside code comments are no longer detected.
- **`var(--token, fallback)` fallbacks** : literals inside the fallback slot are no longer reported as separate hardcoded values.
- **Circular token definitions** : literals used inside a token's own declaration are no longer flagged.
- **Levenshtein similarity** : fixed an inverted `copyInto` in the suggestion engine that made name-similarity distances stale beyond the first row.

### Changed
- **"Non-existent tokens" → "Broken references"** : matches the underlying `BrokenReference` data class and reads as an action item rather than a category.
- **Deprecated IntelliJ APIs removed** : migrated off `com.intellij.ui.components.labels.ActionLink` (scheduled for removal), the deprecated Kotlin top-level `runReadAction { … }` (11 sites, now routed through a `readAction { }` helper backed by `ReadAction.compute`), and the deprecated `getData(dataId)` override (replaced by `uiDataSnapshot(sink)` with `sink[COPY_PROVIDER] = …`).
- **UI harmonisation** : scope info right-aligned in toolbars across panels.
- **SCSS broken-reference noise** : `$name` references are excluded from the broken-reference report (compiler handles them).


## [0.1.4] — 2026-05-15

### Added
- **Design System Dashboard - Grid View (Cards)** :
  - New visual grid mode for tokens, offering a much more visual and modern alternative to the list view.
  - **Token Cards** : Each token is represented by a card with visual previews (color circles, radius boxes, spacing indicators), action buttons (copy, info), and modern rounded aesthetics.
  - **Persistence** : Selected view mode (List/Grid) is saved per project.
- **New Token Categories** : Added more precise categorization for better organization:
  - **`EFFECTS`**, **`LAYOUT`**, **`SIZING`**, **`BORDER`**, **`OPACITY`**, et **`ICON`**.
  - **Enriched existing categories** : `COLOR`, `TYPOGRAPHY`, `SHADOW`, `DURATION`, and `SPACING` now recognize a much wider range of semantic keywords (e.g., surface, gradient, kerning, viewport, etc.).
  - **Strict evaluation order** : Prevents collisions by ensuring composite keywords (like `border-color` or `box-shadow`) are correctly evaluated before their root words.
- **Active Scope Awareness** : Both the Dashboard Library and the Analyser tab now visibly display the current active scope, automatically adapting as you switch between different files.
- **Contextual Help** : Added an information `(i)` button near the scope indicators, explaining how scopes work and linking directly to the plugin settings.
- **UI Polish** : The Analyser tab now features perfectly centered empty-states and a native IntelliJ loading animation during analysis.

### Fixed
- **Incorrect token categorization for Z-INDEX** :
  - Tokens are no longer classified as `Z-INDEX` solely based on having a small integer value (e.g. `$breakpoint-phone: 0` or `--grid-columns: 4`).
  - The categorization logic now prioritizes naming conventions, correctly identifying tokens containing `z-index`, `layer`, `depth`, or `elevation` as Z-INDEX, while letting other integer values fall through to their correct context or `OTHER`.
- **Copying Tokens** : `Cmd + C` (or `Ctrl + C`) and the "Copy token" context menu in the Library list view now correctly copy the token's reference expression (e.g. `var(--token)`) instead of internal data.
- **Scope Visibility Bug** : Fixed an issue where the Library would appear empty when editing a file that is included via a scope's `sourcePaths`.

## [0.1.3] — 2026-05-15

### Added
- **Gutter color swatches for SCSS variables** :
  - Implementation of `TokenColorProvider` (IntelliJ `ElementColorProvider`) allowing SCSS variables (`$color-name`) to display a color preview icon in the editor gutter, consistent with native CSS custom properties.
  - Integration with the project's real-time `TokenIndex` for accurate visual representation of design tokens.

### Fixed
- **Handling of SCSS/CSS modifiers (`!default`, `!global`, `!important`)** :
  - `TokenScanner` now automatically strips these flags from extracted values, preventing them from breaking color parsing and alias resolution. [#2](https://github.com/robinlopez/token-flow/issues/2)
  - Alias resolution now works correctly for variables using these modifiers (e.g. `$app-highlight: $app-primary !default;`). [#2](https://github.com/robinlopez/token-flow/issues/2)
  - Color previews now correctly render for declarations using these flags (e.g. `$app-body-fontColor: #333333 !default;`). [#2](https://github.com/robinlopez/token-flow/issues/2)
- **Ignorer les déclarations de variables dans l'analyse** :
  - Par défaut, les valeurs littérales affectées à une variable (ex: `$color: #fff`) ne sont plus marquées comme "hardcoded". Cela évite de flagger la définition même de vos tokens.
  - Ajout d'un nouvel onglet **Analyser** dans les réglages pour permettre de forcer la détection si besoin.

## [0.1.2] — 2026-05-13

### Added
- **Support React Native / CSS-in-JS runtime themes** :
  - Nouveau `TokenKind.JS_RUNTIME_PROPERTY` pour les jetons accédés par propriété (`colors.PRIMARY_500`, `radius.sm`, `theme.fontPresets.h1.fontSize`).
  - Détection des `const X = { … }` (bag, garde le préfixe `X.`) et des `export const X: Type = { … }` (agrégateur typé, strippe le préfixe → chemins type `colors.PRIMARY_500` / `fontPresets.h1.fontSize` alignés sur les re-exports barrel).
  - Résolution des alias bare-property (`PRIMARY_500: colors.PRIMARY_500`) en plus des alias Style-Dictionary `{…}`.
  - Dispatch automatique par fichier (`JsTokenFileParserRegistry`) : présence d'aliases `'{a.b}'` → Style-Dictionary ; sinon imports `react-native` / `StyleSheet.create` / typed export → Runtime ; fallback historique pour les presets sans alias.
- **Helpers callables (`spacing`, `radius`, …)** — nouveau `TokenKind.JS_RUNTIME_FUNCTION` :
  - `RuntimeFunctionParser` détecte les arrow functions linéaires `(p[: T]) => UNIT * p` (et variantes `Math.floor(unit * Math.abs(p))`, `UNIT * Math.abs(p)`, p ↔ unit swappé). `UNIT` peut être un littéral numérique ou une `const NAME = NUMBER` déclarée plus haut.
  - Suggestions inversées : un `12px` hardcodé + un helper `spacing(unit=8)` propose `spacing(1.5)` (snap au quart de pas, tolérance 0.05).
  - **Alt+T sur `spacing(0.5)`** ouvre une popup de scale (`spacing(0.25)`, `spacing(0.5)`, `spacing(1)`, …) avec pré-sélection de la valeur courante.
  - Badge **ƒ** dans `TokenCellRenderer` pour distinguer les helpers callables des jetons à valeur fixe.
- **Détection des littéraux numériques sans unité** : nouveau `LiteralFinder.Kind.NUMBER` qui matche `IDENT: NUMBER` (ex : `fontSize: 34`, `lineHeight: 24`, `opacity: 0.5`). Filtré aux fichiers JS/TS dans l'inspection et le panel Hardcoded values (CSS shorthand `border: 1 solid red` aurait sinon généré du bruit). Numéros à l'intérieur d'un `(…)` (arguments de helper) ignorés.
- **Re-sync action** dans la toolbar de l'onglet Analyser (icône `ForceRefresh`) : drop hard du cache `TokenIndex` + reconstruction du combo de scope.
- **`TokenSelectorSettings.fireScopesChanged()`** + `addScopesChangeListener()` : nouvelle API pubsub permettant aux panneaux live de réagir aux modifications de scope sans restart IDE. `AnalyzePanel` s'y abonne pour reconstruire son combo et invalider l'analyse précédente.
- **`TokenLocator` reconnaît les expressions runtime** :
  - Property-access chains `colors.PRIMARY_500`, `theme.radius.sm` (requiert au moins un `.`, rejette les contextes `$`/`-`).
  - Helper calls `spacing(0.5)` (capture l'expression complète, rejette les `obj.method(…)`).
- **Complétion par préfixe runtime** : trigger `colors.` / `theme.radius.` dans les `.ts/.tsx` propose les jetons `JS_RUNTIME_PROPERTY` correspondants.

### Changed
- **Refactor `scanner/parsers/`** : `JsObjectTokenParser` expose désormais `parseAt(text, openBrace, initialPath)` réutilisable. Les stratégies `StyleDictionaryParser`, `RuntimeObjectParser`, `RuntimeFunctionParser` cohabitent derrière `JsTokenFileParserRegistry`. Ajouter un nouveau stack = une seule classe à enregistrer.
- **Centralisation du formatage de référence** : nouveau `TokenReference.expression(token)` — toutes les insertions (`var(--name)`, `$name`, `'{path}'`, `colors.X`, `spacing(N)`) passent par ce helper. Ajouter un futur `TokenKind` n'est plus qu'une seule branche.
- **Popup de complétion par valeur** : `setRequestFocus(false)` + `setCancelOnClickOutside(true)` → l'éditeur garde le focus, les frappes continuent dans le code, la popup agit comme un hint cliquable. Plus de `4` qui termine dans le champ de recherche au lieu du fichier.
- **Analyser scope-aware** : `DesignSystemAnalyzer.computeCoverage` accepte `scopeFile` et restreint le file walk aux `rootPath` des scopes actifs (résolus via `ScopeResolver.activeScopesFor`). Les `sourcePaths` exclus sont aussi limités aux scopes actifs (au lieu de l'union globale qui masquait des hits valides).
- **Annotations de type acceptées par le scanner** : `export const X: Theme = { … }` est désormais reconnu (la regex historique stoppait sur l'annotation).

### Fixed
- **Quick-fixes JS/TS** insèrent désormais la syntaxe correcte selon le `TokenKind` (`'{path}'` pour Style-Dictionary, `colors.X` pour property-access, `spacing(1.5)` pour helper call). Avant, l'ajout d'un kind nécessitait de toucher 5 sites distincts ; centralisé via `TokenReference`.
- **Cache `TokenIndex` invalidé après changement de scope** : le listener `fireScopesChanged` est déclenché *après* `TokenIndex.invalidate()`, donc les panels live re-fetch sur un état frais.

## [0.1.1] — 2026-05-08

### Fixed
- **Hover popup — variants groupées par thème** : un token déclaré dans un map SCSS imbriqué (`$themes-config -> "themeOne" -> "light" -> …`) affichait ses variantes en colonnes plates `default | dark | light | dark | light | dark` sans correspondance thème ↔ mode. Le scanner remonte désormais la chaîne de contexte du token primary (`DeclarationContext.describeAt` étendu à `CSS_CUSTOM_PROPERTY` et `SCSS_VARIABLE`, plus seulement `JS_OBJECT_PATH`), et `parseCondition` retombe sur `"default"` au lieu de fuiter `:root`/`@media` quand la chaîne ne contient que du structurel. Résultat : header à 2 lignes `themeOne | themeTwo | themeThree` × `light | dark` pour 3 thèmes × 2 modes.
- **Settings — onglet Scopes scroll horizontal** : les paragraphes d'intro (HTML wrappés via `JEditorPane`) annonçaient une `preferredSize.width` égale à la longueur du texte non-wrappé, ce qui forçait le `JScrollPane` du dialog Settings à afficher une scrollbar horizontale. Override de `getPreferredSize()` qui force un layout à la largeur du parent (calcul de la hauteur wrappée correcte) puis claim `width=0` → le parent peut shrinker, BorderLayout.NORTH grant la pleine largeur au render → wrapping HTML honoré.

### Changed
- **Marketplace metadata** : vendor email mis à jour (`robinlopez.contact@gmail.com`), description CDATA simplifiée (suppression de la version FR doublonnée et de la cover image inline — déjà visible dans le README et sur la page Marketplace).

## [0.1.0-internal-iterations]

> Note : entrées listées initialement dans `[Unreleased]` lors du développement avant la première release publique. Conservées pour traçabilité.

### Added
- **Support TS/JS preset files** (PrimeUIX, Style Dictionary, Material 3 themes…) :
  - Nouvelles extensions scannées : `.ts/.tsx/.js/.jsx/.mjs/.cjs`
  - `JsObjectTokenParser` parse les `export const X = { … }` / `export default { … }` et émet un token par feuille avec son chemin pointé (`global.modeLight.high.surface.default`)
  - Résolution des alias Style-Dictionary `{path.to.other.token}`
  - Nouveau `TokenKind.JS_OBJECT_PATH`. Insertion par défaut → `'{path.to.token}'`
  - **Autocomplétion** : déclenchement sur `'{prefix...` ou `dt('prefix...` dans les fichiers TS/JS. Filtrage progressif par segments du path
  - **Inspection** des hardcoded values fonctionne aussi sur TS/JS (les colors littérales `#fe5716` sont flagées si un token a la même valeur)

### Fixed
- **Library : tooltip variants** ne s'affichait pas (les tooltips de sous-composants d'un cell renderer ne fire pas dans une JList). Maintenant override de `JBList.getToolTipText(MouseEvent)` qui calcule le tooltip directement à partir du token sous la souris.
- **Hardcoded : combo coupé en hauteur** : row passe à 38px, combo à 26px (les descendants du font ne sont plus rognés)
- **Hardcoded : colonne literal alignée** : largeur fixée à 140px (avec `minimumSize`), plus de flex → la flèche centrale est à la même x-coordinate sur toutes les rows. Le flex restant va à la colonne suggestion.

### Added
- **Variants par token** : un même `--width` déclaré sous plusieurs `@media` / classes thème est désormais conservé. Le scanner garde la **première** déclaration comme primaire et stocke les autres dans `variants` avec leur **contexte** (chaîne de sélecteurs `@media (min-width: 1024px) :root`, `.dark-mode`, etc.). Library : badge `+N` à droite du nom + tooltip listant chaque variant avec sa condition.
- **Tooltip CSS property** sur la pastille de catégorie dans le Hardcoded panel : `Used as: font-size`, `Used as: padding-left`. Aide à comprendre la valeur en un coup d'œil.
- **Sections du Hardcoded panel collapsibles** : clic sur le header de sélecteur → chevron `▶/▼`, ligne du compteur `· N`. État conservé pendant la session.

### Changed
- **Skip des valeurs fallback** : `var(--token, #307a10)` → le `#307a10` n'est plus signalé comme hardcoded. Détection via regex `var\(--name, FALLBACK\)`, range exclu de `LiteralFinder`.
- **"exact" / "≈3%" baked dans le swatch** : le label séparé qui se faisait couper en largeur réduite est supprimé. Maintenant :
  - Match exact = swatch propre (sans glyphe pour les couleurs, glyphe catégorie pour les autres) + tooltip `Exact match — VALUE`
  - Match approximatif = swatch avec glyphe `≈` superposé + tooltip `Approximate match (≈3% off) — VALUE`
- **Ligne Hardcoded plus aérée** : flèche centrée, combo réduit à 220px avec font -1pt, alignement GridBag plus prévisible.

### Added
- **Alt+T sur une valeur hardcoded** : l'action `Show Token Alternatives` dispatch maintenant aussi sur les littéraux. Place le caret sur `12px` ou `#fff` → popup des tokens correspondants triés par contexte CSS. Si aucun match, message clair "No matching design token for X".

### Changed
- **Pastilles couleur 100% rondes** : nouveau composant partagé `RoundSwatch` avec min/pref/max size verrouillés à un carré + paint qui prend `min(width, height)` centré → cercle parfait identique partout (Library, Hardcoded values, popups).
- **HardcodedValuesPanel rebuild** :
  - Layout `GridBagLayout` à colonnes alignées : `[✓] [glyph cat] [●] [literal flex] → [●] [suggestion flex] [delta 50px] [⌖] [↩]`
  - **Glyph de catégorie** par row (↔ spacing, ◖ radius, T typo, ⏱ duration, ▣ shadow…) basé sur la propriété CSS détectée
  - Bouton "apply" : icône `AllIcons.Diff.ApplyNotConflicts` (plus explicite que MenuPaste)
  - **Pas de scroll horizontal** : `HORIZONTAL_SCROLLBAR_NEVER` + container override `getPreferredSize` pour matcher la viewport width → la colonne `apply` est toujours visible
  - Combobox uniquement si >1 candidat (sinon JLabel flat)

### Changed
- **HardcodedValuesPanel rewrite** — table remplacée par une liste de row-components plus riches :
  - **Checkbox par row** + bouton toolbar "Replace N selected" qui se met à jour avec le compteur
  - **Bouton Replace inline** par row (icône paste à droite) → action immédiate sans toolbar
  - **Bouton Locate inline** par row (icône target) → place le caret + sélectionne le littéral + scroll-to-center
  - **Headers de groupe par sélecteur CSS** (`.button:hover`, `@media (...)`, etc.) — détection via `SelectorContext` qui walk back jusqu'au `{` non-fermé
  - Combobox uniquement quand il y a >1 candidate ; sinon affichage flat avec swatch + nom + delta
- **Swatches couleurs ronds** partout : `TokenCellRenderer.SwatchIcon` et `RoundColor` du HardcodedValuesPanel paint en `fillOval` au lieu de `fillRect`. Plus moderne.

### Added
- `inspection/SelectorContext` : helper qui retourne le sélecteur CSS englobant à un offset donné. Utilisé pour grouper les rows.

### Added
- **Onglet "Hardcoded values" dans le Tool Window** : nouveau Content à côté de "Library". Affiche tous les littéraux détectés dans le fichier actif, avec une table 2 colonnes :
  - Colonne 1 : la valeur en dur + swatch (couleurs)
  - Colonne 2 : token suggéré (combobox éditable si plusieurs candidats) + indicateur `exact` ou `≈3%` pour les couleurs proches
  - Sélection multiple (Ctrl/Cmd-clic, Shift-clic) + boutons toolbar **Replace Selected** / **Replace All**
  - Tous les remplacements sont batchés dans un seul `WriteCommandAction` (un seul Undo)
  - Refresh auto sur changement d'éditeur via `FileEditorManagerListener`
  - États vides explicites : "ouvrez un fichier", "pas de hardcoded values ✓", ou "aucun token visible — configurez un scope Common"
- **Tabs dans la page Settings** (`JBTabbedPane`) : section **Scopes** isolée de **Triggers** (Alternatives popup, Code completion, Keyboard).
- **Conversion d'unités px ↔ rem ↔ em** dans `TokenValueIndex` (base 16px) : un token `--font-size-sm: 0.75rem` matche désormais le littéral `12px` (et inversement). Résout le cas `font-size: utils.rem-calc(12px)` où le token cible était stocké en rem.

### Refactor
- `inspection/SuggestionEngine` : extraction de la logique "trouver les meilleurs tokens pour un littéral" depuis `HardcodedValueInspection`. Réutilisée par `HardcodedValuesPanel`.

### Added
- **Phase 4 — Inspection des valeurs hardcodées** :
  - `LocalInspectionTool` qui détecte les hex, `rgb()`/`hsl()`, longueurs (`px`/`rem`/`em`/`%`/`vh`/`vw`/...) et durées (`ms`/`s`) directement dans les `.scss/.sass/.css`
  - **Match exact** : warning faible + quick-fixes "Replace with --token-X" pour chaque token de même valeur
  - **Match approximatif** (couleurs uniquement) : si aucun token n'a la valeur exacte mais qu'une couleur est à <5% de distance RGBA, propose le plus proche avec affichage du delta (ex. "Closest design token to #ababab: --global-low-stroke-default (≈3% off)")
  - **Suggestion contextuelle** : la propriété CSS courante (`font-size`, `gap`, `border-radius`…) priorise les tokens de la catégorie attendue. Pour `font-size: 12px`, le `--font-size-sm` arrive avant un éventuel `--units-sm` qui aurait aussi la valeur 12px.
  - **Skip auto des fichiers source-of-truth** : les fichiers déclarés dans les scopes ne s'auto-flagguent pas
  - Whitelist des valeurs neutres : `0`, `0px`, `0%`, `100%`, `0s`, `0ms`
  - L'inspection peut se désactiver via `Settings → Editor → Inspections → Token Flow → Hardcoded value matches a design token`
- **Toggle autocomplétion** dans Settings → Tools → Token Flow → "Suggest design tokens in code completion". Coché par défaut. Décoché → le `CompletionContributor` retourne sans rien proposer (les autres complétions IDE restent intactes).
- `inspection/PropertyContext` : helper partagé entre completion et inspection pour mapper une propriété CSS → catégorie de token attendue. DRY.

### Added
- **Autocomplétion des design tokens** dans les `.scss/.sass/.css` :
  - `var(--…` propose les CSS custom properties dont le nom matche, filtrage progressif au fil de la saisie
  - `$…` (SCSS/Sass uniquement) propose les variables SCSS
  - **Smart category** : si la propriété CSS est `color`/`background-color`/`fill`/`stroke`/etc., les tokens COLOR remontent en haut. Idem pour `padding`/`margin` → SPACING, `border-radius` → RADIUS, `box-shadow` → SHADOW, `font-*`/`line-height` → TYPOGRAPHY, `transition`/`animation`/`*-delay` → DURATION, `z-index` → Z_INDEX.
  - **Smart same-block** : si une autre `var(--token-informative-…)` est déjà utilisée plus haut dans le même bloc `{ … }`, les tokens de la famille `informative` sont aussi boostés. Détection par scan inverse jusqu'au `{` ouvrant.
  - Aperçu couleur : icône swatch dans la liste de complétion pour les tokens de catégorie COLOR
  - Le contributor s'enregistre sur `language="any"` et filtre par extension de fichier — fonctionne en Community comme Ultimate, sans dépendance au plugin CSS
- **Icône "locate" au hover des tokens dans le Dashboard** : passe la souris sur un token, l'icône target apparaît à droite, clic dessus → ouvre le fichier source à la ligne de définition. Curseur main lors du survol.
- **Padding bottom sur la barre de chips** : 8px de marge basse pour que la scrollbar horizontale ne mange plus les chips.

### Changed
- **Cellules de la liste auto-fit la largeur du panneau** : `JBList.getScrollableTracksViewportWidth() = true` + suppression de la largeur fixe des renderers. Plus de scroll horizontal pour voir les valeurs ; les noms longs sont clippés par Swing.

### Added
- **Système de scopes** — un projet peut désormais contenir plusieurs UIs (mobile/desktop/etc.) avec des design systems distincts.
  - Settings → Tools → Token Flow : éditeur master-detail (liste de scopes à gauche, détails à droite)
  - Chaque scope a un `name`, un `rootPath` (relatif au projet) et sa propre liste de `sourcePaths`
  - `rootPath` vide = scope **commun** (toujours actif). Les scopes spécifiques s'activent quand le fichier édité est dans leur racine
  - Sur collision de noms (`--btn-bg` défini dans `Mobile` et dans `Common`), le scope spécifique gagne ("specific shadows common")
  - Migration auto des anciens settings : la liste de chemins legacy devient un scope `Common` au premier load
  - `TokenIndex` cache **par scope** : éditer un fichier mobile ne re-scanne pas les sources desktop
- **Sections collapsibles dans le Dashboard** : clic sur l'en-tête de catégorie (chevron ▶/▼) replie/déplie la section. Boutons `Expand all` / `Collapse all` dans la toolbar. État conservé pendant la session.
- **FilterChip** : chips de famille redessinées en pilules arrondies avec état actif visible (fond accent + texte blanc), hover effect, taille compacte. Plus aucune confusion entre actif/inactif.

### Changed
- **Toolbar Dashboard** : icône "Clear filters" remplacée par `AllIcons.Actions.Cancel` (X) + auto-désactivée quand aucun filtre n'est actif. Maintenant elle clear AUSSI le champ de recherche.
- En-têtes de catégorie : affichent le compteur (ex. `COLOR · 47`) à côté du titre.

### Fixed
- **Hover popup mal positionnée** : la popup s'ouvrait sur la position du caret au lieu de la position de la souris. La position écran de la souris est désormais capturée dans `mouseMoved` et passée à `TokenAlternativesShower.show(..., anchorScreenLocation)`. Visuellement la popup apparaît juste à côté du token survolé.
- **Hover non actif sur éditeurs déjà ouverts** : le `ProjectActivity` s'enregistrait via `EditorFactoryListener` mais n'attachait le listener qu'aux **futurs** éditeurs. Désormais on parcourt aussi `EditorFactory.allEditors` au démarrage pour wire-up les éditeurs préexistants.
- **Lien Keymap qui ne s'ouvrait pas** : `ActionLink` avec lambda Kotlin ne SAM-convertit pas toujours correctement vers `ActionListener`. Remplacé par `HyperlinkLabel` + `addHyperlinkListener`. Plusieurs IDs de Configurable sont essayés en cascade pour ouvrir directement la page Keymap (variations entre versions IDEA), avec fallback sur l'ouverture par display name.

### Added
- **Dashboard : filtres par famille détectée** — chips toggle au-dessus de la liste, calculés automatiquement depuis les noms des tokens (ex : `global`, `actions`, `form`, `informative`, `navigation`, `units`, `shadow`, `radius`, `units`…). Convention : segment[1] si segment[0] = `token`, sinon segment[0]. Sélectionner plusieurs chips = OR. Bouton toolbar "Clear Family Filters" pour reset.
- **Drag-and-drop des tokens** depuis la Dashboard vers l'éditeur. Drag un token sur un fichier ouvert insère `var(--name)` ou `$name` à la position du drop (gestion native par l'éditeur).
- **Menu contextuel sur la Dashboard** (clic droit) : `Insert at caret`, `Open source file` (navigue jusqu'à la déclaration), `Copy token name`.

### Added
- **Phase 3 — Design System Dashboard (Tool Window)** :
  - Nouvelle fenêtre latérale "Design Tokens" (icône palette, ancrage droit par défaut)
  - Liste de tous les tokens du projet, groupés par catégorie (Color, Spacing, Typography, Shadow, Radius, Duration, Z-index, Other)
  - Champ de recherche filtrant nom et valeur (live)
  - **Double-clic** sur un token : insertion à la position du caret de l'éditeur actif (`var(--name)` pour CSS custom property, `$name` pour SCSS variable)
  - Bouton Refresh dans la toolbar
- **Cache d'indexation** (`TokenIndex` service) :
  - Premier scan = à la première demande, puis cache mémoire
  - Invalidation automatique sur changement VFS (`.scss/.sass/.css`)
  - Invalidation manuelle sur Apply des settings
  - Le `Alt+T` est maintenant instantané au-delà du premier appel
- **Hover trigger optionnel** :
  - Toggle dans Settings → Tools → Token Flow → "Open the alternatives popup automatically on hover"
  - Délai configurable (100–5000 ms, défaut 700)
  - Implémenté via `EditorMouseMotionListener` + `Alarm`, attaché par éditeur ouvert (cleanup propre à la fermeture)
- **Lien direct vers Keymap** dans la page de settings ("Customize keyboard shortcut…") qui ouvre `Settings → Keymap` pour rebinder `Alt+T`.

### Changed
- Logique d'affichage de la popup d'alternatives extraite dans `TokenAlternativesShower` (réutilisable depuis l'action ou le hover). L'action elle-même devient minimale.
- L'action `ShowAllTokens` et `ShowAlternatives` passent par `TokenIndex.get()` au lieu de `TokenScanner.scan()` direct.

### Added
- **Séparateurs visuels dans la popup** : les groupes de tokens (mêmes segments structurels sauf l'état) sont séparés par un en-tête. Pour `--token-actions-low-stroke-hover` la liste se présente comme :
  ```
  STROKE
    --token-actions-low-stroke-default
    --token-actions-low-stroke-hover (pivot)
    ...
  CONTENT
    --token-actions-low-content-default
    ...
  HIGH › STROKE
    --token-actions-high-stroke-default
    ...
  ```
  Le label affiche uniquement les segments qui divergent du pivot, joints par `›`. Les séparateurs ne sont pas sélectionnables (le callback les ignore).

### Fixed
- **Compatibilité IntelliJ IDEA Ultimate** : `untilBuild` retiré de `plugin.xml` → le plugin charge sur toutes les versions ≥ 2024.2 (Community + Ultimate, y compris 2025.x et au-delà). Auparavant restreint à 2025.2.*.

### Added
- **Settings page (par projet)** — `Settings → Tools → Token Flow` :
  - Liste éditable de fichiers/dossiers servant de "source de vérité" pour les tokens
  - Chemins stockés en relatif au projet dans `.idea/designTokenSelector.xml` (portable)
  - Fallback automatique : si la liste est vide, scan complet `.scss/.sass/.css` du projet (comportement actuel inchangé)
- **Détection des SCSS map keys** — pattern `"<name>": <value>,` reconnu dans les fichiers `.scss/.sass`. Permet de pointer directement la map source de vérité (ex : `_tokens-semantics.scss` Style Dictionary). Les noms sont promus en `--name` pour matcher la façon dont ils seront utilisés dans le code (`var(--name)`).

### Fixed
- **Tri par ordre source** : dans le tier "préfixe partiel commun" (ex. `token-actions-low-*` quand le pivot est `*-stroke-hover`), `content` et `surface` se retrouvaient mixés à cause du tri secondaire par état. Le tri secondaire passe à **chemin de fichier puis offset** dans la source, ce qui respecte l'ordre voulu par le design system (content puis surface puis stroke, chacun en états ordonnés naturellement).
- **Dédup des tokens** : un même `--token` redéclaré dans plusieurs sélecteurs (`:root`, `.dark-mode`, classes de thème…) du CSS compilé apparaissait N fois dans la popup. Le scanner ne garde plus que la première occurrence par nom (typiquement la valeur du thème par défaut/light).
- **TokenLocator** : la détection consommait les `--` à gauche du caret (les tirets sont des chars d'identifiant en CSS/SCSS). Réécriture qui étend une plage d'ident autour du caret puis vérifie le prefix `--` ou `$`.
- **CandidateSorter** : tri par proximité visuelle (HSL) mélangeait les états des familles de tokens. Nouveau tri **structurel** basé sur les segments du nom (`[domaine]-[famille]-[niveau]-[propriété]-[état]`) :
  1. nombre de segments communs avec le pivot (priorité décroissante)
  2. ordre canonique des états (`default` → `hover` → `focused` → `pressed` → `active` → `checked` → `disabled`)
  3. distance HSL en tiebreaker (couleurs uniquement)
  4. valeur numérique (longueurs/durées sans pivot)
  5. ordre alphabétique
- **TokenCellRenderer** : largeur de cellule fixée (560px) pour éviter que la popup prenne toute la largeur de l'éditeur.
- **Popup** : `setMinSize(580, 380)` + `dimensionServiceKey` pour mémoriser la taille préférée entre sessions.

### Added
- `TokenStructure` / `TokenNameParser` : parsing en segments + heuristiques d'ordre (states canonical, common-prefix scoring).

### Added
- **Phase 2 — Contextual Token Picker** :
  - `TokenLocator` : détection du token (`$var` ou `--var`) sous le caret depuis le `Document`
  - `ColorParser` : parsing hex (3/4/6/8), `rgb()`, `rgba()`, `hsl()`, `hsla()` + 11 named colors
  - `TokenCellRenderer` : rendu JList avec swatch couleur ou glyph par catégorie
  - `CandidateSorter` : tri intelligent (couleurs par proximité HSL au pivot, longueurs/durées par valeur numérique, autre par nom)
  - `ShowTokenAlternativesAction` : popup `JBPopupChooserBuilder` avec filtrage texte, pivot pré-sélectionné, remplacement via `WriteCommandAction`
  - Raccourci clavier par défaut : `Alt+T`
  - Action ajoutée au menu contextuel de l'éditeur
- `buildSearchableOptions` désactivé (optionnel, évite le conflit avec une IDEA déjà ouverte)

## [0.1.0] — 2026-05-05

### Added
- Bootstrap projet : Gradle 8.10, IntelliJ Platform Gradle Plugin 2.2.1, Kotlin 1.9.25
- `DesignToken` / `TokenCategory` (8 catégories) / `TokenKind`
- `TokenScanner` : service projet, scan `.scss/.sass/.css` via `FilenameIndex`, résolution des alias avec garde anti-cycle
- `TokenCategorizer` : heuristiques nom puis valeur (regex couleurs, longueurs, durées + mots-clés)
- Action `Tools → Show All Design Tokens` : scan async + dialog récapitulatif groupé par catégorie
- Documentation : README, ROADMAP, CHANGELOG

### Notes techniques
- Pas de dépendance aux plugins CSS/SCSS bundlés → compatible IDEA Community
- Build packageable en ZIP (~24 ko) installable via Plugins → Install from Disk
