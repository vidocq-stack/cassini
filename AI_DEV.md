# AI-assisted development report — Cassini

> Retrospective on building Cassini with Claude Code (Sonnet 4.6 / Opus 4.7).
> Actual duration: **~10 days** (from 2026-04-20 to 2026-04-30).
> Developer: 1 senior (25 years of Java/Jakarta EE experience).

---

## What was built

| Module | Role |
|--------|------|
| `cassini-api` | Public SPI (CassiniHttpExchange, CassiniHttpAdapter, ResourceFactory, BeanProvider, CassiniStack) — zero dependency beyond `jakarta.ws.rs-api` |
| `cassini-core` | Full JAX-RS 4.0 implementation: Invoker, ResourceScanner, UriRouter, MessageBodyRegistry, filters, interceptors, multipart, SSE, built-in providers |
| `cassini-chappe` | Chappe transport adapter + `ChappeRuntimeDelegate` (SeBootstrap), HTTP/1.1 + HTTP/2 bridge |
| `cassini-jdk-http` | Native JDK transport adapter (`com.sun.net.httpserver`) with zero external dependency |
| `cassini-cdi-vauban` | Vauban CDI adapter (`VaubanBeanProvider` via SPI) + `CassiniScopeExtension` (BCE) |
| `cassini-tck` | Arquillian runner + official Jakarta REST 4.0 harness (Model 4.0.0 standalone for ShrinkWrap workaround) |
| `cassini-examples` × 3 | Chappe / jdkhttp / Vauban examples (with HTML/CSS/JS UI and Chappe composite handler) |

**Cross-cutting characteristics**

- **Native Java Modules** — each module has its `module-info.java`, internal packages locked via `exports ... to`
- **Transport-agnostic** — `cassini-core` imports neither `chappe` nor `httpserver`; the public SPI lets any transport (Netty, Undertow, Vert.x) integrate
- **CDI-pluggable** — `BeanProvider` SPI decoupled from `jakarta.cdi`; one adapter per container (Vauban provided, Weld/OpenWebBeans future)
- **Virtual threads** — VT per request (`Executors.newVirtualThreadPerTaskExecutor`), `@Suspended AsyncResponse` and `CompletionStage<T>` block a VT without starvation
- **Official Jakarta REST 4.0.1 TCK — `2535/2535` (100%)** of tests applicable to the Core Profile / SE-Bootstrap profile

---

## Quantitative metrics

| Metric | Value |
|---|---|
| Period | 10 calendar days |
| Commits | **168** (on original repo `vidocq/`) + **24** (post-extraction) = 192 |
| Java code | ~14,200 lines (excluding generated/target) |
| TCK tests run | 2670 (134 out-of-profile + 6 challenges = 135 skipped) |
| TCK PASS | **2535/2535** applicable (100%) |
| Cassini unit tests | 34 (cassini-core) |
| Examples tests | 30 (10 chappe + 10 jdkhttp + 10 vauban) |
| Modules | 7 (api, core, chappe, jdk-http, cdi-vauban, tck, examples × 3) |

---

## Estimate without AI

### Solo developer (senior profile, 25 years of experience)

| Block | Estimated duration |
|------|--------------------|
| §3 Resources (URI templates, best-match §3.7.2, recursive sub-resource locators §3.4) | 1.5 months |
| §4 Providers (MBR/MBW selection, ExceptionMapper §4.4, ContextResolver, JSON-B/Yasson, JAXB) | 1.5 months |
| §5 Context (Request, UriInfo, Variant, HttpHeaders) | 1 month |
| §6 Filters / Interceptors / DynamicFeature §6.5.5 + §6.7.4 setEntityStream | 1.5 months |
| §8 @Suspended AsyncResponse + §9 CompletionStage<T> | 1 month |
| §10 Application/ApplicationPath + SeBootstrap (full RuntimeDelegate) | 1 month |
| §11 Server SSE (CassiniSseEventSink, SseBroadcaster, OutboundSseEvent, EventSource) | 1 month |
| §3.5.4 EntityPart + Multipart RFC 7578 (MBR/MBW + Builder) | 1 month |
| §11.2 BASIC auth + SecurityContext | 0.5 month |
| Decoupled transport (`CassiniHttpExchange` + Chappe and JDK adapters) | 1 month |
| CDI integration (BeanManager, ScopeExtension, BCE) | 1 month |
| TCK setup (Arquillian + ShrinkWrap + Model 4.0.0 workaround) | 0.5 month |
| TCK 100% — debugging the 2535 applicable tests | 2.5 months |
| Clean SPI (`CassiniStack`, `BeanProvider`, ServiceLoader, classpath + Java Modules) | 0.5 month |
| Examples + HTML/CSS/JS UI + composite handler | 0.5 month |
| Documentation (TCK.md, ASYNC.md, README × 4, HOWTO-CLAUDE.md) | 0.5 month |
| Cross-cutting Java Modules friction | +30% overall |
| **Total** | **~18–22 months** |

