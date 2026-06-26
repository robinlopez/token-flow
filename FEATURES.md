# Token Flow — Features & Options

Catalogue vivant des fonctionnalités du plugin. À mettre à jour à chaque évolution.

---

## 1. Indexation des design tokens

| Source | Reconnu | Notes |
|---|---|---|
| `--var: value;` (CSS / SCSS source) | ✅ | Custom properties standard |
| `$var: value;` (SCSS) | ✅ | Variables Sass |
| `"name": value,` (SCSS map keys) | ✅ | Tokens type `name` (Style Dictionary, etc.) |
| TS/JS Style-Dictionary (`export const X = { … }`, `'{a.b}'` aliases) | ✅ | Path en pointillé (`color.primary.500`) |
| **TS/JS Runtime / React Native** (`const X = { … }`, `export const X: Type = { … }`) | ✅ | `colors.PRIMARY_500`, `radius.sm`, `theme.fontPresets.h1.fontSize`. Export typé → préfixe strippé (aligné sur les re-exports barrel) ; bag → préfixe conservé. Switch automatique de parser par fichier |
| **TS/JS Callable helpers** (`const spacing = (v) => UNIT * v`) | ✅ | Arrow functions linéaires 1-param. Badge ƒ dans la library. Suggestions inverses sur les littéraux |
| Aliases récursifs (`$a: $b`, `var(--c)`, `'{a.b}'`, `colors.X`) | ✅ | Garde anti-cycle, alias bare-property runtime résolus |

Catégorisation automatique : `COLOR`, `SPACING`, `TYPOGRAPHY`, `RADIUS`, `SHADOW`, `DURATION`, `OTHER` (basée sur nom + valeur).

## 2. Tool Window « Design Tokens »

- Onglet **Library** : recherche, chips de filtre par famille, regroupement par catégorie collapsable.
- Double-clic → insertion à la position du caret (`var(--name)` / `$name` / `'{path}'`).
- Drag-and-drop éditeur.
- Clic-droit : *Insert at caret* · *Open source file* · *Copy token name*.
- Survol d'un token avec variantes : tooltip **tabulaire**
  - Colonnes = contextes (`default`, `dark`, `≥1024`, `<1024`, …).
  - Cellules : valeur brute pour metrics ; pastille couleur + hex pour COLOR.
