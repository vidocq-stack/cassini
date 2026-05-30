# Migration plan — Extracting Cassini into a standalone project

> **Goal**: standalone Cassini, pure REST 4.0, Chappe provided by an optional SPI, CDI optional via a dedicated SPI.
> Enables a clean integration with Vauban and Vidocq Runtime, and opens the way to three independent Jakarta REST 4.0 certifications (modes A/B/C).

---

## 0. Current state (assessment before migration)

### Current source (inside `vidocq`)
- **Implementation**: `vidocq-runtime-core-extensions/vidocq-runtime-cassini-rest-extension/` (≈ 45 Java files)
  - Root package: `io.vidocq.runtime.ext.rest.cassini.*`
  - Java module: `io.vidocq.runtime.ext.rest.cassini`
- **TCK runner**: `vidocq-runtime-core-extensions/vidocq-runtime-rest-cassini-tck-runner/` (standalone POM 4.0.0, outside reactor)
  - Current score: **2535/2535 passing** (100% Core Profile / SE-Bootstrap)
  - 6 TCK challenges + 134 tag exclusions (`servlet`, `xml_binding`)

### Couplings to decouple
1. **Direct imports of `fr.vidocq.chappe.api.*`** (8 files):
   - `Invoker.java`, `ParamExtractor.java`, `CassiniRestBridge.java`
   - `internal/context/CassiniRequest.java`, `CassiniHttpHeaders.java`, `CassiniUriInfo.java`, `CassiniSecurityContext.java`
   - `internal/filter/CassiniRequestContext.java`
   - `internal/FieldInjector.java`
   - Chappe types used: `Request`, `Response`, `Body`, `StatusCode`, `Handler`
2. **Imports of `io.vidocq.runtime.ext.chappe.*`** (1 file):
   - `CassiniExtension.java` → `ChappeListener`, `ChappeMountPoint`
3. **Imports of `io.vidocq.vauban.*`** (2 files):
   - `CassiniExtension.java` → `VaubanContainerBuilder`
   - `CassiniRestBridge.java` → `RequestContext`
4. **Imports of `io.vidocq.runtime.spi.*`** (1 file):
   - `CassiniExtension.java` → `VidocqExtension`, `ExtensionContext`, `VidocqConfiguration`
5. **`module-info.java`**: `requires fr.vidocq.chappe.api`, `io.vidocq.runtime.ext.chappe`, `io.vidocq.vauban.core`, `jakarta.cdi`
6. **`CassiniScopeBCE`**: CDI Build-Compatible extension — triggers the CDI coupling

### Chappe groupId inconsistency to clarify (see Q3)
- Vidocq POM uses `<groupId>fr.vidocq.chappe</groupId>`
- Chappe parent POM is `<groupId>io.vidocq.chappe</groupId>` (rename in progress)

---

## 1. Target architecture — Cassini standalone

### Repo layout `/Users/yblazart/projects/perso/vidocq/cassini`

