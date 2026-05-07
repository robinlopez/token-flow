# Roadmap

Suivi de l'avancement par phase. Chaque phase produit un livrable testable.

Légende : ✅ fait · 🚧 en cours · ⬜ à faire

---

## Phase 0 — Cadrage ✅

- ✅ Choix stack : Kotlin + IntelliJ Platform Gradle Plugin 2.x
- ✅ Cible : IDEA Community 2024.2+
- ✅ Décision : parsing texte (regex) plutôt que PSI pour rester compatible Community

## Phase 1 — Moteur d'indexation ✅

- ✅ `DesignToken` / `TokenCategory` / `TokenKind`
- ✅ `TokenScanner` (service projet) — scan `.scss/.sass/.css` via `FilenameIndex`
- ✅ Extraction `$var: value;` (SCSS) et `--var: value;` (CSS)
- ✅ Résolution récursive des alias avec garde anti-cycle
- ✅ `TokenCategorizer` — heuristiques nom + valeur (couleur, longueur, durée…)
- ✅ Action `Tools → Show All Design Tokens` (debug)
- ✅ Build vert (`buildPlugin` produit un ZIP installable)

## Phase 2 — Contextual Token Picker ✅

Objectif : **transformer un token déjà écrit en visuel actionnable directement dans l'éditeur.**

- ✅ `TokenLocator` — détecte le token sous le caret depuis le `Document`
- ✅ `ColorParser` — parse hex/rgb/hsl pour rendu swatch
- ✅ `TokenCellRenderer` — JList renderer (swatch + nom + valeur résolue)
- ✅ `ShowTokenAlternativesAction` — action + popup
- ✅ Filtrage des candidats : tokens de la même `TokenCategory`
- ✅ Tri intelligent : pour les couleurs, par proximité HSL ; pour les longueurs, par valeur croissante
- ✅ Remplacement en `WriteCommandAction`
- ✅ Raccourci clavier (par défaut `Alt+T`)

**Livrable** : sur un fichier `.scss`, curseur sur `$color-primary-500`, `Alt+T` ouvre une liste verticale de toutes les nuances de la famille avec swatch ; clic remplace.

## Phase 3 — Design System Dashboard ✅

Tool Window (sidebar) servant de bibliothèque visuelle globale.

- ✅ Déclaration `<toolWindow>` dans `plugin.xml` (icône palette, ancrage droit)
- ✅ UI Swing : barre de recherche + liste groupée par catégorie + cellules avec swatch
- ✅ Carte token : swatch + nom + valeur (réutilise `TokenCellRenderer` / `PopupRowRenderer`)
- ✅ Double-clic = insertion à la position du caret de l'éditeur actif (`var(--name)` ou `$name` selon le kind)
- ✅ Refresh manuel (toolbar) + auto sur changement de fichier (via `TokenIndex` + VFS listener)
- ✅ Drag-and-drop vers l'éditeur (`TransferHandler`, insère `var(--name)` ou `$name`)
- ✅ Clic droit → "Open source file" (navigue à la déclaration), Insert, Copy
- ✅ Filtres par famille détectée — repensés en popup groupée (Colors / Metrics / Effects) accessible via bouton rond ; strip principal sous la search bar liste désormais les **fichiers sources** comme chips toggleables
- ✅ Tooltip variantes en **tableau** (colonnes = `default | dark | ≥1024 | …`, swatch + hex pour les couleurs)
- ✅ Bouton filter parfaitement rond + clear-all dans la toolbar
- ⬜ Cellules en mode grille (cards) plutôt qu'en liste pour les couleurs

**Livrable** : tool window utilisable, double-clic insère un token à la position du caret.

## Phase 4 — Inspection valeurs hardcodées ✅

`LocalInspectionTool` qui flague les littéraux matchant un token.