- Onglet **Hardcoded values** : audit du fichier ouvert, sélection multiple, *Replace selected* en un clic.
- Onglet **Analyser** : rapport global sur le projet, calculé à la demande (toolbar *Run analysis*).
  - Score global A→F + /100 dans une jauge circulaire, plus 4 sous-scores (cohérence sémantique, couverture, duplication, hardcoded pressure).
  - Sections détaillées : incohérences sémantiques (nom vs valeur), doublons (clusters partageant la même `resolvedValue` + suggestion canonical), hardcoded clusters (littéraux répétés à l'échelle projet, indication du token équivalent), couverture par fichier (top des moins couverts).
  - Boutons « target » → ouvre la source à l'offset exact.
  - Helpers `?` sur chaque section avec explication.

## 3. Hover dans l'éditeur

- Survol d'un `var(--…)` ou `$…` → popup **Token info** (si activé dans les settings) :
  - Nom + swatch (pour COLOR).
  - Tableau de variantes (mêmes colonnes que la library).
- Délai configurable (100–5000 ms).
- N'ouvre **pas** la liste d'alternatives — celle-ci reste accessible via `Alt+T`.

## 4. Copier la valeur d'un token (modifier + clic)

- `⌘/Ctrl + Shift + Clic` sur une référence token (`var(--…)`, `$name`, `'{a.b}'`, `dt('a.b')`, property-access runtime) → **dropdown de copie**.
- Résolution jusqu'à la **primitive** : un alias sémantique copie la vraie valeur (`#e5e9eb`), pas l'alias intermédiaire (chaîne suivie, garde anti-cycle, même pipeline que le hover).
- Lignes proposées :
  - **Valeur résolue** (présélectionnée, swatch couleur, `Entrée` pour copier).
  - **HEX / RGB / HSL / OKLCH** pour les tokens COLOR — le format déjà utilisé par la valeur résolue est omis. OKLCH calculé via la transform sRGB → OKLab (Ottosson). Sortie en `Locale.ROOT` (séparateur décimal toujours `.`).
  - **Nom / référence** tel qu'écrit dans le code.
- Feedback : balloon transitoire `📋 Copied "…"`.
- Implémenté en `EditorMouseListener` programmatique (`CopyValueClickStartup`) qui *consomme* le clic → pas de déplacement de caret ni de multi-curseur parasite.
- Combo configurable (`⌘/Ctrl + Shift`, `Ctrl + Shift`, `Ctrl + Alt`, `Alt + Shift`, `Alt`) ou désactivable dans les Settings. Le défaut `⌘/Ctrl + Shift` mappe le modificateur primaire de la plateforme (⌘ macOS, Ctrl ailleurs).

## 5. Alternatives popup (`Alt+T`)

- Curseur sur un token CSS/SCSS (`$name`, `var(--name)`) → liste des tokens de la même catégorie, triés par proximité (HSL pour COLOR, valeur croissante pour LENGTH).
- Curseur sur un path Style-Dictionary (`'{a.b.c}'`, `dt('a.b')`) ou une property-access runtime (`colors.PRIMARY_500`, `theme.radius.sm`) → mêmes alternatives.
- Curseur sur un **helper call** (`spacing(0.5)`, `radius(2)`) → popup *scale* spécifique : variants synthétiques `spacing(0.25)` … `spacing(10)` calculés à partir de l'unité du helper, valeur courante pré-sélectionnée (flèches haut/bas pour naviguer la scale).
- Curseur sur un littéral hardcodé (`#fff`, `12px`, `200ms`, ou `34` en JS/TS) → liste de tokens correspondants ou approchants.
- Curseur sur une **CSS variable contextuelle** (déclarée hors des token sources : override de consumer, host binding Angular, inline style React/Vue, `setProperty`) → liste **navigable des sites de déclaration**. Chaque ligne montre le sélecteur CSS interne (ou `[runtime]`), la valeur brute, le chemin `relative/path.scss:42`, et un swatch couleur quand la valeur parse. Clic → ouvre le fichier à l'offset exact. Tri : déclarations statiques d'abord, puis runtime. Type-to-filter sur selector / value / path.
- Sélection → remplace dans le code (write action).

## 6. Inspection « Hardcoded value matches a design token »

- Détecte hex / `rgb()` / `hsl()` / lengths / durations qui matchent un token existant.
- **Numériques sans unité** (`fontSize: 34`, `lineHeight: 24`, `opacity: 0.5`) détectés en position propriété-valeur — limité aux fichiers JS/TS pour éviter le bruit en CSS shorthand. Les numéros à l'intérieur d'un `(…)` (arguments de helper) sont ignorés.
- **Helper-aware** : un `12px` hardcodé propose `spacing(1.5)` si un helper `spacing(unit=8)` est indexé (snap aux multiples de 0.25, tolérance 0.05).
- Conversion px ↔ rem ↔ em (base 16 px) pour faire matcher tokens en rem ↔ littéraux en px.
- Quick-fixes : jusqu'à 5 suggestions par occurrence, classées par contexte CSS détecté (`color: …` priorise les COLOR, `font-size: …` priorise TYPOGRAPHY, …).
- Approximation couleur : tokens à ≤5 % de delta RGBA proposés comme « closest token ».
- **Wrappers transparents** : si le littéral est l'argument unique de `rem-calc(…)` ou `utils.rem-calc(…)`, la fix remplace l'**expression entière** (pas d'imbrication redondante `utils.rem-calc(var(--token))`).
- Fichiers source-of-truth (sourcePaths d'un scope) automatiquement exclus.
- Whitelist : `0`, `0px`, `0%`, `100%`, etc.
- Fallbacks `var(--name, fallback)` ignorés.

## 7. Autocomplétion + Alt+T en TS/JS

- `var(--…` → suggère les CSS custom properties indexées.
- `$…` (SCSS/Sass) → suggère les variables Sass.
- `'{…` ou `dt('…` (TS/JS) → suggère les paths Style-Dictionary.
- **`colors.` / `theme.radius.` (TS/JS)** → suggère les jetons runtime indexés (`colors.PRIMARY_500`, `theme.radius.sm`). Trigger requiert au moins un `.` pour ne pas marcher sur la complétion native d'identifiants.
- *Suggest matching tokens when typing a value* : popup hint quand on tape `fontSize: 3` → liste les tokens dont la valeur résolue commence par `3`. **Ne vole pas le focus** : continuer à taper insère bien dans l'éditeur ; clic souris pour appliquer, Escape pour fermer.
- Alt+T sur `'{path.in.token}'`, `colors.X`, ou `spacing(N)` → popup d'alternatives, remplace l'expression entière.
- Inspection / Alt+T sur littéral dans une string TS (`gap: '0.2rem'`) → suggère un `'{token}'` matchant et remplace la string complète.
- Filtrage par kind selon l'extension : `.ts/.tsx/...` → `JS_OBJECT_PATH` + `JS_RUNTIME_PROPERTY` + `JS_RUNTIME_FUNCTION`, `.scss/.sass` → SCSS/CSS, `.css` → CSS only.
- Boost contextuel : la catégorie correspondant à la propriété CSS/JS courante remonte ; les familles déjà utilisées dans le même bloc `{}` remontent aussi.
- Swatch couleur affiché pour les tokens COLOR ; badge **ƒ** pour les helpers callables.

## 8. Scopes (multi-UIs dans un même projet)

- Un *scope* = `name` + `rootPath` (relatif au projet) + `sourcePaths` (fichiers/dossiers).
- Quand on édite un fichier dans `rootPath`, seuls ce scope + scopes communs (rootPath vide) sont actifs.
- Cache d'indexation par scope.
- Édition stable des scopes : modifier les champs d'un scope sélectionné ne contamine plus les autres lors d'un changement de sélection.
- **Live sync** : ajouter / supprimer / renommer un scope dans les Settings rafraîchit immédiatement le combo de l'Analyser (listener `fireScopesChanged`). Bouton **Re-sync** explicite dans la toolbar Analyser pour un drop forcé du cache.
- **Analyser scope-aware** : sélectionner un scope dans l'onglet Analyser restreint le file walk aux `rootPath` actifs et n'exclut que les catalogues du scope choisi (réparé en 0.1.2 — auparavant l'analyse scannait tout le projet).

## 9. Settings (Preferences → Tools → Token Flow)

| Onglet | Option | Description |
|---|---|---|
| Scopes | Master/detail | Ajouter / éditer / supprimer des scopes |
| Triggers | *Show token info on hover* | Active la popup hover (par défaut désactivée) |
| Triggers | *Hover delay* | 100–5000 ms |
| Triggers | *Suggest design tokens in completion* | Toggle de l'autocomplétion |
| Triggers | *Customize keyboard shortcut* | Lien vers le Keymap (Alt+T par défaut) |
| Triggers | *Copy value (modifier + click)* | Active le geste de copie + combo modificateur (`⌘/Ctrl + Shift` par défaut, ou `Ctrl + Shift` / `Ctrl + Alt` / `Alt + Shift` / `Alt`) |
| Triggers | *Tool window icon* | Choix entre 4 variantes (white, black, orange, arc) |

## 10. Actions

| Action | Raccourci | Endroit |
|---|---|---|
| Show All Design Tokens | — | `Tools` menu (debug) |
| Show Token Alternatives | `Alt+T` | Editor popup menu |
| Go to Token Declaration | `Alt+Shift+T` | Editor popup menu (configurable via Keymap) |
| Copy Token Value | `⌘/Ctrl+Shift+Clic` | Geste éditeur (combo configurable dans les Settings) |

## 11. Compatibilité

- IntelliJ Platform 2024.2+ (Community OK : pas de dépendance au plugin CSS).
- Kotlin + IntelliJ Platform Gradle Plugin 2.x.

---

## Roadmap d'évolution

Voir [`ROADMAP.md`](ROADMAP.md) pour le suivi par phase.

Idées en backlog : grille de cards pour COLOR, toggles granulaires d'inspection, liste configurable des wrappers transparents, JSON Style Dictionary, port VSCode, …

Phase suivante (en cours de spec) : **onglet Analyser** — score global du Design System + indicateurs détaillés (cohérence sémantique, couverture, duplication, hardcoded). Détails dans [`ROADMAP.md`](ROADMAP.md).