> The TCK alone (2.5 months) is the most time-consuming phase. Each corner case (recursive sub-resource locator, multi-attribute Cookie RFC 2109, content-type with non-standard `;charset=`, ambiguous variant status code, case-insensitive header lookup…) can take half a day to debug. Reaching 100% instead of 95% is disproportionately expensive.

### Team of 2 seniors

About **10–13 months** — coordination (PR reviews, architectural alignment, shared code ownership) limits linear gain. The TCK remains the least parallelizable phase because fixes are often interdependent there (one fix to `@Path` resolution affects 50+ tests).

---

## Acceleration factor

```
~20x
```

10 guided days ≈ 18–22 months solo.

More precisely:
- **Initial phase (M1–M2c, scaffolding + routing + injection + MBR/MBW)**: highest ratio (~30x) — structurally similar code to known patterns, AI produces idiomatic Java without hesitation.
- **TCK phase (94% → 100%)**: lower ratio (~10–12x) — each failure requires spec reading + fix design + validation, human steering becomes dominant again.
- **Architectural refactors (CassiniStack SPI, BeanProvider SPI)**: medium ratio (~15x) — AI executes brilliantly once the design is decided, but does not decide it itself.

---

## What AI contributed

### JAX-RS 4.0 spec in working memory

The rules of §3.7.2 (best-match with literals + capture groups + path remaining), §4.2.4 (MBR selection with `Object`/byte[]/InputStream), §6.5.2 (NameBinding on Application subclass), §3.4 (recursive sub-resource locator with dynamic dispatch on `Class<T>`) — all these rules were applied correctly from the first draft, without back-and-forth with the PDF spec.

### JAX-RS boilerplate

Implementing `UriBuilder`, `Response.ResponseBuilder`, `HeaderDelegate<T>` for 8 JAX-RS types, the 5 interceptor contexts (RequestContext, ResponseContext, ReaderInterceptorContext, WriterInterceptorContext, DynamicFeatureContext) — that is several weeks of boilerplate reduced to a few hours.

### TCK pattern recognition

TCK failures come in families. Once a fix for "header lookup case-insensitive §6.7.4" is found, the AI recognizes the 3 other tests failing for the same reason without needing to search. Across 2535 tests, this pattern recognition probably saved 20+ days.

### Cross-module refactors

Renaming `cassini-cdi` → `cassini-cdi-vauban` touches: Maven artifactId, Java module, Java package across all files, `requires` in 4 module-info files, exports in cassini-core, dependency in 1 example, ServiceLoader entry, documentation. The AI propagates everything in ~5 minutes, where a human would spend ~1h with omissions risk.

### On-the-fly documentation

`TCK.md`, `ASYNC.md`, `AI_DEV.md`, README per module, ASCII diagrams, justifications of TCK Process 1.4.1 challenges — produced in parallel with the code, with no marginal cost on architectural focus.

---

## What AI did not replace

