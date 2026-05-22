# Plan de migration — Extraction de Cassini en projet standalone

> **But** : Cassini standalone, REST 4.0 pur, Chappe fourni par une SPI optionnelle, CDI optionnel via SPI dédiée.
> Permet une intégration propre avec Vauban et Vidocq Runtime, et ouvre la voie à trois certifications Jakarta REST 4.0 indépendantes (modes A/B/C).

---

## 0. État des lieux (constat avant migration)

### Source actuelle (dans `vidocq`)
- **Implémentation** : `vidocq-runtime-core-extensions/vidocq-runtime-cassini-rest-extension/` (≈ 45 fichiers Java)
  - Package racine : `io.vidocq.runtime.ext.rest.cassini.*`
  - Module Java : `io.vidocq.runtime.ext.rest.cassini`
- **TCK runner** : `vidocq-runtime-core-extensions/vidocq-runtime-rest-cassini-tck-runner/` (POM 4.0.0 standalone, hors reactor)
  - Score actuel : **2535/2535 passants** (100 % Core Profile / SE-Bootstrap)
  - 6 challenges TCK + 134 exclusions de tags (`servlet`, `xml_binding`)

### Couplages à découpler
1. **Imports directs `fr.vidocq.chappe.api.*`** (8 fichiers) :
   - `Invoker.java`, `ParamExtractor.java`, `CassiniRestBridge.java`
   - `internal/context/CassiniRequest.java`, `CassiniHttpHeaders.java`, `CassiniUriInfo.java`, `CassiniSecurityContext.java`
   - `internal/filter/CassiniRequestContext.java`
   - `internal/FieldInjector.java`
   - Types Chappe utilisés : `Request`, `Response`, `Body`, `StatusCode`, `Handler`
2. **Imports `io.vidocq.runtime.ext.chappe.*`** (1 fichier) :
   - `CassiniExtension.java` → `ChappeListener`, `ChappeMountPoint`
3. **Imports `io.vidocq.vauban.*`** (2 fichiers) :
   - `CassiniExtension.java` → `VaubanContainerBuilder`
   - `CassiniRestBridge.java` → `RequestContext`
4. **Imports `io.vidocq.runtime.spi.*`** (1 fichier) :
   - `CassiniExtension.java` → `VidocqExtension`, `ExtensionContext`, `VidocqConfiguration`
5. **`module-info.java`** : `requires fr.vidocq.chappe.api`, `io.vidocq.runtime.ext.chappe`, `io.vidocq.vauban.core`, `jakarta.cdi`
6. **`CassiniScopeBCE`** : extension CDI Build-Compatible — déclenche le couplage CDI

### Incohérence de groupId Chappe à clarifier (cf. Q3)
- Vidocq POM utilise `<groupId>fr.vidocq.chappe</groupId>`
- Chappe parent POM est `<groupId>io.vidocq.chappe</groupId>` (renommage en cours)

---

## 1. Architecture cible — Cassini standalone

### Layout repo `/Users/yblazart/projects/perso/vidocq/cassini`

