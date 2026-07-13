# JSON-B / JSON-P — current state and in-house implementation roadmap

> **STATUS 2026-05-04 (final): RESOLVED.** Cassini now uses **Champollion**
> (`io.vidocq.champollion:{champollion-api,champollion-jsonp,champollion-jsonb}:0.1.0-SNAPSHOT`)
> instead of Yasson + Parsson. All documented friction points below are now addressed.
>
> - **JSON-P 2.1**: 178/179 PASS on the official TCK (99.4%).
> - **JSON-B 3.0**: **289/295 PASS (97.97%)**, zero functional FAIL — the only remaining
>   ERROR is `JSONBSigTest.signatureTest` (binary signature, `.sig` file not distributed in the TCK 3.0.0 ZIP). The 2 previous CDI ERRORs have been resolved (M7.16 spec §5 + M7.17 split creator/property — see TCK.md).
> - **Jakarta REST 4.0 TCK on Cassini with Champollion**: 2535/2670 PASS
>   (0 FAIL, 0 ERROR, 135 SKIP) — **nominal score preserved** after all Champollion changes (M7.16/M7.17 CDI, P6.1/P6.2 runtime perf, P10.1/P10.2 parser refactor, P9 parser pool).
> - **Champollion vs Yasson/Parsson/Jackson benchmark**: see
>   `champollion/BENCH.md`. Champollion is competitive with Yasson on binding
>   (~1× on write, +28% on read MEDIUM), 2.5–3× slower than Parsson on JSON-P streaming (P1/P2 optimization track open).
>
> The sections below are preserved as a **historical archive** of the motivations that led to Champollion. The two originally planned modules `cassini-jsonb` and `cassini-jsonp` are replaced by Champollion (factorization: reusable generic JSON implementation outside Cassini).

---

## History (before Champollion integration)

Cassini used Yasson 3.0.4 (JSON-B) + Parsson 1.1.7 (JSON-P) as runtime providers. Several frictions in strict Java Modules mode (jlink / jpackage / module-path) justified a **homegrown implementation** aligned with Cassini constraints.

This document records the known bugs/workarounds and the functional targets that guided Champollion.

---

## 1. Yasson + `record` + strict module-path bug (April 2026) — ✅ RESOLVED with Champollion

> Champollion resolves this bug natively: it never uses `setAccessible(true)`, resolves the record canonical constructor via `Class.getRecordComponents()` + `MethodHandles.publicLookup()`, and requires no `opens` on the consumer side. The `@JsonbCreator` factory workaround is no longer needed.

### Symptom

A JAX-RS resource that consumes/produces a `record` sees the `String` component (and possibly other references) come back `null` after a POST → GET round-trip, **only** when the app runs in strict module-path (jlink image or jpackage bundle). In classpath (`mvn exec:java`, `java -cp`) the same code works.

Reproduction: `vidocq-runtime-cassini-rest-example` (todo list) before the fix.

```java
public record Todo(long id, String title, boolean done) {}
```

```http
POST /api/todos {"title":"Pain","done":false}
→ 201 {"done":false,"id":1}                 (title lost, never serialized)
```

### Root cause

Yasson deserializes a record through the implicit canonical constructor
(`Todo(long, String, boolean)`). In strict module-path, resolving the canonical
constructor by reflection fails silently even with
`opens io.vidocq.runtime.examples.rest;` unconditional in module-info,
because:

- The canonical constructor of a record has no explicit `@JsonbCreator`;
- Yasson falls back to "no-arg + setters", which do not exist for a record;
- Result: all components keep their default value (`null` for `String`, `0` for primitives).

Yasson then serializes the object — `title=null` and the default Yasson config is `nillable=false`, so the field is omitted.

### Current workaround

Annotate a `static` factory with `@JsonbCreator`:

```java
public record Todo(long id, String title, boolean done) {
    @JsonbCreator
    public static Todo create(@JsonbProperty("id") long id,
                              @JsonbProperty("title") String title,
                              @JsonbProperty("done") boolean done) {
        return new Todo(id, title, done);
    }
}
```

Yasson resolves the factory by the `@JsonbProperty` names and invokes it as a standard public method — no extra reflection privileges required.

