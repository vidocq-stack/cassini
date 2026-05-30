# HOWTO — Cassini with Claude Code

Quick documentation for future Claude Code sessions on this project.

## Context

Cassini is the **standalone Jakarta REST 4.0 implementation** extracted from
`vidocq` in April 2026. Its 6 modules:

| Module | Role |
|--------|------|
| `cassini-api` | HTTP SPI (CassiniHttpExchange/Adapter/AsyncContext/StreamingSink + ResourceFactory + BeanProvider) |
| `cassini-core` | REST 4.0 engine (Invoker, ResourceScanner, MessageBodyRegistry, built-in providers) |
| `cassini-cdi-vauban` | Vauban CDI adapter (Mode B) — VaubanBeanProvider via SPI BeanProvider + CassiniScopeExtension (BCE) |
| `cassini-chappe` | Chappe HTTP adapter (reference transport, used by the TCK) |
| `cassini-jdk-http` | Native JDK HTTP adapter (pure Mode A, zero external deps) |
| `cassini-tck` | Arquillian runner + official Jakarta REST 4.0 harness |

## Architecture

- **`cassini-core`**: zero dependency on Chappe, transport-agnostic code. Pluggable
  through the HTTP SPI of `cassini-api`.
- **CDI provided**: `cassini-core` accepts CDI in `<scope>provided</scope>` —
  `Invoker.forBeanManager(bm)` if the user provides one.
- **Transport decoupling**: All usages of Chappe are confined to `cassini-chappe`
  (`ChappeHttpAdapter` + `ChappeHttpExchange` + `ChappeRuntimeDelegate`).

## Essential commands

### Full reactor build
```bash
mvn install -DskipTests
```

Note: `cassini-tck` is intentionally **outside the reactor** (standalone Model 4.0.0)
to work around ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0.

### TCK
```bash
./run-official-tck-restful-4.0.sh           # smoke (CassiniHarnessSmokeTest)
./run-official-tck-restful-4.0.sh all       # 2670 tests, expected: 2535 PASS / 135 SKIP / 0 ERR
./run-official-tck-restful-4.0.sh -Dtest=X  # targeted
```

Prerequisite: `jakarta.ws.rs:jakarta-restful-ws-tck:4.0.1` installed in the local M2
(non-public — official Jakarta TCK).

### Unit tests
```bash
mvn test
```

## Conventions

- **Java 25** + **Maven 3.9.16** (see `.sdkmanrc`)
- **Explicit Java modules**: all modules have a `module-info.java`
- **Packages**:
  - `io.vidocq.cassini.spi.*` — public SPI (semantic stability)
  - `io.vidocq.cassini.internal.*` — internal (may break between versions)
- **Maven groupId**: `io.vidocq.cassini` (consistent with `io.vidocq.chappe`, `io.vidocq.vauban`)
- **License**: Apache 2.0
- **License headers**: not required in the MVP

## Things to watch for in Claude Code

1. **Never reintroduce an import of `fr.vidocq.chappe`** into `cassini-core` — the
   decoupling is an architectural constraint (see `cassini-migration.md` §4).

2. **Preserve async invariants (M2h)**: `Invoker.invoke()` returns a synchronous
   `CassiniHttpResponse` today; M2h will propagate `CompletionStage`
   without blocking (`awaitBlocking()` must be isolated).

3. **TCK 2535/2535 is a contract**: any change to `cassini-core` must
   preserve this score (run it before committing important changes).

4. **Module-info `provides RuntimeDelegate`**: only `cassini-chappe` (and later
   `cassini-jdk-http`) declares this service. `cassini-core` no longer exposes it to
   avoid ServiceLoader collisions.

5. **`cassini-tck/pom.xml` is in Model 4.0.0** — do not switch it to 4.1.0
   until ShrinkWrap is updated.

6. **MEMO Q3**: the canonical Chappe `groupId` is `io.vidocq.chappe` (not `fr.vidocq.chappe`).

## Roadmap

See [`README.md`](README.md) for M2h (async + virtual threads) and M2i (SSE streaming).
