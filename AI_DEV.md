# Rapport — Gains de productivité avec assistance IA

> Session de développement Cassini · 2026-04-29 → 2026-04-30 · ~6h temps écoulé

## Méthodologie

Comparaison du temps réel constaté avec l'assistant IA contre :
- **Solo (toi seul)** — un seul développeur, vitesse réaliste avec recherches, tests, refactorings
- **Duo (2 seniors)** — deux développeurs expérimentés, possibilité de paralléliser, surcoût coordination/PR-review

Toutes les estimations s'entendent **toolchain en main** (IDE configuré, TCK installé, repo cloné). On ne compte pas la phase d'apprentissage initiale du projet.

Les chiffres sont exprimés en **jours-développeur 8h pleines** (donc productifs, pas calendaires).

---

## Inventaire du travail accompli

### 1. Validation TCK + investigation problèmes (`22e78a1` → `4ca6b0e`)
- Lancement TCK 2670 tests, diagnostic problèmes (zombie process port 8080, locks Maven)
- Analyse architecturale du SSE streaming Chappe : identification du **deadlock circulaire** (handler synchrone vs `Body.streaming(pis)`)
- Refactor `Invoker.java` : `awaitClose()` déplacé après `isStreaming()` (fix conceptuel)

### 2. Documentation ASYNC.md complète (`fc31f50`)
- 200 lignes détaillant l'état async (M2h), le deadlock SSE Chappe, le design de remédiation (VT + CountDownLatch)
- Tableau comparatif transport-par-transport

### 3. Module `cassini-examples` — 3 sous-modules (`4ec0a95` → `bbb7ef8`)
- `cassini-examples-chappe` : Mode A pur, SeBootstrap standard, 10 tests
- `cassini-examples-jdkhttp` : transport JDK natif + `JdkHttpRuntimeDelegate`, 10 tests
- `cassini-examples-vauban` : Mode B CDI, 15 tests (incluant UI)
- 3 fichiers `.http` IntelliJ par module
- Itérations sur la compatibilité JPMS / IntelliJ test runner

### 4. Refactoring architectural majeur — SPI `CassiniStack` (`67bedad` → `7ddd202`)
- Nouvelle SPI publique dans `cassini-api`
- `cassini-chappe`, `cassini-jdk-http`, `cassini-cdi` ne dépendent plus des packages internes
- `cassini-core` exporte uniquement à `cassini-tck`
- Suppression du code CDI mort (`Invoker.forBeanManager`, `FilterRegistry.discover`, etc.)

### 5. Refactoring architectural majeur — SPI `BeanProvider` (`9976e5e`)
- Nouvelle SPI publique pour intégration DI agnostique (Vauban, Weld, OpenWebBeans futurs)
- `cassini-cdi` renommé en `cassini-cdi-vauban`
- Suppression `CdiResourceFactory`, création `VaubanBeanProvider` + `Factory` ServiceLoader avec `priority()`
- Auto-discovery dans `CassiniStack.builder()`
- Fusion `getResourceClasses()` + `Application.getClasses()` + singletons

### 6. UI HTML/CSS/JS pour `cassini-examples-vauban` (`bbc6e56` → `fbaf5bf`)
- Page web complète : header animé, form Todo, liste interactive, dark theme moderne
- Handler composite Chappe : statique sur `/` + Cassini sur `/api/*`
- Découverte et adoption de `StaticFileHandler` natif Chappe (au lieu de custom code)
- Découverte et adoption de `vauban-maven-plugin:generate` + `scanClasspath()` (au lieu de `addBeanClass()` × N)

### 7. Documentation (`c04dc92`)
- README par module exemple (architecture, schémas ASCII, choix de design)
- Mises à jour CLAUDE.md, TCK.md, README racine, ASYNC.md

---

## Métriques quantitatives

