# Token Flow — Features & Options

Catalogue vivant des fonctionnalités du plugin. À mettre à jour à chaque évolution.

---

## 1. Indexation des design tokens

| Source | Reconnu | Notes |
|---|---|---|
| `--var: value;` (CSS / SCSS source) | ✅ | Custom properties standard |
| `$var: value;` (SCSS) | ✅ | Variables Sass |
| `"name": value,` (SCSS map keys) | ✅ | Tokens type `name` (Style Dictionary, etc.) |
| Objets TS/JS (`export default { … }`) | ✅ | Path en pointillé (`color.primary.500`) |
| Aliases récursifs (`$a: $b`, `var(--c)`) | ✅ | Garde anti-cycle |

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

## 4. Alternatives popup (`Alt+T`)

- Curseur sur un token → liste des tokens de la même catégorie, triés par proximité (HSL pour COLOR, valeur croissante pour LENGTH).
- Curseur sur un littéral hardcodé (`#fff`, `12px`, `200ms`) → liste de tokens correspondants ou approchants.
- Sélection → remplace dans le code (write action).

## 5. Inspection « Hardcoded value matches a design token »

- Détecte hex / `rgb()` / `hsl()` / lengths / durations qui matchent un token existant.
- Conversion px ↔ rem ↔ em (base 16 px) pour faire matcher tokens en rem ↔ littéraux en px.
- Quick-fixes : jusqu'à 5 suggestions par occurrence, classées par contexte CSS détecté (`color: …` priorise les COLOR, `font-size: …` priorise TYPOGRAPHY, …).
- Approximation couleur : tokens à ≤5 % de delta RGBA proposés comme « closest token ».
- **Wrappers transparents** : si le littéral est l'argument unique de `rem-calc(…)` ou `utils.rem-calc(…)`, la fix remplace l'**expression entière** (pas d'imbrication redondante `utils.rem-calc(var(--token))`).
- Fichiers source-of-truth (sourcePaths d'un scope) automatiquement exclus.
- Whitelist : `0`, `0px`, `0%`, `100%`, etc.
- Fallbacks `var(--name, fallback)` ignorés.

## 6. Autocomplétion + Alt+T en TS/JS

- `var(--…` → suggère les CSS custom properties indexées.
- `$…` (SCSS/Sass) → suggère les variables Sass.
- `'{…` ou `dt('…` (TS/JS) → suggère les paths d'objet.
- Alt+T sur `'{path.in.token}'` → liste les alternatives, remplace l'expression entière (quotes incluses).
- Inspection / Alt+T sur littéral dans une string TS (`gap: '0.2rem'`) → suggère un `'{token}'` matchant et remplace la string complète.
- Filtrage par kind selon l'extension : `.ts/.tsx/...` → JS_OBJECT_PATH uniquement, `.scss/.sass` → CSS/SCSS, `.css` → CSS only.
- Boost contextuel : la catégorie correspondant à la propriété CSS courante remonte ; les familles déjà utilisées dans le même bloc `{}` remontent aussi.
- Swatch couleur affiché dans la liste pour les tokens COLOR.

## 7. Scopes (multi-UIs dans un même projet)

- Un *scope* = `name` + `rootPath` (relatif au projet) + `sourcePaths` (fichiers/dossiers).
- Quand on édite un fichier dans `rootPath`, seuls ce scope + scopes communs (rootPath vide) sont actifs.
- Cache d'indexation par scope.
- Édition stable des scopes : modifier les champs d'un scope sélectionné ne contamine plus les autres lors d'un changement de sélection.

## 8. Settings (Preferences → Tools → Token Flow)

| Onglet | Option | Description |
|---|---|---|
| Scopes | Master/detail | Ajouter / éditer / supprimer des scopes |
| Triggers | *Show token info on hover* | Active la popup hover (par défaut désactivée) |
| Triggers | *Hover delay* | 100–5000 ms |
| Triggers | *Suggest design tokens in completion* | Toggle de l'autocomplétion |
| Triggers | *Customize keyboard shortcut* | Lien vers le Keymap (Alt+T par défaut) |
| Triggers | *Tool window icon* | Choix entre 4 variantes (white, black, orange, arc) |

## 9. Actions

| Action | Raccourci | Endroit |
|---|---|---|
| Show All Design Tokens | — | `Tools` menu (debug) |
| Show Token Alternatives | `Alt+T` | Editor popup menu |
| Go to Token Declaration | `Alt+Shift+T` | Editor popup menu (configurable via Keymap) |

## 10. Compatibilité

- IntelliJ Platform 2024.2+ (Community OK : pas de dépendance au plugin CSS).
- Kotlin + IntelliJ Platform Gradle Plugin 2.x.

---

## Roadmap d'évolution

Voir [`ROADMAP.md`](ROADMAP.md) pour le suivi par phase.

Idées en backlog : grille de cards pour COLOR, toggles granulaires d'inspection, liste configurable des wrappers transparents, JSON Style Dictionary, port VSCode, …

Phase suivante (en cours de spec) : **onglet Analyser** — score global du Design System + indicateurs détaillés (cohérence sémantique, couverture, duplication, hardcoded). Détails dans [`ROADMAP.md`](ROADMAP.md).
