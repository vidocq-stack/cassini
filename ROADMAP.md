# Cassini — Roadmap

Jakarta RESTful Web Services 4.0 (JAX-RS) implementation — transport-agnostic,
zero dependency outside Jakarta specs. Pluggable transport adapters (chappe,
jdk-http). Optional DI via `BeanProvider` SPI (Vauban adapter provided).

> Product overview: see [`README.md`](README.md). Technical details: [`CLAUDE.md`](CLAUDE.md).

## Jakarta REST 4.0 TCK status

| Metric | Value |
|---|---|
| Target profile | **Core Profile / SE-Bootstrap** (standalone, no Servlet or JAXB server-side) |
| TCK | `jakarta.ws.rs:jakarta-restful-ws-tck:4.0.1` |
| JDK | Temurin 25 |
| TCK `@Test` tests | **2670** |
| Tests applicable to profile | **2535** |
| **PASS** | **2535** (100 %) |
| Failures + Errors | **0** |
| Skipped | **135** (134 out-of-profile tags + 6 challenges + 1 internal exemption) |

✅ **Conformant** on the Core Profile / SE-Bootstrap profile. Details on exclusions
and official challenges: see [`TCK.md`](TCK.md).

## Delivered phases

### M1 — Foundations ✅
- Public API `cassini-api`: `CassiniHttpExchange`, `CassiniHttpAdapter`, `ResourceFactory`, `BeanProvider`
- `cassini-core` implementation: Invoker, ResourceScanner, MessageBodyRegistry, RuntimeDelegate
- Routing via annotations `@Path`/`@GET`/`@POST`/etc., parameters `@PathParam`/`@QueryParam`/`@HeaderParam`/`@FormParam`/`@MatrixParam`
- Sub-resources, sub-resource locators, content negotiation `@Produces`/`@Consumes`

### M2 — Transport + adapters ✅
- `cassini-chappe` adapter (reference transport, used for the TCK)
- `cassini-jdk-http` adapter (zero external dep, `com.sun.net.httpserver`)
- `cassini-cdi-vauban` adapter (optional DI via Vauban)

### M2a — Exception mapping + filters ✅
- `ExceptionMapper`, `ContainerRequestFilter`, `ContainerResponseFilter`
- `NameBinding` + filter ordering respected

### M2b — MessageBody providers ✅
- Built-in: `String`, `byte[]`, `InputStream`, `Reader`, `Form`, `MultivaluedMap`, `File`
- Discovery via `@Provider` + ServiceLoader for third-party extensions

### M2c — UriInfo + Links ✅
- `UriBuilder`, `Link`, `Link.Builder`, `Link:` headers

### M2d — Client API _(in progress — triggered by humboldt M7c.6 on 2026-05-23)_

> **Historical note**: this milestone was mistakenly marked ✅ in a previous version
> of the roadmap. The Client API code was never implemented; the Jakarta REST 4.0 TCK
> was passing at 2535/2535 thanks to Jersey being used as the client
> in `cassini-tck` (cf. `CassiniMultipartAutoDiscover.java`). Implementation
> starts today to unblock the humboldt MP Telemetry 2.1 TCK, which requires
> a `jakarta.ws.rs.client.ClientBuilder` provider on the classpath.

- [ ] `cassini-client` (new module) — `java.net.http.HttpClient` backend + virtual threads
- [ ] Synchronous `Client`, `WebTarget`, `Invocation.Builder`, `Invocation` (GET/POST/PUT/DELETE)
- [ ] Client filters (`ClientRequestFilter`/`ClientResponseFilter`) — prepares humboldt M7c.12
- [ ] Request/response body serialization via `MessageBodyRegistry` (reuses cassini-core builtins)
- [ ] Discovery via `META-INF/services/jakarta.ws.rs.client.ClientBuilder` + Java Modules `provides`
- [ ] E2E tests with ephemeral in-process `com.sun.net.httpserver.HttpServer`
- [ ] Validation: replace Jersey with cassini-client in cassini-tck (gate: 2535/2535 PASS preserved)
- [ ] Async (`InvocationCallback`, `CompletionStage`) deferred to M2d.2 if not required by TCK consumers

### M2e — Validation + Bean Validation bridge ✅
- `@Valid` on resources, returns 400/422 with messages

### M2f — SSE base ✅
- `Sse`, `SseEventSource`, `SseBroadcaster`, `OutboundSseEvent`
- Spec-conformant API, real streaming to be finalized (M2i)

### M2g — Minimal multipart ✅
- `multipart/form-data` support for resources that declare it

## Phases in progress / upcoming

### M2h — Non-blocking async + virtual threads

**Status**: invariants prepared, `@Tag("async")` tests disabled pending this work.

- [ ] `@Suspended AsyncResponse` propagated to transport without blocking
- [ ] `CompletionStage<Response>` resource methods, lifecycle callbacks
- [ ] Validation: no pinning on virtual threads, `ScopedValue` for request context
- [ ] Enable `@Tag("async")` TCK tests + custom regression suite