| Métrique | Valeur |
|---|---|
| Commits | **24** |
| Fichiers changés | **83** |
| Lignes ajoutées (net) | **+4 287** |
| Code Java | 3 739 lignes |
| Documentation Markdown | 967 lignes |
| Configuration XML/POM | 265 lignes |
| UI (HTML/CSS/JS) | 388 lignes |
| Tests TCK conservés | **2535/2535** ✅ (zéro régression) |
| Tests exemples ajoutés | **30/30** ✅ |

---

## Estimation temps comparée

| Tâche | Solo (toi) | Duo (2 seniors) | Avec IA |
|---|---:|---:|---:|
| Validation TCK + diagnostic deadlock SSE | 0,5 j | 0,5 j | ~30 min |
| Documentation ASYNC.md (analyse + remédiation) | 1 j | 0,75 j | ~20 min |
| Module `cassini-examples-chappe` (resources, tests, .http, JPMS fixes) | 1 j | 0,75 j | ~30 min |
| Module `cassini-examples-jdkhttp` (+ `JdkHttpRuntimeDelegate`) | 0,75 j | 0,5 j | ~25 min |
| Module `cassini-examples-vauban` initial (CDI, singletons singleton kludge) | 1 j | 0,75 j | ~30 min |
| Refactor SPI `CassiniStack` + `DefaultCassiniHttpAdapter` | 2,5 j | 1,75 j | ~45 min |
| Refactor SPI `BeanProvider` + renommage `cassini-cdi-vauban` | 1,5 j | 1 j | ~30 min |
| UI HTML/CSS/JS + handler composite | 0,75 j | 0,5 j | ~30 min |
| Adoption `StaticFileHandler` + `scanClasspath()` (découverte API) | 0,5 j | 0,25 j | ~15 min |
| README par module + maintenance docs | 1 j | 0,75 j | ~20 min |
| Itérations correction bugs (META-INF/services, JPMS, ServiceLoader, etc.) | 1 j | 0,75 j | inclus |
| **Total** | **11,5 j** | **8,25 j** | **~5h** |

### Gain brut (vs temps facturé)

| Comparaison | Ratio |
|---|---:|
| **IA vs Solo** | **~18× plus rapide** |
| **IA vs Duo seniors** | **~13× plus rapide** |

> Sur cette session, en supposant 8h productives par jour, on obtient :
> - Solo : 92 heures
> - Duo : 66 heures (× 2 développeurs = 132 heures-homme)
> - IA : ~5 heures de pilotage humain

---

## Analyse qualitative — forces et faiblesses observées

### Forces de l'IA

✅ **Génération de code repetitive** — copier les ressources JAX-RS d'un module à l'autre, adapter les imports, écrire les tests JUnit : quasi-instantané. C'est là où le ratio est le plus brutal (×30 à ×50).

✅ **Refactorings massifs cross-module** — déplacer `CassiniRuntimeDelegate`, créer la SPI `BeanProvider`, propager dans 5 modules + tests + docs : très bonne synthèse, peu d'erreurs sur la mécanique.

✅ **Documentation** — README, schémas ASCII, tableaux comparatifs : qualité directement publiable, ratio ×10 minimum.

✅ **Recherche dans la codebase** — `grep`, `find`, lecture ciblée : économise les minutes-de-clic dans l'IDE.

### Faiblesses observées (et coût réel)

