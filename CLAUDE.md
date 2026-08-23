# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- The official `jakarta.ws.rs:jakarta-restful-ws-tck:4.0.1` TCK must be installed in the local M2 (non-public artifact)

## Essential commands

```bash
# Reactor build (without TCK)
./mvnw -ntp install -DskipTests

# Build with unit tests (cassini-core only)
mvn test

# TCK — smoke test only
./run-official-tck-restful-4.0.sh

# TCK — full suite (2670 tests, expected: 2538 PASS / 132 SKIP / 0 ERR)
./run-official-tck-restful-4.0.sh all

# TCK — targeted test
./run-official-tck-restful-4.0.sh -Dtest=TestName
```

> `cassini-tck` is **in-reactor, gated behind the `tck` Maven profile** (TCK harmonisation, same pattern as the vidocq-runtime-tck-* runners): a plain `mvn install` neither downloads nor runs anything TCK-related. The historical ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 constraint is obsolete since the Maven 3.9.16 / Model 4.0.0 migration.

## Architecture

Cassini is a **transport-agnostic** Jakarta RESTful Web Services 4.0 implementation (Core Profile / SE-Bootstrap).

```
cassini-api          ← public HTTP SPI + stable codegen SPI (ResourceAdapter, RouteProvider, InjectionSupport - spi.gen)
cassini-core         ← JAX-RS implementation + RuntimeAdapterGenerator (Class-File API) + AdapterRegistry + RouteRegistry
cassini-client       ← JAX-RS 4.0 client (zero-dep ClientBuilder on java.net.http + virtual threads)
cassini-processor    ← APT (javax.annotation.processing) — generates CassiniAdapter at compile time
cassini-maven-plugin ← Maven plugin — pre-generates CassiniAdapter for external archives (dep JARs)
cassini-cdi-vauban   ← Vauban CDI adapter (BeanProvider + BCE @RequestScoped, optional)
cassini-chappe       ← Chappe adapter (reference transport, used for the TCK)
cassini-jdk-http     ← Pure JDK adapter (com.sun.net.httpserver, zero external dependencies)
cassini-tck          ← Arquillian runner + official Jakarta REST 4.0 harness
```

**Request flow:** `CassiniHttpAdapter.dispatch()` → `Invoker` (core) → resource method → `CassiniHttpResponse` → transport.

### M4 codegen architecture

Three levels of adapter generation (`<Class>$$CassiniAdapter`), from most preferred to fallback:

1. **APT compile time (`cassini-processor`)** — source classes from the current build. AOT-safe.
2. **Maven plugin build time (`cassini-maven-plugin:generate`, `process-classes`)** — external
   archives (dep JARs). Calls `RuntimeAdapterGenerator.toBytecode(cls)` and writes the `.class`
   files to disk. AOT-safe.
3. **Runtime generator (`RuntimeAdapterGenerator.generate`)** — JVM-only fallback. Not AOT-compatible.

`AdapterRegistry.lookup` resolves in this order: (0) a **`ServiceLoader`-registered adapter**
(module-path `provides io.vidocq.cassini.spi.gen.ResourceAdapter with <Class>$$CassiniAdapter`, or
classpath `META-INF/services`), keyed by `ResourceAdapter.resourceClass()`; (1) `Class.forName(<class>$$CassiniAdapter)`
(APT/plugin path); (2) the runtime generator; (3) the SENTINEL (reflective fallback). The
ServiceLoader step is what lets a strict Java Modules app keep its resource package **closed** (neither
`opens` nor `exports`): the module system instantiates the provider from the encapsulated package,
so cassini-core never reflects into it. That step also populates the per-class `methodId` map (via
`RuntimeAdapterGenerator.collectMethods`, public-method enumeration — no `setAccessible`), so
`adapter.invoke()` does the typed dispatch instead of a reflective `Method.invoke`. On the classpath
and for the TCK no provider is registered, so the map is empty and lookup falls through to
`Class.forName` exactly as before (zero behaviour change). cassini-core `uses` both SPIs.

> **Zero-export proof:** `cassini-examples-jdkhttp` (pure Mode A) keeps its `resource` package fully
> encapsulated — `java --describe-module` shows `contains …resource` (no `opens`, no `exports`), only
> `provides ResourceAdapter/RouteProvider with …$$CassiniAdapter/$$CassiniRoutes`. All HTTP dispatch
> tests pass on the module-path. (Mode-B Vauban apps additionally need Vauban to instantiate the
> normal-scoped client proxy in-module — the separate BCE-static-metadata chantier — before they can
> drop `exports` too.) Follow-up: `cassini-maven-plugin`/`RuntimeAdapterGenerator.toBytecode` do **not**
> yet emit `resourceClass()`, so plugin-pre-generated external module-path jars still need `Class.forName`
> (i.e. an `exports`); only APT-generated adapters are ServiceLoader-keyable today.

**Generated route table (M5b):** in addition to the adapter, each `@Path` class without a sub-resource
locator gets a `<Class>$$CassiniRoutes` (SPI `RouteProvider`) exposing the route table as
literals (`RouteDescriptor`). `RouteRegistry` resolves a provider the same way as `AdapterRegistry`
(ServiceLoader by `RouteProvider.resourceClass()` first, then `Class.forName(<class>$$CassiniRoutes)`),
then converts each descriptor into a `ResourceMethod` via a targeted `getDeclaredMethod(...)` (no
annotation scan; the descriptor `Method`'s `setAccessible` is best-effort — a closed package relies on
`adapter.invoke`). Classes with locators set `hasLocators()` and fall back to
`ResourceScanner.discover`.