```
cassini/
├── .forgejo/workflows/ci.yml         ← repris de chappe (build + deploy SNAPSHOT à chaque push)
├── .mvn/maven.config                 ← repris de chappe (preemptive auth)
├── .sdkmanrc                         ← java=25-tem, maven=4.0.0-rc-5
├── .gitignore
├── LICENSE                           ← Apache 2.0
├── README.md                         ← présentation + roadmap M2h/M2i
├── TCK.md                            ← procédure repro 2535/2535 + matrice
├── HOWTO-CLAUDE.md                   ← repris de vidocq, adapté Cassini
├── run-official-tck-restful-4.0.sh   ← repris + adapté (chemin nouveau module)
├── pom.xml                           ← parent reactor (Model 4.1.0)
│
├── cassini-api/                      ← interfaces publiques + SPI HTTP
│   └── pom.xml                       ← UNIQUEMENT jakarta.ws.rs-api
│   └── src/main/java/io/vidocq/cassini/spi/http/
│       ├── CassiniHttpExchange.java
│       ├── CassiniHttpAdapter.java
│       ├── CassiniAsyncContext.java
│       └── CassiniStreamingSink.java
│   └── src/main/java/io/vidocq/cassini/spi/resource/
│       └── ResourceFactory.java      ← SPI fabrique de ressources (Mode A : default new(), Mode B : Vauban/CDI)
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.api
│
├── cassini-core/                     ← Invoker, scanner, providers built-in, RuntimeDelegate
│   └── pom.xml                       ← cassini-api + jakarta.ws.rs-api + JSON-B (Yasson) + JSON-P (Parsson)
│   └── src/main/java/io/vidocq/cassini/internal/...
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.core
│
├── cassini-cdi/                      ← intégration CDI optionnelle (Mode B)
│   └── pom.xml                       ← cassini-core + jakarta.cdi-api
│   └── src/main/java/io/vidocq/cassini/cdi/
│       ├── CdiResourceFactory.java     (impl ResourceFactory déléguant à BeanManager)
│       └── CassiniScopeExtension.java  (BCE pour @RequestScoped via le conteneur hôte)
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.cdi
│
├── cassini-chappe/                   ← adapter Chappe (CassiniHttpAdapter via Chappe) — packagé dans Cassini
│   └── pom.xml                       ← cassini-core + io.vidocq.chappe:chappe-api + chappe-core (runtime)
│   └── src/main/java/io/vidocq/cassini/chappe/
│       ├── ChappeHttpAdapter.java      (implements CassiniHttpAdapter)
│       └── ChappeHttpExchange.java     (implements CassiniHttpExchange via Chappe Request/Response)
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.chappe
│
├── cassini-jdk-http/                 ← adapter JDK natif (java.net.http.HttpServer) pour standalone pur
│   └── pom.xml                       ← cassini-core uniquement (zéro dép externe)
│   └── src/main/java/io/vidocq/cassini/jdkhttp/
│       ├── JdkHttpAdapter.java
│       └── JdkHttpExchange.java
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.jdkhttp
│
└── cassini-tck/                      ← runner Arquillian + TckChallengeExclusions
    └── pom.xml                       ← Model 4.0.0 standalone (cf. note ShrinkWrap)
    └── src/main/java/io/vidocq/cassini/tck/
        ├── CassiniTestHarness.java
        ├── arquillian/...
    └── src/test/java/.../TckChallengeExclusions.java
    └── src/test/java/.../tck/...     (BasicAuthHandler, ContextProxies, Container ext)
    └── dépend de cassini-chappe en <scope>test</scope> (transport TCK = Chappe)
```

### Découplage Chappe — qui héberge l'adapter ?
- **`cassini-api`** ne dépend de **rien** sauf `jakarta.ws.rs-api`.
- **Décision (Q5)** : Cassini livre **deux adapters** dans le repo :
  - `cassini-chappe` : adapter Chappe (transport de référence, dépend de `io.vidocq.chappe:chappe-core` runtime). C'est le transport utilisé pour passer le TCK.
  - `cassini-jdk-http` : adapter JDK natif (`java.net.http.HttpServer`), zéro dép externe — utilisé pour les tests Mode A "pur" et fallback de référence si Vauban a besoin d'un transport sans cycle Chappe.
- Côté `vidocq`, l'extension `vidocq-runtime-cassini-rest-extension` devient un agrégateur léger qui dépend de `io.vidocq.cassini:cassini-chappe` + `cassini-cdi` et héberge `CassiniExtension` (`VidocqExtension`).

### Trois modes de certification
| Mode | Artefacts | Transport | Périmètre TCK | DI | Statut |
|------|-----------|-----------|---------------|----|--------|
| **A** Cassini "pur" | `cassini-api` + `cassini-core` + `cassini-jdk-http` (ou `cassini-chappe`) + `cassini-tck` | JDK HttpServer (par défaut) ou Chappe | REST 4.0 hors tests CDI | aucun (factory `new()`) | **cible immédiate après extraction** |
| **B** Cassini + Vauban | + `cassini-cdi` + `vauban-core` + `cassini-chappe` | Chappe (Vauban a besoin de virtual-thread-friendly) | REST 4.0 complet | CDI via Vauban | post-extraction, certif principal 2026 |
| **C** Vidocq Runtime | + JSON-P/B + extensions MP | Chappe | Core Profile 11 + MicroProfile | CDI complet | long terme |

---

## 2. SPI HTTP — contrat d'interface (cassini-api)

### `CassiniHttpExchange`
```java
public interface CassiniHttpExchange {
    String method();
    URI requestUri();
    String requestUriRaw();        // pour ne pas perdre l'encoding original
    Map<String,List<String>> requestHeaders();
    InputStream requestBody();

    void setStatus(int code);
    Map<String,List<String>> responseHeaders();   // mutable, lu juste avant flush
    OutputStream responseBody();

    SocketAddress remoteAddress();
    boolean isSecure();
    String authScheme();           // BASIC, etc., null si non authentifié
    Principal userPrincipal();     // null si non authentifié
}
```