```
cassini/
├── .forgejo/workflows/ci.yml         ← copied from chappe (build + SNAPSHOT deploy on each push)
├── .mvn/maven.config                 ← copied from chappe (preemptive auth)
├── .sdkmanrc                         ← java=25-tem, maven=3.9.16
├── .gitignore
├── LICENSE                           ← Apache 2.0
├── README.md                         ← overview + M2h/M2i roadmap
├── TCK.md                            ← 2535/2535 repro procedure + matrix
├── HOWTO-CLAUDE.md                   ← copied from vidocq, adapted for Cassini
├── run-official-tck-restful-4.0.sh   ← copied + adapted (new module path)
├── pom.xml                           ← parent reactor (Model 4.1.0)
│
├── cassini-api/                      ← public interfaces + HTTP SPI
│   └── pom.xml                       ← ONLY jakarta.ws.rs-api
│   └── src/main/java/io/vidocq/cassini/spi/http/
│       ├── CassiniHttpExchange.java
│       ├── CassiniHttpAdapter.java
│       ├── CassiniAsyncContext.java
│       └── CassiniStreamingSink.java
│   └── src/main/java/io/vidocq/cassini/spi/resource/
│       └── ResourceFactory.java      ← resource factory SPI (Mode A: default new(), Mode B: Vauban/CDI)
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.api
│
├── cassini-core/                     ← Invoker, scanner, built-in providers, RuntimeDelegate
│   └── pom.xml                       ← cassini-api + jakarta.ws.rs-api + JSON-B (Yasson) + JSON-P (Parsson)
│   └── src/main/java/io/vidocq/cassini/internal/...
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.core
│
├── cassini-cdi/                      ← optional CDI integration (Mode B)
│   └── pom.xml                       ← cassini-core + jakarta.cdi-api
│   └── src/main/java/io/vidocq/cassini/cdi/
│       ├── CdiResourceFactory.java     (ResourceFactory impl delegating to BeanManager)
│       └── CassiniScopeExtension.java  (BCE for @RequestScoped via the host container)
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.cdi
│
├── cassini-chappe/                   ← Chappe adapter (CassiniHttpAdapter via Chappe) — packaged within Cassini
│   └── pom.xml                       ← cassini-core + io.vidocq.chappe:chappe-api + chappe-core (runtime)
│   └── src/main/java/io/vidocq/cassini/chappe/
│       ├── ChappeHttpAdapter.java      (implements CassiniHttpAdapter)
│       └── ChappeHttpExchange.java     (implements CassiniHttpExchange via Chappe Request/Response)
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.chappe
│
├── cassini-jdk-http/                 ← native JDK adapter (`java.net.http.HttpServer`) for pure standalone
│   └── pom.xml                       ← cassini-core only (zero external dependency)
│   └── src/main/java/io/vidocq/cassini/jdkhttp/
│       ├── JdkHttpAdapter.java
│       └── JdkHttpExchange.java
│   └── src/main/java/module-info.java  → module io.vidocq.cassini.jdkhttp
│
└── cassini-tck/                      ← Arquillian runner + TckChallengeExclusions
    └── pom.xml                       ← standalone Model 4.0.0 (see ShrinkWrap note)
    └── src/main/java/io/vidocq/cassini/tck/
        ├── CassiniTestHarness.java
        ├── arquillian/...
    └── src/test/java/.../TckChallengeExclusions.java
    └── src/test/java/.../tck/...     (BasicAuthHandler, ContextProxies, container ext)
    └── depends on cassini-chappe in <scope>test</scope> (TCK transport = Chappe)
```

### Chappe decoupling — who hosts the adapter?
- **`cassini-api`** depends on **nothing** except `jakarta.ws.rs-api`.
- **Decision (Q5)**: Cassini ships **two adapters** in the repo:
  - `cassini-chappe`: Chappe adapter (reference transport, depends on `io.vidocq.chappe:chappe-core` at runtime). This is the transport used to pass the TCK.
  - `cassini-jdk-http`: native JDK adapter (`java.net.http.HttpServer`), zero external dependency — used for pure Mode A tests and as a reference fallback if Vauban needs a transport without a Chappe cycle.
- On the `vidocq` side, the `vidocq-runtime-cassini-rest-extension` becomes a lightweight aggregator that depends on `io.vidocq.cassini:cassini-chappe` + `cassini-cdi` and hosts `CassiniExtension` (`VidocqExtension`).

### Three certification modes
| Mode | Artifacts | Transport | TCK scope | DI | Status |
|------|-----------|-----------|-----------|----|--------|
| **A** Pure Cassini | `cassini-api` + `cassini-core` + `cassini-jdk-http` (or `cassini-chappe`) + `cassini-tck` | JDK HttpServer (default) or Chappe | REST 4.0 without CDI tests | none (`new()` factory) | **immediate target after extraction** |
| **B** Cassini + Vauban | + `cassini-cdi` + `vauban-core` + `cassini-chappe` | Chappe (Vauban needs virtual-thread-friendly transport) | full REST 4.0 | CDI via Vauban | post-extraction, main 2026 certification |
| **C** Vidocq Runtime | + JSON-P/B + MP extensions | Chappe | Core Profile 11 + MicroProfile | full CDI | long term |

---

## 2. HTTP SPI — interface contract (cassini-api)

