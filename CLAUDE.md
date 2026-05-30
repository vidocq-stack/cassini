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

# TCK — full suite (2670 tests, expected: 2535 PASS / 135 SKIP / 0 ERR)
./run-official-tck-restful-4.0.sh all

# TCK — targeted test
./run-official-tck-restful-4.0.sh -Dtest=TestName
```

> `cassini-tck` is **outside the reactor** (standalone Model 4.0.0 pom.xml) to work around a ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 incompatibility. Do not change this model.

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

`AdapterRegistry.lookup` first tries `Class.forName(<class>$$CassiniAdapter)` (APT/plugin
path), then the runtime generator, then returns the SENTINEL (reflective fallback).

**Generated route table (M5b):** in addition to the adapter, each `@Path` class without a sub-resource
locator gets a `<Class>$$CassiniRoutes` (SPI `RouteProvider`) exposing the route table as
literals (`RouteDescriptor`). `RouteRegistry` tries `Class.forName(<class>$$CassiniRoutes)` then
converts each descriptor into a `ResourceMethod` via a targeted `getDeclaredMethod(...)` (no annotation
scan). Classes with locators set `hasLocators()` and fall back to
`ResourceScanner.discover`.

**JPMS named-module rule (plugin):** adapters live in the resource package.
Classpath JARs → write into `target/classes`. JPMS named-module → fail build (option
`repackageModularDependencies=true` to repackage the JAR).

**Documented residual reflection (accepted exception):**
- `ResourceScanner` at startup (JAX-RS annotation scan, only once).
- Resource and bean instantiation (`getDeclaredConstructor().newInstance()`) — one-time, outside the hot path.
- `InjectionSupportImpl.beanParam`: instantiation is still reflective; bean field injection goes through its generated adapter (P4), or reflective fallback if the adapter cannot be generated (closed module, private superclass).
- Runtime fallback (SENTINEL + `FieldInjector.inject` as a safety net) in two **irreducible by codegen** cases: (a) closed application module (no `opens`) → `privateLookupIn` fails; (b) **field whose TYPE is a package-private class from ANOTHER package** than the resource (e.g. TCK `ParamEntityWithConstructor` injected into `*.locator`/`*.sub`) → `findVarHandle` requires access to the field type, impossible by language rules even for an adapter generated in the resource package; only `Field.setAccessible(true)` works around it. These two cases are NOT bugs. (The M6d bug — array-type params `Annotation[]`/`byte[]` crashing generation via `ClassDesc.of(getName())` — is fixed: those provider classes are now generated.)
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

- **M2h** — Non-blocking async + virtual threads: `@Suspended AsyncResponse`, `CompletionStage` propagation to the transport, lifecycle callbacks. The async invariants are already prepared in `cassini-core/internal/Async.java` and `CassiniAsyncContext` (SPI). The `@Tag("async")` tests are disabled for now.
- **M2i** — Real SSE streaming: refactoring `CassiniSseEventSink` for progressive chunked push (depends on M2h).

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