Implemented for `vidocq-runtime-cassini-rest-example/Todo.java` at commit `86934af`.

### Trade-offs

- **Pros**: minimal fix, no added dependency, works immediately in jlink/jpackage.
- **Cons**: boilerplate repeated on every record serialized via REST. Not discoverable — a developer forgets the annotation and the bug comes back silently (missing fields, no error).

---

## 2. Other Yasson limitations observed or anticipated

| # | Limitation | Impact | Workaround |
|---|------------|--------|------------|
| 2.1 | Records without `@JsonbCreator` broken in module-path | Blocking in production jlink | `@JsonbCreator` factory (see §1) |
| 2.2 | Reduced configurability via `JsonbConfig` (no global `@JsonbAdapter`, verbose date formats) | Friction for advanced usages | Adapter per field |
| 2.3 | `@Generated` BeanPropertyVisibility but no custom `JsonbVisibility` support | Limits project-level override | Subclass `JsonbAdapter` |
| 2.4 | Startup: ~80 ms of JNDI/CDI initialization on the first request | First-hit cost | Pre-warm at boot |

---

## 3. Target: in-house `cassini-jsonb` + `cassini-jsonp`

### Why

- **Records first-class**: no `@JsonbCreator` boilerplate required; the canonical constructor is resolved via `Class.getRecordComponents()` and does not require a specific `opens`.
- **Native module-path**: clean Java module, `provides jakarta.json.bind.spi.JsonbProvider with cassini.jsonb.CassiniJsonbProvider`.
- **Instant startup**: no JNDI/CDI scan at boot; binding by `Lookup` MethodHandle created on first use then cached.
- **Streaming-first**: for SSE and chunked bodies, read/write through `Flow.Publisher<ByteBuffer>` instead of a full `byte[]` in memory (see `Body.streaming()` on the Chappe side).
- **Zero unnecessary runtime reflection**: use `MethodHandles.Lookup` + `LambdaMetafactory` for record accessors and POJO setters. Reflection only at initial lookup.
- **Test-driven via TCK**: align with the Jakarta JSON-B 3.0 TCK to stay conformant.

### Scope

| Module | Role | Status |
|--------|------|--------|
| `cassini-jsonb` | Jakarta JSON-B 3.0 implementation | Backlog |
| `cassini-jsonp` | Jakarta JSON-P 2.1 implementation (JsonParser/JsonGenerator) | Backlog |

`cassini-jsonp` is useful because Yasson needs it (StAX-like API). If we build `cassini-jsonp` properly, it can be shared with other JSON-P consumers (structured logging, config, etc.).

### Intended attack plan

1. **`cassini-jsonp` first** (simpler, more contained).
   - Tokenizer + `JsonParser` (pull-based, streaming).
   - `JsonGenerator` (push-based, streaming, optional indentation).
   - `JsonObject`/`JsonArray` builders.
   - JSON-P 2.1 TCK → 100% target.

2. **`cassini-jsonb` next**, built on `cassini-jsonp`.
   - `RecordComponents` resolver via `MethodHandles`.
   - Built-in adapters: `Instant`, `LocalDate`, `LocalDateTime`, `UUID`,
     `Duration`, `Optional<T>`, collections, maps, enums, sealed types.
   - `@JsonbProperty`, `@JsonbDateFormat`, `@JsonbNumberFormat`, `@JsonbAdapter`,
     `@JsonbTransient`, `@JsonbCreator` (backward compatibility).
   - JSON-B 3.0 TCK → 100% target.

3. **Cassini wiring**: `MessageBodyRegistry` registers by default
   `CassiniJsonbReaderWriter`, delegating to `cassini-jsonb` instead of
   Yasson via `JsonbBuilder`. Yasson remains the fallback if present as a provider.

4. **`vidocq-runtime-cassini-rest-example` migration**: remove the `@JsonbCreator`
   factory from the `Todo` record once `cassini-jsonb` is enabled.

### Estimated effort

- `cassini-jsonp`: 2–3 weeks (parser + generator + builders + TCK).
- `cassini-jsonb`: 4–6 weeks (API richness + TCK conformance).