### `CassiniHttpExchange`
```java
public interface CassiniHttpExchange {
    String method();
    URI requestUri();
    String requestUriRaw();        // do not lose the original encoding
    Map<String,List<String>> requestHeaders();
    InputStream requestBody();

    void setStatus(int code);
    Map<String,List<String>> responseHeaders();   // mutable, read just before flush
    OutputStream responseBody();

    SocketAddress remoteAddress();
    boolean isSecure();
    String authScheme();           // BASIC, etc., null if unauthenticated
    Principal userPrincipal();     // null if unauthenticated
}
```

### `CassiniHttpAdapter`
```java
public interface CassiniHttpAdapter {
    CompletionStage<Void> dispatch(CassiniHttpExchange exchange);
    // hookable: prefix routing, shutdown, lifecycle
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

### `ResourceFactory` (complementary SPI — Mode A vs B)
```java
public interface ResourceFactory {
    <T> T create(Class<T> resourceClass);
    void destroy(Object resource);
    static ResourceFactory defaultFactory() { /* new instance per call, no injection */ }
}
```

---

## 3. Roadmap — sequential execution (proposed)

### Phase 0 — Repo bootstrap (≈ 0.5 day)
- [ ] `git init` in `/Users/yblazart/projects/perso/vidocq/cassini`
- [ ] Copy `.sdkmanrc` (java=25-tem, maven=3.9.16), `.mvn/maven.config`, `.forgejo/workflows/ci.yml` from chappe (adapt the `MAVEN_DEPLOY_TOKEN` secret)
- [ ] Apache 2.0 `LICENSE` (consistent with vidocq/chappe)
- [ ] `.gitignore` (copied from chappe)
- [ ] Parent `pom.xml` (Model 4.0.0, groupId `io.vidocq.cassini`, artifactId `cassini-parent`, version `0.1.0-SNAPSHOT`, packaging `pom`, distributionManagement `repo.vidocq.dev`, `<subprojects>` × 4)
- [ ] `README.md`: overview + M2h/M2i roadmap + 3 certification modes + test matrix
- [ ] First commit: `chore(init): bootstrap cassini standalone repo`

### Phase 1 — Skeleton of the 6 modules (≈ 0.5 day)
- [ ] `cassini-api/pom.xml`: zero dependency outside `jakarta.ws.rs-api:4.0.0` + `jakarta.annotation-api`
- [ ] `cassini-core/pom.xml`: `cassini-api` + jakarta.ws.rs-api + Yasson + Parsson + (test) JUnit 6
- [ ] `cassini-cdi/pom.xml`: `cassini-core` + `jakarta.cdi-api` (provided)
- [ ] `cassini-chappe/pom.xml`: `cassini-core` + `io.vidocq.chappe:chappe-api` + `io.vidocq.chappe:chappe-core`
- [ ] `cassini-jdk-http/pom.xml`: `cassini-core` only
- [ ] `cassini-tck/pom.xml`: standalone Model 4.0.0 (see ShrinkWrap), reuse the existing POM (profile `tck-official`)
- [ ] `module-info.java` × 5 (api/core/cdi/chappe/jdk-http) with public SPI exports, internal opens for JAX-RS reflection
- [ ] Empty build: `mvn install` must pass (no code yet)

### Phase 2 — HTTP SPI & ResourceFactory (≈ 1 day)
- [ ] Write the 4 HTTP SPI interfaces + `ResourceFactory` in `cassini-api`
- [ ] `ResourceFactory.defaultFactory()`: `new instance per call`, no injection
- [ ] Basic unit tests: a `MockExchange` and a `MockAdapter` writing to a `ByteArrayOutputStream`
- [ ] Document the semantic stability of the SPI in `cassini-api/README.md` (changes ≠ breaking → major bump)

### Phase 3 — cassini-core migration (≈ 2–3 days)
Sub-step A: physical move
- [ ] `git mv` the ~38 `internal/*` files from `vidocq-runtime-cassini-rest-extension/src/main/java/io/vidocq/runtime/ext/rest/cassini/internal/` → `cassini-core/src/main/java/io/vidocq/cassini/internal/`
- [ ] Rename the root package `io.vidocq.runtime.ext.rest.cassini` → `io.vidocq.cassini` (IDE refactor or `sed` across all `.java`)

Sub-step B: Chappe decoupling
- [ ] Replace every `import fr.vidocq.chappe.api.Request` with use of `CassiniHttpExchange`
- [ ] `Invoker`: `dispatch(CassiniHttpExchange)` instead of `(Request, Response)`. Internal return `CompletionStage<Void>` (completed immediately → current synchronous behavior unchanged).
- [ ] Isolate `awaitBlocking(CompletionStage)` (see current line 741), mark it `// TODO(M2h): remove, propagate all the way to transport`
- [ ] `CassiniRequest`: audit `ThreadLocal`s (especially `PENDING_VARY`) → `// TODO(M2h): RequestContext portable virtual-thread-safe`
- [ ] Adapt `ParamExtractor`, `FieldInjector`, `CassiniHttpHeaders`, etc. to the `CassiniHttpExchange` interface
- [ ] `CassiniSseEventSink`: abstraction to `CassiniStreamingSink` (default implementation remains buffered as today)
- [ ] **No new runtime dependency**, **no new public JAX-RS class exposed**

Sub-step C: remove CDI from cassini-core
- [ ] `CassiniExtension` (the Vidocq/Vauban extension) **DOES NOT move into cassini-core** — it stays on the vidocq side (see Phase 5)
- [ ] `CassiniScopeBCE` (CDI Build-Compatible extension) → move to `cassini-cdi` (Phase 4)
- [ ] `cassini-core` no longer `requires` `jakarta.cdi`
- [ ] Any `RequestContext` (Vauban) reference → migrate to an internal `cassini-core` abstraction (e.g. `CassiniRequestContext`)

Sub-step D: tests
- [ ] Reuse the existing unit tests (`UriTemplateTest`, `MediaTypesTest`, `FormDecoderTest`, `ParamValueConverterTest`, `UriRouterBestMatchTest`)
- [ ] Adapt `CassiniEndToEndTest` to use a `MockHttpAdapter` instead of a real Chappe server
- [ ] `mvn install` must produce `cassini-core-0.1.0-SNAPSHOT.jar` with green unit tests

### Phase 4 — cassini-cdi (≈ 1 day)
- [ ] `CdiResourceFactory implements ResourceFactory` → delegates to `BeanManager`
- [ ] Move `CassiniScopeBCE` from vidocq + adapt it so it no longer depends on Cassini internal classes
- [ ] `module-info.java`: `requires io.vidocq.cassini.core` + `requires jakarta.cdi`
- [ ] ServiceLoader `provides`: `io.vidocq.cassini.spi.resource.ResourceFactory with CdiResourceFactory`
- [ ] Minimal test: 1 `@RequestScoped` resource with a mock `BeanManager` (Weld-SE optional)

### Phase 5 — Chappe + JDK adapters (≈ 1–2 days)
**In the Cassini repo**:
- [ ] Module `cassini-chappe`: implements `CassiniHttpAdapter` + `CassiniHttpExchange` from Chappe `Request`/`Response` (reuses the current `CassiniRestBridge` logic)
- [ ] Module `cassini-jdk-http`: implements the same interfaces on `com.sun.net.httpserver.HttpServer` (pure JDK). Serves as the "Mode A" transport and safety net if Vauban has a Chappe cycle.
- [ ] Basic unit tests: HTTP ping/echo through each adapter

**On the `vidocq` side**:
- [ ] `vidocq-runtime-cassini-rest-extension` becomes a thin aggregator:
   - depends on `io.vidocq.cassini:cassini-chappe` (pulls `cassini-core` transitively)
   - depends on `io.vidocq.cassini:cassini-cdi`
   - hosts `CassiniExtension implements VidocqExtension` (the `VidocqExtension` stays on the vidocq side, see Q12)
- [ ] Update the vidocq parent `<dependencyManagement>` to reference `io.vidocq.cassini:*:0.1.0-SNAPSHOT`

### Phase 6 — cassini-tck migration (≈ 1–2 days)
- [ ] `git mv` from `vidocq-runtime-rest-cassini-tck-runner/` → `cassini-tck/`
- [ ] Rename package `io.vidocq.runtime.ext.rest.cassini.tck.*` → `io.vidocq.cassini.tck.*`
- [ ] **TCK adapter decision** (Q5 settled): `cassini-tck` depends on `cassini-chappe` in `<scope>test</scope>` → Chappe is the transport used to run the official TCK. `cassini-jdk-http` remains available as a Mode A fallback.
- [ ] Keep `TckChallengeExclusions` unchanged (6 challenges)
- [ ] Keep the `tck-official` profile (Jersey CLIENT, JSON-B CLIENT, multipart, sigtest, etc.)
- [ ] Add empty JUnit tags `@Tag("async")` and `@Tag("sse-streaming")` in `TckChallengeExclusions` or via an `@Tags` configuration → current `excludedGroups` remain `servlet,xml_binding`, but the M2h tags are already prepared
- [ ] Enable in CI (optional secondary run) a `mvn -Ptck-official-async-preview verify` job that only **lists** the M2h tests (green assert on the current quorum)

### Phase 7 — 2535/2535 validation (≈ 0.5 day)
- [ ] `./run-official-tck-restful-4.0.sh all` from the Cassini repo
- [ ] Expected result: `Tests run: 2670, Failures: 0, Errors: 0, Skipped: 135`
- [ ] **Extraction is not complete until this score is reproduced in the Cassini repo** (see constraint 4)
- [ ] Update Cassini `TCK.md` with:
  - repro procedure from Cassini
  - tag matrix (servlet, xml_binding, async, sse-streaming)
  - 6 challenges + link to `TckChallengeExclusions`

### Phase 8 — Vidocq reintegration (≈ 0.5 day)
- [ ] Push initial Cassini SNAPSHOT to `repo.vidocq.dev/snapshots`
- [ ] In vidocq: replace the internal dependency `vidocq-runtime-cassini-rest-extension` (embedded Cassini) with `io.vidocq.cassini:cassini-core` + `:cassini-cdi` + local adapter
- [ ] `mvn -DskipTests install` of the vidocq reactor must pass
- [ ] Run the TCK from vidocq to validate no regression: still 2535/2535
- [ ] Cassini git tag: `v0.1.0-extraction` (SNAPSHOT) — no public release yet

### Phase 9 — Documentation & roadmap (≈ 0.5 day)
- [ ] Cassini `README.md`:
  - Overview
  - Quickstart (Mode A, example `Cassini.builder().http(myAdapter).register(MyResource.class).start()`)
  - **Roadmap** section: M2h (≈ 8–13 days) then M2i (≈ 1–2 days)
  - TCK status: 2535/2535 on Core Profile / SE-Bootstrap
- [ ] Cassini `TCK.md`: copied from vidocq, updated
- [ ] `HOWTO-CLAUDE.md`: adapted to the Cassini repo

---

## 4. Constraints (reminder — from the user brief)

1. **No direct `fr.vidocq.chappe.*` imports in Cassini** → everything goes through `cassini-api`
2. **Split into submodules immediately during extraction** → `api`, `core`, `cdi`, `tck`
3. **Preserve async invariants right now**:
   - Invoker returns internal `CompletionStage<Void>` (completed immediately)
   - `awaitBlocking()` isolated and marked `TODO(M2h)`
   - ThreadLocals audited and marked
4. **Freeze the TCK as the contract**: 2535/2535 in Cassini after extraction
5. **Preserve the M2h test matrix**: `async` + `sse-streaming` tags created
6. **Explicit roadmap** in Cassini `README.md`: M2h (8–13 days) + M2i (1–2 days)
7. **Naming/groupId fixed**: `io.vidocq.cassini`, modules `io.vidocq.cassini.{api,core,cdi,tck}`
8. **Do NOT**: do M2h in parallel, change TCK coverage, add new runtime dependency, break the public JAX-RS API

---

## 5. Decisions made (2026-04-28)

| # | Question | Decision |
|---|----------|----------|
| Q1 | Git repo | **`forge.vidocq.dev/vidocq/cassini`** (Forgejo, consistent with chappe) |
| Q2 | License | **Apache 2.0** |
| Q3 | Chappe groupId | **`io.vidocq.chappe`** (the chappe parent POM is canonical; vidocq will be updated to align) |
| Q4 | Cassini-core unit-test transport | **Chappe** by default. `cassini-jdk-http` available if decoupling is needed |
| Q5 | TCK adapter | **Chappe in `<scope>test</scope>`** in `cassini-tck` |
| Q6 | `CassiniRuntimeDelegate` | **stays in `cassini-core`** (the system property `-Djakarta.ws.rs.ext.RuntimeDelegate` continues to force selection against Jersey) |
| Q7 | Forgejo CI | **deploy SNAPSHOT on every push** |
| Q8 | Source migration | **`git filter-repo`** to preserve the granular history of the 45 Cassini files |
| Q10 | Versioning | **keep version `0.1.0-SNAPSHOT`** aligned with vidocq/chappe |
| Q11 | CDI reference for tests | **Vauban**. If Vauban→Chappe creates a cycle, switch those tests to `cassini-jdk-http` |
| Q12 | `CassiniExtension` (`VidocqExtension`) | **stays in vidocq**. `CassiniRestBridge` becomes `cassini-chappe`/`ChappeHttpAdapter` |
| Q13 | JUnit | **JUnit 6** (BOM `6.0.3`) |
| Q14 | Maven naming | **`cassini-api`, `cassini-core`, `cassini-cdi`, `cassini-chappe`, `cassini-jdk-http`, `cassini-tck`** |

### Remaining questions

- **Q9**: Does Cassini integrate `chappe-bench` into CI for perf regression checks? (default: no, can be enabled later)

---

## 6. Preliminary tasks identified (TaskList)

- [ ] T1 — Bootstrap Cassini repo (.sdkmanrc, .mvn, .forgejo, LICENSE, README, parent pom)
- [ ] T2 — Create 6 empty modules (api/core/cdi/chappe/jdk-http/tck) with their module-info
- [ ] T3 — Write the 4 HTTP SPIs + ResourceFactory in cassini-api
- [ ] T4 — Migrate ~38 cassini-internal files via `git filter-repo` to cassini-core, rename package `io.vidocq.cassini.*`, decouple Chappe
- [ ] T5 — Migrate CassiniScopeBCE + write CdiResourceFactory in cassini-cdi
- [ ] T6 — Write `cassini-chappe` (ChappeHttpAdapter) and `cassini-jdk-http` (JdkHttpAdapter)
- [ ] T7 — Migrate cassini-tck-runner → cassini-tck (Chappe adapter in test-scope), add @async/@sse-streaming tags
- [ ] T8 — Preserve async invariants (internal CompletionStage, isolated awaitBlocking, TODO M2h)
- [ ] T9 — Run TCK from Cassini: 2535/2535 ✅
- [ ] T10 — Update vidocq to consume Cassini SNAPSHOT (groupId alignment `io.vidocq.chappe`), validate no regression
- [ ] T11 — Final documentation (README + TCK.md + roadmap)

**Total estimate**: 8–12 days of development.

---

## 6.bis — Audit of Chappe/Vauban/vidocq-spi coupling (post-import)

**State after import via `git filter-repo`** (commit `758c0ec`):

### Files to decouple from `fr.vidocq.chappe.*` (8 files, ~50 usages)

| File | Imports | Action |
|------|---------|--------|
| `internal/CassiniRestBridge.java` | `Body, Handler, Request, Response, StatusCode` + `vauban.RequestContext` | **MOVE → cassini-chappe** as `ChappeHttpAdapter implements CassiniHttpAdapter` (this is the native Chappe adapter) |
| `internal/Invoker.java` | `Body, Request, Response, StatusCode` | **REFACTOR** → use `CassiniHttpExchange` instead of Request/Response. Signature change: `dispatch(CassiniHttpExchange) → CompletionStage<Void>` |
| `internal/ParamExtractor.java` | `Request` | **REFACTOR** → read headers/body via `CassiniHttpExchange` |
| `internal/FieldInjector.java` | `Request` | **REFACTOR** → same |
| `internal/filter/CassiniRequestContext.java` | `Request` | **REFACTOR** → same |
| `internal/context/CassiniRequest.java` | `Request` | **REFACTOR** → wrap `CassiniHttpExchange` instead of Chappe Request |
| `internal/context/CassiniHttpHeaders.java` | `Request` | **REFACTOR** → same |
| `internal/context/CassiniUriInfo.java` | `Request` | **REFACTOR** → same |
| `internal/context/CassiniSecurityContext.java` | `Request` | **REFACTOR** → same |

### Files to decouple from `io.vidocq.vauban.*` (2 files)

| File | Imports | Action |
|------|---------|--------|
| `internal/CassiniRestBridge.java` | `vauban.core.context.RequestContext` | Disappears with the move to cassini-chappe |
| `CassiniExtension.java` | `vauban.core.container.VaubanContainerBuilder` | **MOVE OUT → vidocq** |

### Files to decouple from `io.vidocq.runtime.*` (1 file)

| File | Imports | Action |
|------|---------|--------|
| `CassiniExtension.java` | `runtime.ext.chappe.{ChappeListener, ChappeMountPoint}`, `runtime.spi.{ExtensionContext, VidocqConfiguration, VidocqExtension}` | **MOVE OUT → vidocq-runtime-cassini-rest-extension** (see Q12 — `CassiniExtension` stays on the vidocq side) |

### TCK files to decouple (3 files)

| File | Action |
|------|--------|
| `cassini-tck/src/test/java/.../arquillian/VidocqCassiniDeployableContainer.java` | **REFACTOR** → use `cassini-chappe` directly, not `vidocq-runtime-chappe-extension` |
| `cassini-tck/src/test/java/.../arquillian/BasicAuthHandler.java` | **REFACTOR** → same |
| `cassini-tck/src/main/java/.../CassiniTestHarness.java` | **REFACTOR** → same |

### Decoupling strategy (proposed order)

1. **Phase 3a** — ✅ Move `CassiniExtension` out of Cassini (to vidocq). `CassiniScopeBCE` → `cassini-cdi/CassiniScopeExtension`.
2. **Phase 3b** — ✅ Refactor `CassiniRequest`, `CassiniHttpHeaders`, `CassiniUriInfo`, `CassiniSecurityContext` to wrap `CassiniHttpExchange`. `FieldInjector` (90% migrated).
3. **Phase 3c** — 🚧 Refactor `Invoker` + `ParamExtractor` + `CassiniRequestContext` to use `CassiniHttpExchange`.
4. **Phase 3c-bis** — 🚧 **POST-IMPORT DISCOVERY**: 4 files depend on CDI `BeanManager` (not just Chappe):
   - `Invoker.java` — resolves resources + providers via `BeanManager`
   - `ResourceScanner.java` — discovers `@Path` beans via `BeanManager`
   - `ExceptionMapperRegistry.java` — resolves providers via `BeanManager`
   - `FilterRegistry.java` — CDI filters

   **Implication**: `cassini-core` cannot be shipped without CDI unless we broaden the `ResourceFactory` SPI into a more complete `BeanRegistry`:
   ```java
   public interface BeanRegistry {
       <T> T resolve(Class<T> type);
       <T> List<T> resolveAll(Class<T> type);    // for providers/filters
       <T> List<Class<? extends T>> discover(Class<? extends Annotation> ann);  // for @Path
   }
   ```
   `cassini-core` uses `BeanRegistry` (SPI in `cassini-api`). `cassini-cdi` provides `CdiBeanRegistry` delegating to `BeanManager`. Mode A provides `ServiceLoaderBeanRegistry` or a manual registry via builder.

5. **Phase 3d** — Move `CassiniRestBridge` → `cassini-chappe/ChappeHttpAdapter.java` (impl `CassiniHttpAdapter` on the Chappe side). Becomes the only place where Chappe is touched on the Cassini side.
6. **Phase 3e** — Refactor the TCK runner (3 Arquillian files) to drive Cassini via `cassini-chappe` + `cassini-cdi` (Mode B).
7. **Phase 3f** — Validation: `mvn install` on the reactor + `./run-official-tck-restful-4.0.sh all` = 2535/2535.

### Revised estimate
- Phase 3a–3b: ✅ done (~2–3 h)
- Phase 3c + 3c-bis: ~2–3 days (BeanManager → BeanRegistry refactor on 4 files, including Invoker ~800 lines)
- Phase 3d: ~1 day (move CassiniRestBridge → cassini-chappe + `CassiniHttpExchange` adapter)
- Phase 3e–3f: ~1–2 days

**Revised total**: 4–6 days (vs initial estimate 2–3 days) for decoupled cassini-core + TCK 2535/2535.

### Precise post-Phase 3c state (scaffolding checkpoint)

**Pragmatic decision**: `cassini-core` accepts CDI in `<scope>provided</scope>` + `requires static jakarta.cdi`. Pure Mode A (without CDI) will be factored later via a `BeanRegistry` SPI. This decision makes it possible to compile quickly and pass the TCK faster.

**Scaffolding created**:
- `cassini-core/internal/transport/CassiniHttpResponse.java` — neutral record (status, headers, body bytes) + `writeTo(CassiniHttpExchange)`. Serves as the response IR (intermediate representation), transport-independent.
- module-info exports `internal.transport` to transport modules (chappe, jdk-http) and tck.

**Remaining mechanical refactors for `Invoker.java` (1292 lines)**:
1. Imports: remove `fr.vidocq.chappe.api.{Body, Request, Response, StatusCode}`, add `CassiniHttpExchange` + `CassiniHttpResponse`
2. Signatures: `Request request → CassiniHttpExchange exchange` (~10 methods: `invoke`, `invokeDynamicLocator`, `dispatchOnInstance`, `invokeDynamicLocatorWithInstance`, `invokeFinalOnInstance`, `runPreMatching`, `invokeInternal`, `injectProviderContexts`, `hasRequestBody`, `readEntity`, `pickBestMatch`, `renderThrowable`)
3. Returns: `Response → CassiniHttpResponse` (~50 occurrences). `Response.builder().status(StatusCode.X).header(...).body(Body.of(...)).build()` → `CassiniHttpResponse.builder().status(X).header(...).body(bytes).build()`
4. Reads: `request.headers().firstOrNull(X)` → `exchange.firstHeader(X)`; `request.body().asInputStream()` → `exchange.requestBody()`; etc.

**ParamExtractor.java (543 lines)**: same mechanical patterns as FieldInjector.

**CassiniRequestContext.java filter (243 lines)**: wrap `CassiniHttpExchange` instead of Chappe Request.

**CassiniRestBridge.java (158 lines)**: entirely moved to `cassini-chappe/ChappeHttpAdapter.java`. Bridges Chappe `Request/Response` ↔ `CassiniHttpExchange` + `CassiniHttpResponse`. Routing/filter/error-handling code stays in Invoker (moved there).

**Remaining estimate**: ~1 day (mechanical refactor Invoker + ParamExtractor + CassiniRequestContext) + ~0.5 day (write cassini-chappe ChappeHttpAdapter) + ~0.5 day (adapt cassini-jdk-http) + ~0.5 day (TCK runner refactor) + ~0.5 day (2535/2535 validation).

---

## 7. Notes & risks

- **ShrinkWrap resolver**: `cassini-tck` must stay in POM Model 4.0.0 (see the note in the existing pom) — Maven 4.1 / ShrinkWrap 1.2.x do not speak the same language.
- **Automatic vs explicit Java module**: all Cassini modules must have an explicit `module-info.java` (consistent with chappe and vidocq).
- **ServiceLoader for `RuntimeDelegate`**: if the CLIENT TCK loads Jersey, discovery order may select Jersey instead of Cassini → the `-Djakarta.ws.rs.ext.RuntimeDelegate` property remains necessary (already in the `tck-official` profile).
- **`cassini-cdi` module and BCE**: Build-Compatible Extension requires `jakarta.cdi-api` 4.1+; to be validated.
- **Cassini unit tests without a real server**: `cassini-core` must be able to build/run its tests **without Chappe or JDK HttpServer** → a lightweight `MockHttpAdapter` in `src/test/java` is required (Mode A target).

---

*Working document — to be amended after answers to questions Q1–Q14.*