- ✅ Inspection sur `.scss/.sass/.css` (regex sur le texte, pas de PSI requis → marche en Community)
- ✅ Détection valeur littérale qui matche une valeur de token (hex, fonctional colors, lengths, durations)
- ✅ Quick-fix `Replace with --token-X` ou `$token-name` (jusqu'à 5 par occurrence)
- ✅ Suggestions priorisées par contexte (CSS property → catégorie attendue)
- ✅ Approximation pour les couleurs (delta RGBA < 5%)
- ✅ Whitelist des valeurs neutres (`0`, `100%`, etc.)
- ✅ Skip des fichiers source-of-truth (évite l'auto-flag)
- ✅ Onglet **Hardcoded values** dans le Tool Window : table des littéraux du fichier + replace en masse (selection multiple)
- ✅ Conversion d'unités px ↔ rem ↔ em (base 16px) pour faire matcher tokens en rem et littéraux en px
- ✅ Détection contextuelle des function calls (`utils.rem-calc(12px)`, `rem-calc(12px)`) : la quick-fix remplace l'expression entière par `var(--token)` / `$token` au lieu d'imbriquer
- ⬜ Toggle granulaire des kinds (couleur seule / longueur seule / approx désactivable)
- ⬜ Liste configurable des "transparent wrappers" (au-delà de `rem-calc`)

## Phase 5 — Persistance & polish 🚧

- ✅ Settings page (par projet) : liste de fichiers/dossiers source-of-truth, persisté dans `.idea/designTokenSelector.xml`
- ✅ Détection SCSS map keys (`"name": value,`) en plus des variables `$` et `--`
- ✅ Cache d'indexation (`TokenIndex`) avec invalidation VFS + settings, **cache par scope**
- ✅ Toggle hover dans les settings + délai configurable (le hover affiche désormais l'**info popup** — valeur résolue + tableau des variantes — au lieu de la liste d'alternatives, qui reste accessible via `Alt+T`)
- ✅ Lien direct vers Keymap pour customiser **chaque** raccourci (alternatives + go-to-declaration)
- ✅ Mouse shortcut Alt+Shift+Click pour la navigation directe vers la déclaration
- ✅ Settings scopes : édition stable (les modifications ne contaminent plus les autres scopes lors d'un changement de sélection)
- ✅ **Système de scopes** : multi-UIs dans un même projet (mobile/desktop/common) avec `rootPath` + tokens dédiés ; sélection du scope le plus profond quand des roots sont imbriqués
- ✅ Choix de l'icône du tool window dans les settings (Default auto light/dark + variantes Orange/Arc), preview live dans la combo
- ✅ Description marketplace + change-notes formatés (FR + EN)
- ✅ `pluginIcon.png` (marketplace) + icônes tool-window dans `resources/icons/` avec variante `_dark` automatique
- ⬜ Settings additionnels : conventions de nommage, blacklist d'états
- ⬜ Cache d'indexation invalidé sur `VirtualFileListener` (perf : éviter le rescan complet)
- ⬜ Migration `FilenameIndex.getAllFilesByExt` → `FileBasedIndexExtension` custom si besoin de scaler
- ⬜ Screenshots marketplace
- ⬜ Tests `BasePlatformTestCase` : scanner, categorizer, locator
- ⬜ CI GitHub Actions : `gradle buildPlugin` + `verifyPlugin`
- ⬜ Publication JetBrains Marketplace

## Phase 6 — Extension formats 🚧

- ✅ TS/JS preset objects (PrimeUIX, Style-Dictionary aliases) avec résolution des aliases multi-niveaux (mode-stripped + lead-segment-strip + suffix-match)
- ✅ Mode collapsing JS (`token.modeLight.x.y` + `token.modeDark.x.y` → un seul DesignToken canonical avec variantes ; replace + autocomplete préservent le segment de mode original)
- ✅ Support TS/JS dans Alt+T, hover info, completion, go-to-declaration, inspection hardcoded (skip des partial strings type `box-shadow`)
- ⬜ JSON (Style Dictionary, Tokens Studio)
- ⬜ Tailwind config natif
- ⬜ Less / Stylus (si demande)

## Phase 6-bis — Onglet « Analyser » 🚧

Nouvel onglet dans le Tool Window (à côté de *Library* / *Hardcoded values*) qui produit un rapport global sur le scope actif. Pensé comme un **dashboard analytique** : une vue scrollable, des KPI cards en haut, des sections avec graphs/tableaux en dessous, des liens « target » qui renvoient à la source à chaque ligne.

Sections livrées (1ʳᵉ itération) :

- ✅ **Score & robustesse du Design System** — KPI principal (note A→F + /100), jauge circulaire `ScoreGauge` self-centrée même quand stretch, sous-scores par axe en grid 2×2 (Semantic coherence / Usage coverage / Duplication / Hardcoded pressure).
- ✅ **Semantic incoherences** — détection des tokens dont le nom implique une catégorie ≠ valeur (ex : `*-color-*` → `12px`). Liste cliquable → ouvre la déclaration via `OpenFileDescriptor`.
- ✅ **Token-source usage** — section repensée : taux d'utilisation par fichier-catalogue (`X/Y tokens used`) avec barre + bouton target. Plus utile que la coverage par fichier consommateur.
- ✅ **Hardcoded clusters** — agrégation des hits `LiteralFinder` à l'échelle projet, groupés par valeur identique. **Chaque ligne est elle-même collapsible** : header = literal + count, expand → table d'occurrences `filename:line` + bouton target rond. Filtre les clusters dont la valeur match déjà un token (ces cas sont gérés par l'inspection).
- ✅ **Duplicates** — clusters partageant la **signature complète** (primary + variants), exigent au moins 2 fichiers sources distincts pour éviter les faux positifs intra-catalog.
- ✅ **Unused tokens** — tokens déclarés mais jamais référencés ; détection via scan du projet `var(--…)` / `$…` / `'{path}'` (avec strip de mode pour les paths JS).
- ⬜ **Tendances / historique** *(stretch)* — sparkline du score sur les derniers commits (basée sur `git log`).

Exigences UX :

- ✅ UI soignée : cards, séparateurs, sections collapsibles (`CollapsibleSection`), max-width 720px pour éviter le scroll horizontal sur grandes tool windows.
- ✅ Graphs Swing custom (`ScoreGauge` circulaire, `MiniBar` horizontal compact-mode — `Graphics2D` pur).
- ✅ Helpers contextuels : icône `?` par section avec tooltip explicatif.
- ✅ Boutons « target » ronds (`RoundIconButton`) sur chaque ligne actionnable → `OpenFileDescriptor.navigate(true)`.
- ✅ Refresh manuel via toolbar + scope picker (Active editor / All project / Scope: name).
- ✅ Sections collapsibles avec chevron (Hardcoded / Unused / Token-source usage collapsées par défaut).
- ✅ Tous les libellés en anglais (cohérent avec le reste du plugin).
- ⬜ Cache du `AnalysisReport` par signature (tokens + mtimes), évite les re-calculs.

Côté implémentation :

- ✅ `DesignSystemAnalyzer` (service projet) : prend un fichier de scope optionnel, retourne un `AnalysisReport` immutable.
- ✅ Calculs en `Task.Backgroundable`.
- ✅ `AnalyzePanel` : assemble les sections, branchées sur `AnalysisReport`.
- ✅ Modèle `AnalysisReport` : `score`, `grade`, `subScores`, `incoherences`, `coverage` (avec `sources`), `duplicateClusters`, `hardcodedClusters`, `unusedTokens`.

## Phase 7 — Port VSCode ⬜

- ⬜ Extraire la logique de catégorisation en module partagé (TS port)
- ⬜ Hover Provider + Code Actions natifs VSCode
- ⬜ Webview React pour le dashboard

---

## Phase 4-bis — Autocomplétion ✅

- ✅ `CompletionContributor` pour `var(--…` et `$…`
- ✅ Smart category : la propriété CSS courante (color/padding/border-radius/…) priorise la catégorie attendue
- ✅ Smart same-block : les familles déjà utilisées dans le même bloc `{}` sont boostées
- ✅ Swatch couleur dans la liste de complétion pour les tokens COLOR

## Idées en backlog (non priorisées)

- Génération automatique d'une page Storybook à partir de l'index
- Export du design system en JSON Style Dictionary
- Diff visuel entre deux versions du DS (avant/après)
- Heatmap d'usage des tokens (quels tokens sont les plus/moins utilisés)