Files affected:
- `cassini-core/src/main/java/io/vidocq/cassini/internal/Async.java`
- `cassini-core/src/main/java/io/vidocq/cassini/spi/CassiniAsyncContext.java`

### M2i — Real SSE streaming

**Depends on M2h** (asynchronous push on the transport side).

- [ ] Refactor `CassiniSseEventSink` for incremental chunked push (no full in-memory buffering)
- [ ] Configurable heartbeat (HTTP/1.1 keep-alive)
- [ ] Tests: SSE client consumes events incrementally, clean disconnection

### M3 — Vidocq extensions ✅ (delivered in the runtime)
- `vidocq-runtime-cassini-rest-extension` extension loaded via ServiceLoader
- See [vidocq runtime ROADMAP](../vidocq/ROADMAP.md)

### M4 — Performance & footprint (in progress)

#### M4 P0+P1a — InjectionSupport facade + runtime adapters (Class-File API) ✅
- `InjectionSupport` SPI facade in `cassini-api/spi/gen`
- `ResourceAdapter` SPI interface
- `AdapterRegistry` dual-path registry + reflective fallback
- `RuntimeAdapterGenerator` (Class-File API JEP 484) — generates adapters on the fly,
  VarHandle constants for private fields, eliminates `Method.invoke` and per-request
  scanning — primary gain on the hot path.

#### M4 P1b — Direct method dispatch ✅
- `invoke(int methodId, Object target, Object[] args)` — direct switch/tableswitch,
  no more `Method.invoke` on the hot path.

#### M4 P2 — APT processor (`cassini-processor`) ✅
- `CassiniResourceProcessor` generates adapters at compile time for source classes
  in the current build; `AdapterRegistry` prefers the APT adapter (AOT-ready).

#### M4 P3 — Maven plugin (`cassini-maven-plugin`) ✅
- `cassini-maven-plugin:generate` (phase `process-classes`) pre-generates
  `$$CassiniAdapter` classes for pre-compiled `.class` files arriving in
  external archives (dependency JARs: TCK jar, legacy).
- Scopes: `project` (default) and `dependencies` (configurable includeArtifacts/excludeArtifacts).
- Java Modules named-module rule: fail-build with an actionable message if a named Java Modules JAR
  contains `@Path`/`@Provider` resources (option `repackageModularDependencies=true`
  to repackage the JAR with the woven adapters).
- Wired into `cassini-tck` for TCK jar classes — closes AOT coverage.
- `RuntimeAdapterGenerator.toBytecode(Class<?>) → byte[]` extracted (bytecode-only,
  without `defineClass`) — same logic as the runtime path, identical adapters.
- `AdapterRegistry.preGeneratedHits()`/`runtimeGeneratedHits()` counters for
  observability and tests.

#### M4 P4 — Edge cases + exemption doc ✅
- [x] `@BeanParam` via per-bean adapters: `InjectionSupportImpl.beanParam()` routes injection
  through the bean's adapter (generated in the bean's package via `privateLookupIn`) — eliminates
  `IllegalAccessException` cross-package on private bean fields.
- [x] Nested `@BeanParam`: natural recursion via `support.beanParam(nestedType)` from
  the outer bean's `injectFields`.
- [x] SENTINEL fallback preserved for non-generatable classes (private superclass, closed module) —
  `FieldInjector.inject` remains the safety net.
- [x] Residual reflection documented (assumed exemption): startup `ResourceScanner`, `newInstance()`
  instantiation, singleton provider injection, type coercion, dynamic locators (`Object`).
- [ ] Dynamic locator (`Object`) via adapter: runtime generation as soon as the class is known
  at the first call — not implemented (locators remain reflective; TCK stays at 2535/0/0 without this fix).

#### M4 gates reached
- [ ] JMH end-to-end benchmarks vs RestEasy/Jersey (`BENCH.md` entry)
- [ ] Hot-path allocation reduction (context reuse)
- [x] GraalVM native-image AOT: coverage ensured by APT + plugin; runtime generator
      remains JVM-only fallback

## Technical backlog

- [ ] `cassini-migration.md` updated with real-world app porting experience
- [ ] Enriched examples (`cassini-examples`): OAuth2 resource server, file upload
      streaming, custom validation
- [ ] Evaluate native HTTP/2 server-side support via chappe (linked resource push)

## Bugs

No dedicated `BUG.md` — open bugs are tracked directly in the commits
and the tests that cover them. Open a `BUG.md` if a non-trivial regression
appears.

## Tracking conventions

- **This roadmap**: M-x milestones, medium/long-term vision.
- **`TCK.md`**: conformance status, justified exclusions, official challenges.
- **`CLAUDE.md`**: code/architecture conventions for the AI session.
- **`cassini-migration.md`**: notes for porting apps from Jersey/RestEasy.