Total ~7 to 10 weeks to have a fully in-house JSON stack.

### Success criteria

- Records first-class without `@JsonbCreator`.
- JSON-P 2.1 and JSON-B 3.0 TCKs green at 100%.
- Startup: < 10 ms between Cassini boot and first serialization.
- Memory footprint < Yasson 3.0.4 (measured).
- Clean Java module: proper `provides`/`uses` + `module-info.java`.

---

## 4. Technical requirements that `cassini-jsonb` / `cassini-jsonp` MUST satisfy

These invariants come directly from the friction observed with Yasson + Parsson (see §1, §2). **Any violation of one of these points fails the design review.**

### 4.1 Strict module-path ready (top priority)

| # | Rule | Why | Implementation detail |
|---|-------|-----|-----------------------|
| **R-1** | **No `setAccessible(true)` call** in the runtime path. | Requires `opens` from the consumer; broke Yasson + records. | Everything goes through `MethodHandles.publicLookup()`. If the target is not public, either fail clearly at binding time (not runtime) or require a user `JsonbAdapter`. |
| **R-2** | **No `Class.getDeclaredConstructors()` / `getDeclaredMethods()` calls**. Prefer the `public*` variants. | Same issue: requires reflection privileges. | Public records and POJOs → `getRecordComponents()` + `getMethods()` are enough. For non-public types, require `@JsonbAdapter` or reject. |
| **R-3** | **No dependency on an `opens` on the consumer side**. An app whose `module-info.java` contains *no* `opens` must work. | The Yasson + record bug occurs exactly there. | Explicitly test with a runtime jlink test where the consumer module has no `opens`. CI mandatory. |
| **R-4** | **No use of `Lookup.privateLookupIn(...)`** on the consumer module. | This API requires targeted `opens to <module>`. | Only `MethodHandles.publicLookup()` is acceptable to cross module boundaries. |

### 4.2 Records — first-class

| # | Rule | Implementation detail |
|---|-------|-----------------------|
| **R-5** | The record **canonical constructor** is resolved via `Class.getRecordComponents()` + `MethodHandles.publicLookup().findConstructor(...)`. No `getDeclaredConstructor`. | The canonical constructor of a record is *always* `public` — it can be resolved via `publicLookup` without opens. |
| **R-6** | **Accessors** (`title()`, `id()`, `done()`) are resolved via `publicLookup().findVirtual(...)`. Cached through `ClassValue<RecordBinding>` on first use. | Record accessors are public by construction. One lookup amortized across all calls. |
| **R-7** | **No annotation required** on records for them to be serializable/deserializable. `@JsonbProperty` remains optional (field rename, alias). | This is what distinguishes it from Yasson 3.0.4 → remove the `@JsonbCreator` factory boilerplate. |
| **R-8** | `null` components serialized according to config (`nillable=true` by default on `cassini-jsonb`? To be discussed — Yasson uses `false`; for records the absence of a field has a different meaning than `null`). | Open decision (see §5). |
| **R-9** | Generic records (`record Pair<A, B>(A first, B second)`) supported via `Class.getRecordComponents()[i].getGenericType()`. | Preserve `TypeVariable` until final binding. |

### 4.3 JSON-P pull-based / push-based

| # | Rule | Implementation detail |
|---|-------|-----------------------|
| **R-10** | `JsonParser` reads character by character from an `InputStream` or `Reader`. **Never** `readAllBytes()`. | Critical for SSE / chunked / large payloads. Aligns with Chappe `Body.streaming()`. |
| **R-11** | `JsonGenerator` writes directly to the target `OutputStream`. No intermediate `StringBuilder`. | Same streaming goal. |
| **R-12** | A `JsonParser` must be seekable and navigable without loading everything into memory. Complete `JsonObject` only on explicit request (`getObject()`). | Consistent with the standard JSON-P API, but often forgotten by implementations. |
| **R-13** | `JsonString.getString()` returns the decoded content (without quotes); Unicode escapes (`\uXXXX`), surrogate pairs and control characters are decoded correctly (TCK requirement). | Often misses BMP characters > U+FFFF. |

### 4.4 Startup / init cost