**Java Modules named-module rule (plugin):** adapters live in the resource package.
Classpath JARs → write into `target/classes`. Java Modules named-module → fail build (option
`repackageModularDependencies=true` to repackage the JAR).

**Documented residual reflection (accepted exception):**
- `ResourceScanner` at startup (JAX-RS annotation scan, only once).
- Resource and bean instantiation (`getDeclaredConstructor().newInstance()`) — one-time, outside the hot path.
- `InjectionSupportImpl.beanParam`: instantiation is still reflective; bean field injection goes through its generated adapter (P4), or reflective fallback if the adapter cannot be generated (closed module, private superclass).
- Runtime fallback (SENTINEL + `FieldInjector.inject` as a safety net) in two cases: (a) closed application module that does **not** register its APT adapter as a `ServiceLoader` provider → cassini-core cannot reach `<Class>$$CassiniAdapter` (no `opens`/`exports`, no `provides`) and `privateLookupIn` fails. **Resolvable** by adding `provides ResourceAdapter with <Class>$$CassiniAdapter` (+ `RouteProvider`) to the app `module-info` — then the package stays closed and dispatch/injection run in-module (see `cassini-examples-jdkhttp`). (b) **field whose TYPE is a package-private class from ANOTHER package** than the resource (e.g. TCK `ParamEntityWithConstructor` injected into `*.locator`/`*.sub`) → `findVarHandle` requires access to the field type, impossible by language rules even for an adapter generated in the resource package; only `Field.setAccessible(true)` works around it — this one is genuinely **irreducible by codegen**. Neither case is a bug. (The M6d bug — array-type params `Annotation[]`/`byte[]` crashing generation via `ClassDesc.of(getName())` — is fixed: those provider classes are now generated.)
- `@Context` injection into singleton providers (filters, MBW/MBR) via `FieldInjector.inject` — outside the resource hot path.
- `@*Param` field coercion: **generated inline** by the three generators (runtime `RuntimeAdapterGenerator` = M6b, APT `cassini-processor` = M6c, plugin via `toBytecode`) for String/CharSequence, primitives+wrappers, enum (`fromString` or `Enum.valueOf`), public `valueOf`/`fromString`/ctor `(String)`, and `List`/`Set`/`SortedSet`/`Collection` collections of those types. Reflective fallback `support.param` (→ `ParamValueConverter`) only for non-inlineable forms: `PathSegment`, non-public types/members, raw collections.
- **Method parameter** coercion: still handled through `ParamExtractor` + `ParamValueConverter` (`ParamConverterProvider`-aware resolution on first call per type, not per request). **Intentionally not inlined** — PCP precedence is what broke 23 tests on the first P1b attempt.

**Two resource instantiation modes:**
- Mode A: `new()` via `ResourceFactory.defaultFactory()` (jdk-http, standalone)
- Mode B: DI through public SPI `BeanProvider` (`cassini-cdi-vauban` or any other ServiceLoader adapter). No `jakarta.cdi` import in `cassini-api`/`cassini-core` — strict decoupling.

**`RuntimeDelegate`:** declared only in `cassini-chappe` and `cassini-jdk-http` through ServiceLoader. `cassini-core` contains `CassiniRuntimeDelegate` but no longer exposes it to avoid collisions.

## Architectural constraints that must not be violated

1. **Zero `fr.vidocq.chappe` import in `cassini-core`** — transport decoupling is a fundamental constraint (see `cassini-migration.md`).
2. **Canonical Chappe groupId**: `io.vidocq.chappe` (not `fr.vidocq.chappe`).
3. **TCK 2535/2535 is a contract** — every modification to `cassini-core` must preserve this score; run the TCK before committing structural changes.
4. **`cassini-tck/pom.xml` stays on Model 4.0.0** — do not switch to 4.1.0 until ShrinkWrap is updated.

## Conventions

- **Explicit Java modules**: all modules have a `module-info.java`.
- **Packages**: `io.vidocq.cassini.spi.*` = stable public SPI; `io.vidocq.cassini.internal.*` = internal code (may break between versions).
- **Maven groupId**: `io.vidocq.cassini`.

## Current roadmap

- **M2h** — largely DONE (2026-06-11): cassini-core is ThreadLocal-free (`RequestScope` ScopedValue), `@Suspended AsyncResponse` + `CompletionStage` returns work (blocking-on-VT), CompletionCallback wired. Remaining: `CompletionStage` propagation to the transport (true non-blocking, needs a Chappe async SPI — see ASYNC.md) and ConnectionCallback disconnect notification.
- **M2i** — DONE (2026-06-11): real chunked SSE streaming on Chappe (lazy-commit latch + thread-agnostic chunk queue, see ASYNC.md). The 3 SSE TCK challenges are lifted (2538 PASS).

## Unit tests

**cassini-core**: `UriTemplateTest`, `UriRouterBestMatchTest`, `MediaTypesTest`, `FormDecoderTest`,
`ParamValueConverterTest`, `RuntimeAdapterGeneratorTest` (includes toBytecode P3),
`AdapterRegistrySeamTest`.

**cassini-processor**: `CassiniResourceProcessorTest`.

**cassini-maven-plugin**: `GenerateAdaptersMojoTest` (toBytecode round-trip, JAR named-module detection).

**cassini-client**: `ClientBuilderDiscoveryTest`, `BasicGetTest`, `FiltersTest`.

## Documented TCK challenges

6 tests disabled via `TckChallengeExclusions` with justification in `TCK.md`:
- 1 non-portable spec interpretation (`locatorNameTooLongAgainTest`)
- 1 TCK sigtest environment issue (`signatureTest`)
- 2 blocking multipart issues on the Jersey CLIENT side (not Cassini)
- 2 real SSE streaming cases (out of scope until M2i)

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