- **Architectural vision** — the decision to separate `cassini-api` (public SPI) from `cassini-core` (impl), to make transport agnostic via `CassiniHttpExchange`, to promote `CassiniStack.builder()` as the public facade for third-party transports. AI executes the design, it does not create it.
- **Architectural smell detection** — I was the one who said "no, I do not want to specify singletons manually" to bring out `BeanProvider`. AI had delivered a technically correct but mediocre `getSingletons()` kludge.
- **Domain intuition** — knowing that the TCK Multipart `basicTest` fails on Jersey CLIENT and not on Cassini SERVER (it is a challenge to document, not a bug to fix). Knowing that `locatorNameTooLongAgainTest` imposes a non-portable segment-by-segment interpretation of §3.7.2.
- **Discovery of existing APIs** — AI did not go inspect `chappe-api/StaticFileHandler` or `vauban-maven-plugin:generate` on its own. I had to point out "Chappe already has this", "Vauban has an APT". Without that intervention, the examples would have had inferior custom code.
- **Product decisions** — Core Profile vs Full scope, choice of official TCK challenges to document (rather than try to fix), priority on strict Java Modules from the start.

---

## Observations on the working method

### What worked well

- **Systematic plan mode** on refactors (CassiniStack SPI, BeanProvider SPI) — writing the 5-step plan before touching the code avoided costly zigzags.
- **Isolated parallel agents (worktree)** on major cross-module refactors — while one agent restructured `cassini-chappe`, I kept analyzing the diff and preparing the `cassini-jdk-http` update mentally.
- **Context mode** for TCK outputs (~37,000 lines per run) — offloading avoided saturating the context window during long sessions.
- **TCK validation after each fix** — cost ~2 minutes per run but avoids silent regressions that would have cost much more later.
- **Systematic diff review** before each commit — AI sometimes produces code that compiles and passes tests but introduces architectural regressions (duplication, lost encapsulation).

### What cost time despite AI

- **Recurring Java Modules friction** — `opens to named-module` does not cover the unnamed module, missing `requires` in classpath mode, `ServiceLoader` ignores `provides` when the module is not on the graph, `META-INF/services` must be duplicated for classpath mode. Probably 1 cumulative day.
- **Auto-discovery vs explicit choice** — first draft `getSingletons()` (kludge), second draft `BeanProvider` (correct). ~30 minutes of rework.
- **Latent bugs introduced by the agent autonomously** — 700 lines of `RuntimeDelegate` boilerplate duplicated instead of extended, missing `META-INF/services` that made the TCK fail once (7 `SeBootstrapIT` errors). ~1h cumulative.
- **Zombie process on port 8080** on the dev workstation — blocked for an hour during the first TCK validation before the conflict was identified.

---

## Comparison with reference implementations

| Implementation | Team | Known public duration |
|---|---|---|
| Jersey (Eclipse Foundation) | Multiple Oracle/Eclipse people | Years (since 2010+) |
| RESTEasy (Red Hat) | Multiple people | Years (since 2007+) |
| Apache CXF (rs) | Multiple ASF people | Years |
| **Cassini** | **1 senior + AI** | **10 days for 2535/2535** |

Note: these implementations cover a broader scope than Cassini (notably client API + Servlet + EE Full Profile). But on the Core Profile / SE-Bootstrap scope that defines Cassini, reaching official TCK 100% conformance in less than 2 weeks is, to my knowledge, unprecedented.

---

## Conclusion

For a project of this technical density — formal spec (JAX-RS 4.0 = 200+ dense pages), 100% official TCK, native Java Modules, transport-agnostic, CDI-pluggable, virtual threads — AI assistance represented a **~20x** multiplier on development speed.

The gain is not uniform: it is maximal on mechanical code (JAX-RS boilerplate, cross-module refactors, test generation) and null on architectural decisions, TCK challenge vs bug judgment, and product intuition.

As with Vauban, the most accurate model is not "AI codes instead of the developer" but
**"the senior developer drives at the speed of thought rather than at the speed of typing"**.

The remaining friction is no longer in code production, it is in:
1. Agent supervision (reviewing each diff, correcting suboptimal choices)
2. Toolchain friction (Java Modules, IntelliJ test runner, Maven 4 vs IDE LSP)
3. TCK validation (the spec does not get simpler with AI)

If I had to rebuild Cassini without AI today, I probably would not try alone.
With two seniors, I would estimate **10 to 13 calendar months** to reach TCK 2535/2535
on the same scope. With AI and one senior at the controls, it took **10 days**.