### `CassiniHttpAdapter`
```java
public interface CassiniHttpAdapter {
    CompletionStage<Void> dispatch(CassiniHttpExchange exchange);
    // hookable : routing préfixe, fermeture, lifecycle
}
```

### `CassiniAsyncContext`
```java
public interface CassiniAsyncContext {
    void suspend();
    void resume(Object entity);
    void resumeWithError(Throwable t);
    void setTimeout(Duration d, Runnable handler);
    void addCompletionCallback(Runnable cb);
    void addConnectionCallback(Runnable cb);
    boolean isSuspended();
    boolean isCancelled();
    boolean isDone();
}
```

### `CassiniStreamingSink`
```java
public interface CassiniStreamingSink {
    CompletionStage<Void> writeChunk(byte[] data);
    CompletionStage<Void> flush();
    CompletionStage<Void> close();
    boolean isOpen();
}
```

### `ResourceFactory` (SPI complémentaire — Mode A vs B)
```java
public interface ResourceFactory {
    <T> T create(Class<T> resourceClass);
    void destroy(Object resource);
    static ResourceFactory defaultFactory() { /* new instance per call, no injection */ }
}
```

---

## 3. Roadmap — exécution séquentielle (proposée)

### Phase 0 — Bootstrap repo (≈ 0,5 j)
- [ ] `git init` dans `/Users/yblazart/projects/perso/vidocq/cassini`
- [ ] Copier `.sdkmanrc` (java=25-tem, maven=4.0.0-rc-5), `.mvn/maven.config`, `.forgejo/workflows/ci.yml` depuis chappe (adapter le secret `MAVEN_DEPLOY_TOKEN`)
- [ ] `LICENSE` Apache 2.0 (cohérent vidocq/chappe)
- [ ] `.gitignore` (repris de chappe)
- [ ] `pom.xml` parent (Model 4.1.0, groupId `io.vidocq.cassini`, artifactId `cassini-parent`, version `0.1.0-SNAPSHOT`, packaging `pom`, distributionManagement `repo.vidocq.dev`, `<subprojects>` × 4)
- [ ] `README.md` : présentation + roadmap M2h/M2i + 3 modes de certification + matrice de tests
- [ ] Premier commit : `chore(init): bootstrap cassini standalone repo`

### Phase 1 — Squelette des 6 modules (≈ 0,5 j)
- [ ] `cassini-api/pom.xml` : zéro dep hors `jakarta.ws.rs-api:4.0.0` + `jakarta.annotation-api`
- [ ] `cassini-core/pom.xml` : `cassini-api` + jakarta.ws.rs-api + Yasson + Parsson + (test) JUnit 6
- [ ] `cassini-cdi/pom.xml` : `cassini-core` + `jakarta.cdi-api` (provided)
- [ ] `cassini-chappe/pom.xml` : `cassini-core` + `io.vidocq.chappe:chappe-api` + `io.vidocq.chappe:chappe-core`
- [ ] `cassini-jdk-http/pom.xml` : `cassini-core` uniquement
- [ ] `cassini-tck/pom.xml` : Model 4.0.0 standalone (cf. ShrinkWrap), reprise du POM existant (profil `tck-official`)
- [ ] `module-info.java` × 5 (api/core/cdi/chappe/jdk-http) avec exports SPI publics, opens internes pour réflexion JAX-RS
- [ ] Build vide : `mvn install` doit passer (pas encore de code)

### Phase 2 — SPI HTTP & ResourceFactory (≈ 1 j)
- [ ] Écrire les 4 interfaces SPI HTTP + `ResourceFactory` dans `cassini-api`
- [ ] `ResourceFactory.defaultFactory()` : `new instance per call`, pas d'injection
- [ ] Tests unitaires basiques : un `MockExchange` + un `MockAdapter` qui écrivent dans un `ByteArrayOutputStream`
- [ ] Documenter la stabilité sémantique du SPI dans `cassini-api/README.md` (changements ≠ breaking → bump majeur)

### Phase 3 — Migration cassini-core (≈ 2-3 j)
Sous-étape A : déplacement physique
- [ ] `git mv` les ~38 fichiers `internal/*` depuis `vidocq-runtime-cassini-rest-extension/src/main/java/io/vidocq/runtime/ext/rest/cassini/internal/` → `cassini-core/src/main/java/io/vidocq/cassini/internal/`
- [ ] Renommer le package racine `io.vidocq.runtime.ext.rest.cassini` → `io.vidocq.cassini` (refactor IDE ou `sed` sur tous les `.java`)