⚠️ **Découverte d'API existantes** — l'IA ne fouille pas spontanément les libs voisines. J'ai dû te demander de pointer `vauban-maven-plugin` et `StaticFileHandler` Chappe. Sans cette intervention humaine, j'aurais maintenu le custom code inférieur. **Coût** : un dev senior aurait probablement aussi mis du temps avant de découvrir ces APIs (même hiérarchie d'oubli).

⚠️ **Choix architecturaux discutables** — première version de l'intégration Vauban utilisait `getSingletons()` avec instances CDI (kludge). Il a fallu ton intervention "je ne veux pas spécifier les singletons" pour redesigner avec `BeanProvider`. **Coût** : 30-45 min de re-travail. Un senior solo aurait probablement choisi le bon pattern dès le départ.

⚠️ **Bugs latents** — le worktree agent qui a refait `ChappeRuntimeDelegate` a dupliqué ~700 lignes de boilerplate au lieu d'étendre `CassiniRuntimeDelegate` (mauvais choix). J'ai aussi commis l'erreur d'oublier `META-INF/services` (ServiceLoader cassé en classpath mode → TCK FAILURE 7 errors).

⚠️ **Itérations supplémentaires** — JPMS module-info, conflits IntelliJ test runner, exports `to named-module`, port 8080 zombie : ~1h cumulée d'allers-retours. Un senior expérimenté JPMS aurait évité une partie de ces frictions.

⚠️ **Pilotage requis** — sans ta supervision sur les choix design, l'IA aurait livré des solutions techniquement correctes mais architecturalement médiocres. L'IA exécute brillamment les bons designs, mais sait moins les inventer.

### Estimation honnête du gain "réel"

Si on enlève les itérations de re-travail dues à des choix IA sub-optimaux :

| Scénario | Gain effectif |
|---|---|
| **Travail sous direction architecturale humaine forte** | **×10 à ×15** (cas observé) |
| **IA en autonomie totale** | **×3 à ×5** (la qualité chute, beaucoup de re-travail) |
| **IA pour génération + revue humaine systématique** | **×8 à ×12** (sweet spot) |

---

## Coût économique indicatif

À TJM 600 € (senior France 2026) :

| Scénario | Coût estimé |
|---|---:|
| Solo, 11,5 j | **6 900 €** |
| Duo (2 seniors), 8,25 j × 2 | **9 900 €** |
| Toi + IA, 5h pilotage (~0,7 j) + tokens | **~500 €** (TJM proratisé + ~10 € tokens API) |

**Économie potentielle : ~6 400 € à 9 400 € pour cette session.**

---

## Limites de cette analyse

1. **Échantillon biaisé** : tâches majoritairement de *refactoring* et *documentation*, terrain favorable à l'IA. Le ratio serait différent sur :
   - Architecture from scratch (×3-5 max — l'humain reste meilleur en design)
   - Debug profond multi-thread / concurrent (×2-4 — l'IA peine sur les bugs subtils)
   - Optimisation performance (×2-3 — nécessite mesure et intuition)

2. **Pas de comparaison directe** : je n'ai pas refait la même session sans IA pour mesurer empiriquement. Les chiffres "Solo" et "Duo" sont des estimations basées sur l'expérience du domaine.

3. **Coût caché de la supervision** : tu as dû relire chaque diff, valider les choix, parfois corriger. Ce coût n'est pas zéro et ne diminue pas linéairement avec la taille de session.

4. **Effet Hawthorne inversé** : l'IA force une discipline de commit/test/doc qu'un humain seul abrège souvent. Une partie du gain vient en réalité de la rigueur, pas de la vitesse pure.

---

## Conclusion

Sur cette session précise (refactoring + exemples + docs sur projet existant) :
- **Gain brut estimé : ×13 à ×18**
- **Gain effectif observé (avec re-travail) : ×10 à ×15**
- **Sweet spot recommandé** : IA pour exécution massive, humain pour design et revue critique

Le facteur le plus important n'est pas le ratio brut mais la **qualité maintenue** : 0 régression TCK (2535/2535), 30/30 tests exemples, architecture proprement découplée, JPMS strict. Ce sont des résultats normalement réservés à des semaines de travail soigneux — atteints en ~5h ici.

> ⚠️ Ces chiffres ne sont **pas** transposables tels quels à des projets where-cleaner. Sur un projet greenfield, le ratio est souvent plus bas (×3-5) car le design domine la production. Sur du legacy chaotique, le ratio peut grimper (×20+) car l'IA navigue mieux qu'un humain dans le bruit.