| # | Rule | Implementation detail |
|---|-------|-----------------------|
| **R-14** | `Jsonb.fromJson(...)` must work with no init side effect beyond the first call. **No JNDI init**, no classpath scan, no CDI lookup. | Constant `O(1)` cost on first `JsonbBuilder.create()`. |
| **R-15** | Binding by class (record or POJO) is **lazy**: resolved only on first serialization/deserialization of that class. Cached via `ClassValue`. | No global scan at boot. |
| **R-16** | Bindings are **immutable** once created. Concurrency-safe without explicit synchronization. | Multi-thread without contention (HTTP server with many virtual threads). |
| **R-17** | An integrated benchmark (`cassini-bench`) compares cold start + warm throughput vs Yasson on a representative set (records, POJOs, dates, collections, polymorphism). Regression > 10% blocks merge. | Performance discipline. |

### 4.5 Java module — `module-info.java`

```java
module io.vidocq.cassini.jsonp {
    requires transitive jakarta.json;
    exports io.vidocq.cassini.jsonp;            // if public API beyond the SPI
    provides jakarta.json.spi.JsonProvider
        with io.vidocq.cassini.jsonp.CassiniJsonProvider;
}

module io.vidocq.cassini.jsonb {
    requires transitive jakarta.json.bind;
    requires io.vidocq.cassini.jsonp;
    exports io.vidocq.cassini.jsonb;            // same, to minimize
    provides jakarta.json.bind.spi.JsonbProvider
        with io.vidocq.cassini.jsonb.CassiniJsonbProvider;
}
```

| # | Rule | Detail |
|---|-------|--------|
| **R-18** | No `requires static` on optional modules. If an optional binding exists (e.g. JSR-310 zone-id format), it must be detected via `Class.forName()` at binding time. | Avoids module-path pollution. |
| **R-19** | No `internal/` packages exported without qualification. | Encapsulation. |
| **R-20** | jlink integration tests in `cassini-jsonb-tests`: a `cassini-jsonb-it-jlink` submodule that produces a minimal jlink image + asserts via curl that the record round-trip works. | Regression-proof against R-1 to R-7. |

### 4.6 Jakarta TCK compatibility

| # | Rule | Detail |
|---|-------|--------|
| **R-21** | JSON-P 2.1 TCK run on `cassini-jsonp` at every PR. Target 100% before merge. | Conformance. |
| **R-22** | JSON-B 3.0 TCK run on `cassini-jsonb` at every PR. Target 100% before merge. | Conformance. |
| **R-23** | No non-spec feature exposed in the public API until the TCK is green. | Avoids instability surface. |

### 4.7 Diagnostics & errors

| # | Rule | Detail |
|---|-------|--------|
| **R-24** | Binding error (unresolvable record, unsupported type) → `JsonbException` with explicit message: record name, failing component, likely cause, suggestion. | The "silent default" mode of Yasson + records is exactly what we want to avoid. |
| **R-25** | A `JsonbConfig.withStrictMode(true)` mode that turns every warning into an error (unknown JSON field during deserialization, missing accessor, etc.). | Helps developers catch bugs as early as possible. |
| **R-26** | Logging via `System.Logger` (JEP 264), `DEBUG` for resolved bindings (one line per class), `WARNING` for fallbacks (custom adapter used, etc.). | Zero logging library dependency. |

---

## 5. Decisions to make

- [ ] **Module path**: `io.vidocq.cassini.jsonp` and `io.vidocq.cassini.jsonb`
      or `io.vidocq.jsonp`/`io.vidocq.jsonb` (potentially useful outside
      Cassini)?
- [ ] **Optional or default**: does Yasson remain a supported fallback
      for Cassini, or do we ship only `cassini-jsonb`?
- [ ] **JSON-P as a separate dependency**: extract `cassini-jsonp` even
      if `cassini-jsonb` is the only consumer, or merge both into one
      module?
- [ ] **Record `null` policy** (see R-8): `nillable=true` by default
      (explicitly serialize `null` fields) or `false` (omit them)?
      The default Yasson behavior is `false` — that is what masked the bug in §1. Lean toward `true` for `cassini-jsonb`?