Sous-étape B : découplage Chappe
- [ ] Remplacer chaque `import fr.vidocq.chappe.api.Request` par un usage du `CassiniHttpExchange`
- [ ] `Invoker` : `dispatch(CassiniHttpExchange)` au lieu de `(Request, Response)`. Retour `CompletionStage<Void>` interne (rempli immédiatement → ne casse pas le synchrone actuel).
- [ ] `awaitBlocking(CompletionStage)` isolé (cf. ligne 741 actuelle), marqué `// TODO(M2h): supprimer, propagé jusqu'au transport`
- [ ] `CassiniRequest` : auditer les `ThreadLocal` (notamment `PENDING_VARY`) → `// TODO(M2h): RequestContext portable virtual-thread-safe`
- [ ] Adapter `ParamExtractor`, `FieldInjector`, `CassiniHttpHeaders`, etc. à l'interface `CassiniHttpExchange`
- [ ] `CassiniSseEventSink` : abstraction vers `CassiniStreamingSink` (impl par défaut bufferisée comme aujourd'hui)
- [ ] **Aucune nouvelle dépendance runtime**, **aucune nouvelle classe publique JAX-RS exposée**

Sous-étape C : retirer CDI de cassini-core
- [ ] `CassiniExtension` (l'extension Vidocq/Vauban) **NE bouge PAS dans cassini-core** — il reste côté vidocq (cf. Phase 5)
- [ ] `CassiniScopeBCE` (extension CDI Build-Compatible) → déplacer vers `cassini-cdi` (Phase 4)
- [ ] `cassini-core` ne `requires` plus `jakarta.cdi`
- [ ] Toute référence `RequestContext` (Vauban) → migrer vers une abstraction interne `cassini-core` (ex. : `CassiniRequestContext`)

Sous-étape D : tests
- [ ] Reprendre les tests unitaires existants (`UriTemplateTest`, `MediaTypesTest`, `FormDecoderTest`, `ParamValueConverterTest`, `UriRouterBestMatchTest`)
- [ ] Adapter `CassiniEndToEndTest` pour utiliser un `MockHttpAdapter` au lieu d'un serveur Chappe réel
- [ ] `mvn install` doit produire `cassini-core-0.1.0-SNAPSHOT.jar` avec les unit tests verts

### Phase 4 — cassini-cdi (≈ 1 j)
- [ ] `CdiResourceFactory implements ResourceFactory` → délègue à `BeanManager`
- [ ] Migrer `CassiniScopeBCE` depuis vidocq + adapter pour ne plus dépendre des classes Cassini internes
- [ ] `module-info.java` : `requires io.vidocq.cassini.core` + `requires jakarta.cdi`
- [ ] `provides` ServiceLoader : `io.vidocq.cassini.spi.resource.ResourceFactory with CdiResourceFactory`
- [ ] Test minimal : 1 ressource `@RequestScoped` avec un mock `BeanManager` (Weld-SE optionnel)

### Phase 5 — Adapters Chappe + JDK (≈ 1-2 j)
**Dans le repo Cassini** :
- [ ] Module `cassini-chappe` : implémente `CassiniHttpAdapter` + `CassiniHttpExchange` à partir de `Request`/`Response` Chappe (reprend la logique de `CassiniRestBridge` actuel)
- [ ] Module `cassini-jdk-http` : implémente les mêmes interfaces sur `com.sun.net.httpserver.HttpServer` (JDK pur). Sert de transport "Mode A" et de filet de sécurité si Vauban a un cycle Chappe.
- [ ] Tests unitaires basiques : ping/echo HTTP via chaque adapter

**Côté `vidocq`** :
- [ ] `vidocq-runtime-cassini-rest-extension` devient un mince agrégateur :
   - dépend de `io.vidocq.cassini:cassini-chappe` (récupère `cassini-core` transitivement)
   - dépend de `io.vidocq.cassini:cassini-cdi`
   - héberge `CassiniExtension implements VidocqExtension` (le `VidocqExtension` reste côté vidocq, cf. Q12)
- [ ] Mettre à jour le `<dependencyManagement>` du parent vidocq pour référencer `io.vidocq.cassini:*:0.1.0-SNAPSHOT`

### Phase 6 — Migration cassini-tck (≈ 1-2 j)
- [ ] `git mv` du `vidocq-runtime-rest-cassini-tck-runner/` → `cassini-tck/`
- [ ] Renommer le package `io.vidocq.runtime.ext.rest.cassini.tck.*` → `io.vidocq.cassini.tck.*`
- [ ] **Adapter pour le TCK** (Q5 tranchée) : `cassini-tck` dépend de `cassini-chappe` en `<scope>test</scope>` → Chappe est le transport utilisé pour passer le TCK officiel. `cassini-jdk-http` reste disponible en fallback Mode A.
- [ ] Conserver `TckChallengeExclusions` à l'identique (6 challenges)
- [ ] Conserver le profil `tck-official` (Jersey CLIENT, JSON-B CLIENT, multipart, sigtest, etc.)
- [ ] Ajouter les **tags JUnit** vides `@Tag("async")` et `@Tag("sse-streaming")` dans la classe `TckChallengeExclusions` ou via une `@Tags` configuration → `excludedGroups` actuel reste `servlet,xml_binding`, mais les tags M2h sont déjà préparés
- [ ] Activer en CI (run secondaire optionnel) un job `mvn -Ptck-official-async-preview verify` qui ne fait que **lister** les tests M2h (assert vert sur le quorum actuel)

### Phase 7 — Validation 2535/2535 (≈ 0,5 j)
- [ ] `./run-official-tck-restful-4.0.sh all` depuis le repo Cassini
- [ ] Résultat attendu : `Tests run: 2670, Failures: 0, Errors: 0, Skipped: 135`
- [ ] **L'extraction n'est terminée que quand ce score est reproduit dans le repo Cassini** (cf. contrainte 4)
- [ ] Mettre à jour `TCK.md` du repo Cassini avec :
  - procédure repro depuis Cassini
  - matrice tags (servlet, xml_binding, async, sse-streaming)
  - 6 challenges + lien vers `TckChallengeExclusions`

### Phase 8 — Réintégration vidocq (≈ 0,5 j)
- [ ] Push initial Cassini en SNAPSHOT vers `repo.vidocq.dev/snapshots`
- [ ] Dans vidocq : remplacer la dép interne `vidocq-runtime-cassini-rest-extension` (Cassini intégré) par `io.vidocq.cassini:cassini-core` + `:cassini-cdi` + adapter local
- [ ] `mvn -DskipTests install` du reactor vidocq doit passer
- [ ] Lancer le TCK depuis vidocq pour valider la non-régression : 2535/2535 toujours
- [ ] Tag git Cassini : `v0.1.0-extraction` (SNAPSHOT) — pas de release publique encore

### Phase 9 — Documentation & roadmap (≈ 0,5 j)
- [ ] `README.md` Cassini :
  - Présentation
  - Quickstart (Mode A, exemple `Cassini.builder().http(myAdapter).register(MyResource.class).start()`)
  - Section **Roadmap** : M2h (≈ 8-13 j) puis M2i (≈ 1-2 j)
  - Statut TCK : 2535/2535 sur Core Profile / SE-Bootstrap
- [ ] `TCK.md` Cassini : repris de vidocq, mis à jour
- [ ] `HOWTO-CLAUDE.md` : adapté au repo Cassini

---

## 4. Contraintes (rappel — issues du brief utilisateur)

1. **Pas d'import direct `fr.vidocq.chappe.*` dans Cassini** → tout passe par `cassini-api`
2. **Découpage en sous-modules dès l'extraction** → `api`, `core`, `cdi`, `tck`
3. **Préserver invariants async dès maintenant** :
   - Invoker retourne `CompletionStage<Void>` interne (rempli immédiatement)
   - `awaitBlocking()` isolé et marqué `TODO(M2h)`
   - ThreadLocals audités et marqués
4. **Geler le TCK comme contrat** : 2535/2535 dans Cassini après extraction
5. **Préserver matrice tests M2h** : tags `async` + `sse-streaming` créés
6. **Roadmap explicite** dans `README.md` Cassini : M2h (8-13 j) + M2i (1-2 j)
7. **Naming/groupId figés** : `io.vidocq.cassini`, modules `io.vidocq.cassini.{api,core,cdi,tck}`
8. **À NE PAS faire** : M2h en parallèle, changement de couverture TCK, nouvelle dép runtime, casser API publique JAX-RS

---

## 5. Décisions actées (2026-04-28)

| # | Question | Décision |
|---|----------|----------|
| Q1 | Repo git | **`forge.vidocq.dev/vidocq/cassini`** (Forgejo, cohérent chappe) |
| Q2 | License | **Apache 2.0** |
| Q3 | groupId Chappe | **`io.vidocq.chappe`** (le parent POM chappe est canonique ; vidocq sera mis à jour pour aligner) |
| Q4 | Transport unit-tests cassini-core | **Chappe** par défaut. `cassini-jdk-http` disponible si besoin de découplage |
| Q5 | Adapter TCK | **Chappe en `<scope>test</scope>`** dans `cassini-tck` |
| Q6 | `CassiniRuntimeDelegate` | **reste dans `cassini-core`** (la property système `-Djakarta.ws.rs.ext.RuntimeDelegate` continue de forcer la sélection face à Jersey) |
| Q7 | CI Forgejo | **deploy SNAPSHOT à chaque push** |
| Q8 | Migration sources | **`git filter-repo`** pour préserver l'historique granulaire des 45 fichiers Cassini |
| Q10 | Versionnement | **on garde la version `0.1.0-SNAPSHOT`** alignée avec vidocq/chappe |
| Q11 | CDI référence pour les tests | **Vauban**. Si cycle Vauban→Chappe, basculer ces tests sur `cassini-jdk-http` |
| Q12 | `CassiniExtension` (`VidocqExtension`) | **reste dans vidocq**. `CassiniRestBridge` devient `cassini-chappe`/`ChappeHttpAdapter` |
| Q13 | JUnit | **JUnit 6** (BOM `6.0.3`) |
| Q14 | Naming Maven | **`cassini-api`, `cassini-core`, `cassini-cdi`, `cassini-chappe`, `cassini-jdk-http`, `cassini-tck`** |

### Questions résiduelles

- **Q9** : Cassini intègre-t-il `chappe-bench` dans le CI pour la non-régression perf ? (par défaut : non, à activer plus tard)

---

## 6. Tâches préliminaires identifiées (TaskList)

- [ ] T1 — Bootstrap repo Cassini (.sdkmanrc, .mvn, .forgejo, LICENSE, README, pom parent)
- [ ] T2 — Créer 6 modules vides (api/core/cdi/chappe/jdk-http/tck) avec leur module-info
- [ ] T3 — Écrire les 4 SPI HTTP + ResourceFactory dans cassini-api
- [ ] T4 — Migrer ~38 fichiers cassini-internal via `git filter-repo` vers cassini-core, renommer le package `io.vidocq.cassini.*`, découpler Chappe
- [ ] T5 — Migrer CassiniScopeBCE + écrire CdiResourceFactory dans cassini-cdi
- [ ] T6 — Écrire `cassini-chappe` (ChappeHttpAdapter) et `cassini-jdk-http` (JdkHttpAdapter)
- [ ] T7 — Migrer cassini-tck-runner → cassini-tck (adapter Chappe en test-scope), ajouter tags @async/@sse-streaming
- [ ] T8 — Préserver invariants async (CompletionStage interne, awaitBlocking isolé, TODO M2h)
- [ ] T9 — Lancer TCK depuis Cassini : 2535/2535 ✅
- [ ] T10 — Mettre à jour vidocq pour consommer Cassini SNAPSHOT (alignement groupId `io.vidocq.chappe`), valider non-régression
- [ ] T11 — Documentation finale (README + TCK.md + roadmap)

**Estimation totale** : 8-12 jours de développement.

---

## 6.bis — Audit du couplage Chappe/Vauban/vidocq-spi (post-import)

**État après import via `git filter-repo`** (commit `758c0ec`) :

### Fichiers à découpler de `fr.vidocq.chappe.*` (8 fichiers, ~50 usages)

| Fichier | Imports | Action |
|---------|---------|--------|
| `internal/CassiniRestBridge.java` | `Body, Handler, Request, Response, StatusCode` + `vauban.RequestContext` | **MOVE → cassini-chappe** comme `ChappeHttpAdapter implements CassiniHttpAdapter` (c'est l'adapter natif Chappe) |
| `internal/Invoker.java` | `Body, Request, Response, StatusCode` | **REFACTOR** → utilise `CassiniHttpExchange` au lieu de Request/Response. Changement de signature : `dispatch(CassiniHttpExchange) → CompletionStage<Void>` |
| `internal/ParamExtractor.java` | `Request` | **REFACTOR** → lit headers/body via `CassiniHttpExchange` |
| `internal/FieldInjector.java` | `Request` | **REFACTOR** → idem |
| `internal/filter/CassiniRequestContext.java` | `Request` | **REFACTOR** → idem |
| `internal/context/CassiniRequest.java` | `Request` | **REFACTOR** → wrap `CassiniHttpExchange` au lieu de Chappe Request |
| `internal/context/CassiniHttpHeaders.java` | `Request` | **REFACTOR** → idem |
| `internal/context/CassiniUriInfo.java` | `Request` | **REFACTOR** → idem |
| `internal/context/CassiniSecurityContext.java` | `Request` | **REFACTOR** → idem |

### Fichiers à découpler de `io.vidocq.vauban.*` (2 fichiers)

| Fichier | Imports | Action |
|---------|---------|--------|
| `internal/CassiniRestBridge.java` | `vauban.core.context.RequestContext` | Disparaît avec le move vers cassini-chappe |
| `CassiniExtension.java` | `vauban.core.container.VaubanContainerBuilder` | **MOVE OUT → vidocq** |

### Fichiers à découpler de `io.vidocq.runtime.*` (1 fichier)

| Fichier | Imports | Action |
|---------|---------|--------|
| `CassiniExtension.java` | `runtime.ext.chappe.{ChappeListener, ChappeMountPoint}`, `runtime.spi.{ExtensionContext, VidocqConfiguration, VidocqExtension}` | **MOVE OUT → vidocq-runtime-cassini-rest-extension** (cf. Q12 — `CassiniExtension` reste côté vidocq) |

### Fichiers TCK à découpler (3 fichiers)

| Fichier | Action |
|---------|--------|
| `cassini-tck/src/test/java/.../arquillian/VidocqCassiniDeployableContainer.java` | **REFACTOR** → utilise `cassini-chappe` directement, pas `vidocq-runtime-chappe-extension` |
| `cassini-tck/src/test/java/.../arquillian/BasicAuthHandler.java` | **REFACTOR** → idem |
| `cassini-tck/src/main/java/.../CassiniTestHarness.java` | **REFACTOR** → idem |

### Stratégie de découplage (ordre proposé)

1. **Phase 3a** — ✅ Move `CassiniExtension` hors de Cassini (vers vidocq). `CassiniScopeBCE` → `cassini-cdi/CassiniScopeExtension`.
2. **Phase 3b** — ✅ Refactor `CassiniRequest`, `CassiniHttpHeaders`, `CassiniUriInfo`, `CassiniSecurityContext` pour wrapper `CassiniHttpExchange`. `FieldInjector` (90 % migré).
3. **Phase 3c** — 🚧 Refactor `Invoker` + `ParamExtractor` + `CassiniRequestContext` pour utiliser `CassiniHttpExchange`.
4. **Phase 3c-bis** — 🚧 **DÉCOUVERTE POST-IMPORT** : 4 fichiers dépendent du CDI `BeanManager` (pas seulement Chappe) :
   - `Invoker.java` — résout ressources + providers via `BeanManager`
   - `ResourceScanner.java` — découverte `@Path` beans via `BeanManager`
   - `ExceptionMapperRegistry.java` — résolution providers via `BeanManager`
   - `FilterRegistry.java` — filtres CDI

   **Implication** : `cassini-core` ne peut pas être livré sans CDI sauf à élargir le SPI `ResourceFactory` en un `BeanRegistry` plus complet :
   ```java
   public interface BeanRegistry {
       <T> T resolve(Class<T> type);
       <T> List<T> resolveAll(Class<T> type);    // pour providers/filters
       <T> List<Class<? extends T>> discover(Class<? extends Annotation> ann);  // pour @Path
   }
   ```
   `cassini-core` utilise `BeanRegistry` (SPI dans `cassini-api`). `cassini-cdi` fournit `CdiBeanRegistry` qui délègue à `BeanManager`. Mode A fournit `ServiceLoaderBeanRegistry` ou registry manuel via builder.

5. **Phase 3d** — Move `CassiniRestBridge` → `cassini-chappe/ChappeHttpAdapter.java` (impl `CassiniHttpAdapter` côté Chappe). Devient le seul endroit où Chappe est touché côté Cassini.
6. **Phase 3e** — Refactor TCK runner (3 fichiers Arquillian) pour piloter Cassini via `cassini-chappe` + `cassini-cdi` (Mode B).
7. **Phase 3f** — Validation : `mvn install` du reactor + `./run-official-tck-restful-4.0.sh all` = 2535/2535.

### Estimation révisée
- Phase 3a-3b : ✅ fait (~2-3 h)
- Phase 3c + 3c-bis : ~2-3 j (refactor BeanManager → BeanRegistry sur 4 fichiers, dont Invoker ~800 lignes)
- Phase 3d : ~1 j (move CassiniRestBridge → cassini-chappe + adapter `CassiniHttpExchange`)
- Phase 3e-3f : ~1-2 j

**Total révisé** : 4-6 jours (vs estimation initiale 2-3 j) pour cassini-core découplé + TCK 2535/2535.

### État précis post-Phase 3c (checkpoint scaffolding)

**Décision pragmatique** : `cassini-core` accepte CDI en `<scope>provided</scope>` + `requires static jakarta.cdi`. Mode A "pur" (sans CDI) sera factoré ultérieurement via un SPI `BeanRegistry`. Cette décision permet de compiler vite et passer le TCK plus rapidement.

**Scaffolding créé** :
- `cassini-core/internal/transport/CassiniHttpResponse.java` — record neutre (status, headers, body bytes) + `writeTo(CassiniHttpExchange)`. Sert d'IR (intermediate representation) de la réponse, indépendante du transport.
- module-info exporte `internal.transport` aux modules transport (chappe, jdk-http) et tck.

**Refactors mécaniques restants pour Invoker.java (1292 lignes)** :
1. Imports : retirer `fr.vidocq.chappe.api.{Body, Request, Response, StatusCode}`, ajouter `CassiniHttpExchange` + `CassiniHttpResponse`
2. Signatures : `Request request → CassiniHttpExchange exchange` (~10 méthodes : `invoke`, `invokeDynamicLocator`, `dispatchOnInstance`, `invokeDynamicLocatorWithInstance`, `invokeFinalOnInstance`, `runPreMatching`, `invokeInternal`, `injectProviderContexts`, `hasRequestBody`, `readEntity`, `pickBestMatch`, `renderThrowable`)
3. Retours : `Response → CassiniHttpResponse` (~50 occurrences). `Response.builder().status(StatusCode.X).header(...).body(Body.of(...)).build()` → `CassiniHttpResponse.builder().status(X).header(...).body(bytes).build()`
4. Lectures : `request.headers().firstOrNull(X)` → `exchange.firstHeader(X)` ; `request.body().asInputStream()` → `exchange.requestBody()` ; etc.

**ParamExtractor.java (543 lignes)** : mêmes patterns mécaniques que FieldInjector.

**CassiniRequestContext.java filter (243 lignes)** : wrap `CassiniHttpExchange` au lieu de Chappe Request.

**CassiniRestBridge.java (158 lignes)** : intégralement déplacé vers `cassini-chappe/ChappeHttpAdapter.java`. Adapte `Request/Response` Chappe ↔ `CassiniHttpExchange` + `CassiniHttpResponse`. Le code de routing/filters/error-handling reste dans Invoker (déplacé là-bas).

**Estimation reste** : ~1 j (refactor mécanique Invoker + ParamExtractor + CassiniRequestContext) + ~0,5 j (write cassini-chappe ChappeHttpAdapter) + ~0,5 j (adapter cassini-jdk-http) + ~0,5 j (refactor TCK runner) + ~0,5 j (validation 2535/2535).

---

## 7. Notes & risques

- **ShrinkWrap résolveur** : `cassini-tck` doit rester en POM Model 4.0.0 (cf. note dans le pom existant) — Maven 4.1 / ShrinkWrap 1.2.x ne savent pas se parler.
- **Module Java automatique vs explicite** : tous les modules Cassini doivent avoir un `module-info.java` explicite (cohérent avec chappe et vidocq).
- **ServiceLoader pour `RuntimeDelegate`** : si le TCK CLIENT charge Jersey, l'ordre de découverte peut sélectionner Jersey à la place de Cassini → la property `-Djakarta.ws.rs.ext.RuntimeDelegate` reste nécessaire (déjà dans le profil `tck-official`).
- **Module `cassini-cdi` et BCE** : Build-Compatible Extension exige `jakarta.cdi-api` 4.1+ ; à valider.
- **Tests unitaires Cassini sans serveur réel** : le module `cassini-core` doit pouvoir construire/exécuter ses tests **sans Chappe ni JDK HttpServer** → un `MockHttpAdapter` léger en `src/test/java` est requis (objectif Mode A).

---

*Document de travail — à amender après réponses aux questions Q1-Q14.*
