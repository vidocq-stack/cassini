# Cassini — Bug Registry

Format: one bug per dated section — short id, symptom, minimal repro, cause hypothesis, status.

---

## CASSINI-001 — APT adapter fails to compile for `ExceptionMapper<E extends Throwable>` (and bounded type-variable params)
- **Date**: 2026-06-01 — **Status**: FIXED
- **Severity**: medium (any `@Provider`/resource whose method has a bounded type-variable parameter,
  notably `ExceptionMapper<Throwable>`)
- **Surfaced by**: Arago (a diagnostic `@Provider ExceptionMapper<Throwable>`).

### Symptom
Compiling a class implementing `ExceptionMapper<Throwable>` with the cassini-processor on the
annotation-processor path failed:
`BoomMapper$$CassiniAdapter.java: incompatible types: java.lang.Object cannot be converted to java.lang.Throwable`.

### Cause
`CassiniResourceProcessor` collected the interface's abstract `toResponse(E)` (a type variable) in
addition to the concrete `toResponse(Throwable)` override. It erased the type variable `E` to
`java.lang.Object` (hardcoded in `javaTypeName`, `jvmBinaryName`, `toJvmDescriptor`), so:
1. the de-dup signature (`(Ljava/lang/Object;)…`) differed from the concrete override
   (`(Ljava/lang/Throwable;)…`), so both were emitted; and
2. the extra invoke case generated `target.toResponse((Object) args[0])`, which does not compile —
   the bean only declares `toResponse(Throwable)`.

The runtime/plugin generators (reflection-based, `RuntimeAdapterGenerator`) were unaffected: they see
the concrete `toResponse(Throwable)` override directly and filter bridge methods.

### Fix
A type variable now erases to its **leftmost bound** (`types.erasure(...)`), not to `Object`:
`E extends Throwable` → `Throwable`. The cast becomes `(Throwable) args[0]` (valid) and the interface
method's signature matches the concrete override, so it de-dups to a single invoke case. Unbounded
type variables still erase to `Object` (unchanged). Regression:
`CassiniResourceProcessorTest.processorGeneratesAdapterForExceptionMapperOfThrowable`.
REST 4.0 TCK preserved: 2535 PASS / 135 SKIP.

## CASSINI-002 — Request-remapping wrappers silently drop per-request attributes
- **Date**: 2026-06-11 — **Status**: FIXED
- **Severity**: low before M2h (latent — nothing flowed through the wrappers' attributes);
  high during M2h (3 SecurityContext TCK tests errored)
- **Surfaced by**: the M2h ThreadLocal→ScopedValue migration (auth handed over via Chappe
  per-request attributes instead of an InheritableThreadLocal).

### Symptom
After migrating `CURRENT_AUTH` to a Chappe request attribute (`cassini.auth`), the REST TCK
errored on `requestcontext.security.getSecurityContextTest` and
`securitycontext.basic.basicAuthorization{Admin,StandardUser}Test`: the resource saw an
anonymous SecurityContext although `BasicAuthHandler` had validated the credentials.

### Cause
Three anonymous `Request` wrappers (context-prefix remapping in
`VidocqCassiniDeployableContainer`, `CassiniTestHarness.ContextStrippingHandler`, and
`ChappeRuntimeDelegate`) override the path/context methods but not
`attribute(String)`/`attribute(String, Object)`. The Chappe interface defaults for those are
**no-ops** (`return null` / `return this`), so any attribute written by an upstream handler on
the wrapped request was silently dropped before reaching `ChappeHttpExchange`.

### Fix
The three wrappers now delegate both `attribute` methods to the wrapped request.
`ChappeHttpExchange.getAttribute` also falls back to the Chappe request attributes, so
upstream handlers can hand state to Cassini across the per-request virtual-thread boundary.
REST 4.0 TCK: 2535 PASS / 135 SKIP / 0 ERROR.

## CASSINI-003 — Duplicated RuntimeDelegate copies diverged (CacheControl) + quoted field lists broken
- **Date**: 2026-06-12 — **Status**: FIXED (7ff6af3 + 83db6dd)
- **Severity**: low (header-delegate edge cases; nothing user-reported)
- **Surfaced by**: the Java 25 modernization review — same duplication-divergence
  class as vauban VAU-TYP-001.

### Symptom
`ChappeRuntimeDelegate` duplicated ~630 lines of `CassiniRuntimeDelegate` (10 inner
delegate classes + 2 reflective shims) "so that cassini-chappe does not depend on
cassini-core" — a rationale that had been obsolete since the module gained a hard
`requires io.vidocq.cassini.core`. The copies had diverged:
1. core's `CacheControlDelegate` silently dropped the `public` directive
   (`case "public": break`) while the chappe copy — the one the TCK validates —
   kept it in the cache extension map. Round-tripping a `Cache-Control: public,
   max-age=60` header through core lost `public`.
2. BOTH copies tokenized `Cache-Control` with a naive `s.split(",")`, breaking
   RFC 7234 quoted field lists: `private="a,b"` parsed as a single `"a` field.

### Reproduction
```java
var hd = new CassiniRuntimeDelegate().createHeaderDelegate(CacheControl.class);
hd.fromString("public, max-age=60").getCacheExtension(); // was empty (core)
hd.fromString("private=\"a,b\"").getPrivateFields();      // was ["\"a]
```

### Fix
Core parser: quote-aware directive splitting + quoted field lists expanded into
individual names + `public`/unknown directives kept as cache extensions (chappe
behaviour). `ChappeRuntimeDelegate` now extends `CassiniRuntimeDelegate` like
`JdkHttpRuntimeDelegate` (single copy, qualified export, `opens internal.runtime`
removed). Contract pinned by `CassiniRuntimeDelegateTest`.
REST 4.0 TCK after unification: 2670 run, 0 FAIL, 0 ERR, 132 SKIP (= baseline 2538 PASS).

## CASSINI-004 — `cassini:generate` splits an automatic module's package into the application (grimm#15)
- **Date**: 2026-10-01 — **Status**: FIXED (`fix/no-split-into-automatic-module`)
- **Symptom**: with `scope=dependencies`, a dependency jar without `module-info` (Grimm's, at the time) had its adapter written into the project's output, e.g. `io/vidocq/grimm/cdi/OpenApiResource$$CassiniAdapter.class`. In a modular application that jar is an automatic module, so the package was split. Either the layer failed (`LayerInstantiationException: Package … in both module …`), or the application layer owned the package and the dependency's beans were silently lost (Grimm inactive, `/openapi` 404).
- **Minimal repro**: `GenerateAdaptersMojoTest#anAutomaticModuleDependencyFailsAModularProject`.
- **Cause**: the plugin assumed that a jar without `module-info` sits on the class path, which no longer holds once the project itself is a module.
- **Fix**: when the project's output holds a `module-info.class`, such a dependency fails the build with an actionable message: give it a `module-info` (a Vidocq artifact never ships as an automatic module), or exclude it. A class-path project keeps the old behaviour (`#aClasspathProjectStillGetsTheAdapterOfAPlainJar`).

## CASSINI-006 — `cassini-jdk-http` echoes the exception message in its 500, and sends any header the JDK accepts (cassini#58)
- **Date**: 2026-10-17 — **Status**: FIXED (`fix/58-jdk-http-500-body`)
- **Severity**: medium. A failing request sent the exception message, and any request or header value it carried, to the client.
- **Surfaced by**: the review of cassini#56.

### Symptom
`JdkHttpAdapter` caught every failure and wrote `e.getMessage()` (`java.lang.IllegalStateException: <message>` once wrapped by the future) as the body of a `text/plain` 500. Response headers were copied into the JDK `Headers` unchecked: the JDK refuses CR and LF only, so a non-Latin-1 value went out mangled.

### Repro
`JdkHttpAdapterFailureTest` (cassini-jdk-http).

### Fix
The 500 is empty, and the log line names the exception's class only, with a message-less stack trace. `ResponseHeaders.check` applies the Chappe adapter's rule (name an RFC 9110 token, value ISO-8859-1 text without control characters, not null) before anything is sent: a refused header answers 500 with a line naming the header, never its value. In streaming mode no sink is opened, which the SPI defines as falling back to buffered mode.

---

## CASSINI-005 — A response header Chappe refuses: latent streaming leak, unclear buffered 500 (cassini#56)
- **Date**: 2026-10-11 — **Status**: FIXED (`pr/ybl/cassini-56-header-leak`)
- **Severity**: medium. Buffered path: a real-world 500 with an unhelpful log. Streaming path: latent, its only producer (`Invoker`, SSE) sends constant, valid headers (`Content-Type: text/event-stream`, `Cache-Control: no-cache`).
- **Symptom**: since chappe's CHAPPE-008 fix, `Response.Builder.header(name, value)` throws `IllegalArgumentException` for a name that is not a token, or a value that is `null` or holds CR, LF, NUL, another control character or a char above `U+00FF`. A typical trigger is `Content-Disposition: attachment; filename="Отчёт-€.pdf"`.
  - Buffered mode: the exception reached the generic `catch (Throwable)` of `ChappeHttpAdapter`, which logged `Request failed: GET /…` with a stack trace and no hint of which header failed or how to fix it.
  - Streaming mode: the exception was thrown on the handler thread before `streaming.input()` was handed to Chappe. Nobody read or closed the pipe, so the thread writing the entity blocked forever once the bounded queue was full.
- **Minimal repro**: `ResponseHeaderRejectionTest` (cassini-chappe). Before the fix: 12 errors, `handle()` throws the builder's exception.
- **Cause**: `ChappeHttpAdapter` copied the JAX-RS headers into the Chappe builder with no guard.
- **Fix**: both paths build the Chappe response inside a `try/catch (RuntimeException)`. On failure, the streaming path closes `streaming.input()` (the writer's next write fails with an `IOException` and its thread ends), and both answer a bare `500` with one `ERROR` line. When a header is to blame, the line names it (escaped), never its value nor the transport's exception message, and points to RFC 8187 (`filename*=UTF-8''…`). It does not depend on chappe's validation: the tests force the failure through a package-private builder seam, and the real-chappe cases are skipped on a chappe that still accepts the value (checked with 0.3.0: 5 pass, 8 skipped).

---

## CASSINI-007 — Request properties are not shared between the contexts of a request, and the CDI request scope ends in a response filter
- **Date**: 2026-10-27 — **Status**: FIXED (cassini#116, PR #124, `fix/shared-request-properties`): `4b5d961` (the properties of a request are one store), `b8a2121` (an end-of-request action, run once the dispatch is over), `c42ee15` (the scope filters end the scope there), `38b81d4` and `abc3fbb` (the interceptors share the store; a response rendered before any request context exists no longer fails). Found while removing the per-request thread of `ChappeHttpAdapter`; the first version of the fix (`4b5d961` alone, which made the scope filters end the scope in their response filter) was reviewed on 2026-11-07 and corrected by the three commits that follow it. A second review (2026-11-07) added `85a10f9` (a 404, 405, 415 or 406 shares the request's store: the exchange is published as the current request from the start of the dispatch), `8c61c20` (an `Error` of the end-of-request action is attached as suppressed to the one in flight, or rethrown, never masking nor swallowed) and `f3fc2da` (a non-`Runnable` completion property is logged; the contract name is pinned by a test in core, `cassini-cdi` and `cassini-cdi-vauban`).
- **Severity**: high for the scope part, medium for the properties. JAX-RS conformance (a property set by a request filter must be visible to the response filters, and to the interceptors, of the same request); a request scope that was never ended on some paths (no `@PreDestroy` on `@RequestScoped` beans, the Weld request context left on the thread); and, with the first version of the fix, a request scope ended before the entity was written.
- **Symptom**: (1) every `CassiniRequestContext` had its own `properties` map, and a request builds several; the reader and writer interceptor contexts had a private map too. `CdiRequestScopeFilter` (cassini-cdi) and `VaubanRequestScopeFilter` (cassini-cdi-vauban) store a marker in the properties when they activate the scope, and ended it in their response filter only when they found it: they never found it. (2) Once the properties were shared, the response filter did end the scope, but before the entity was written: a `MessageBodyWriter`, a `WriterInterceptor` or a `StreamingOutput` that touched a `@RequestScoped` bean got a `ContextNotActiveException` (`WELD-001303` on Weld), and under the Vidocq runtime hook (`RequestContext#runInScope`) the filter ended the scope that the hook had opened. (3) A 404 or 405, an unmapped failure (500) and a stream never reached a response filter, so the scope stayed active.
- **Minimal repro**: `WeldRequestScopeLifetimeTest` (cassini-it-weld) and `FilterOnlyLifetimeTest` / `RuntimeHookLifetimeTest` (cassini-it-vauban), against the filters of `b524576`: `GET /lifetime/stream` (a `StreamingOutput` that reads a `@RequestScoped` bean) answers 500 `RequestScope is not active`; `GET /lifetime/missing` (404) and `/lifetime/boom` (500) start a request scope that never ends; under the hook the scope starts twice. For (1), `RequestPropertiesTest`.
- **Cause**: the properties belonged to the context object instead of the request; and the scope was tied to the response filters, which run before the entity is written and not on every path.
- **Fix**: the properties are one store, an exchange attribute built by the first property set (no allocation before, as `RequestPathCopiesTest` requires) and shared by the request, response and interceptor contexts (a null name asks for nothing). Its creation is not atomic: the `CassiniHttpExchange` SPI has no create-if-absent for an attribute, and the contexts of a request run one after the other on the dispatch thread (JAX-RS contexts are not thread-safe); the store is a concurrent map. The scope filters only activate the scope, and hand the request an end-of-request action through the `io.vidocq.cassini.requestCompleted` property (a `Runnable`): `DefaultCassiniHttpAdapter.dispatch` runs it in a `finally`, once the entity is written, on the thread that ran the filters, on every path (match, 404/405, mapped or unmapped failure, failed write, async response resumed elsewhere; an SSE ends with its resource method). The Vauban filter is inert when a scope is already active on the thread, so it neither activates twice nor ends a scope that is not its own; on an application server `RequestContextController#activate` answers `false` and nothing is ended. Tests: `RequestCompletionTest`, `RequestPropertiesTest`, `RequestPropertiesStoreTest` (cassini-core), `WeldRequestScopeLifetimeTest`, `FilterOnlyLifetimeTest`, `RuntimeHookLifetimeTest`; REST 4.0 TCK (2026-11-07, run in series): 2670 run, 0 failures, 131 skipped, as on `main`. The `ConcurrentHashMap`-backed attribute stores of the Chappe and JDK exchanges are what the `CassiniHttpExchange` contract promised ("thread-safe with virtual threads").
- **Not fixed here**: the scope of a streamed SSE response still ends with the resource method that opened the sink, not with the last event; a bean read from a thread the application starts itself has no scope at all, as before.
- **Corrected repro of the original symptom**: the earlier text said that `new ChappeHttpAdapter(engine)` dispatches on the calling thread when `mayStream()` is `false`; it does so only with a `Scoped` hook (`ChappeHttpAdapter`: `inline = !engine.mayStream() && scoped != Scoped.IDENTITY`). Without a hook every request has its own virtual thread, which is what hid the leak.


## CASSINI-008 — `@Context ContextResolver` injects the `Providers` and fails the invocation
- **Date**: 2026-10-27 — **Status**: FIXED 69b8dd1 (cassini#117, `fix/context-resolver-param`; found while covering the request path with unit tests)
- **Severity**: low. `ContextResolver` is not among the `@Context` types of §9.2; an application that asks for one gets a 500 instead of a clear "unsupported" error.
- **Symptom**: a resource method parameter `@Context ContextResolver<?> cr` receives the request's `CassiniProviders`, which is not a `ContextResolver`: the reflective invocation throws a `ClassCastException`, answered 500.
- **Minimal repro**: `@GET public String get(@Context ContextResolver<?> cr)`, then any request to it.
- **Cause**: `ParamExtractor` maps `ContextResolver.class` to `currentProviders()`, a copy of the `Providers` case.
- **Fix**: dropped the mapping (`ContextResolverParameterTest`), so that the parameter ends in "Unsupported @Context type" like any other type outside §9.2; or, if a `ContextResolver` is meant to be injectable, resolve it through `Providers.getContextResolver` with the parameter's generic type.

## CASSINI-009 — `UriBuilder` glues a relative path to the authority (`http://ha`)
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniUriBuilder` with unit tests)
- **Severity**: medium. Any URI built from a base without a path (`http://host`) plus a relative `path(...)`/`segment(...)` came out wrong, and so did a builder set component by component (`scheme().host().path("x")`).
- **Symptom**: `UriBuilder.fromUri("http://h").path("a").build()` gave `http://ha`; `toTemplate()` had the same defect.
- **Minimal repro**: `UriBuilder.fromUri("http://h").path("a").build().toString()` → `"http://ha"`, expected `"http://h/a"`.
- **Cause**: `path(String)` only inserts a `/` between two path parts; when the path was empty, the first relative segment was stored as is, and `assemble`/`toTemplate` appended it right after the authority. RFC 3986 §3.3: a path that follows an authority must be empty or begin with `/`.
- **Fix**: `assemble` and `toTemplate` lead a non-empty path with `/` when an authority precedes it. Regression test: `CassiniUriBuilderTest.path_after_an_authority_starts_with_a_slash`.

## CASSINI-010 — `UriBuilder` encodes a character outside the BMP as `%3F%3F`
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniUriBuilder` with unit tests)
- **Severity**: low. Any emoji or other supplementary character in a path, query, matrix or fragment value was replaced by two `?` once percent-encoded.
- **Symptom**: `UriBuilder.fromPath("/😀").build()` gave `/%3F%3F` instead of `/%F0%9F%98%80`.
- **Minimal repro**: `UriBuilder.fromPath("/").queryParam("q", "😀").build()` → `/?q=%3F%3F`.
- **Cause**: `encode` walked the string `char` by `char` and UTF-8-encoded each one alone; a lone surrogate has no UTF-8 form, so the JDK encoder substituted `?` for each half of the pair.
- **Fix**: encode a whole code point at a time (`codePointAt` / `charCount`). Regression test: `CassiniUriBuilderTest.characters_outside_the_basic_multilingual_plane_are_encoded_as_utf_8`.

## CASSINI-011 — `UriBuilder.build(...)` gives the fragment's value to the query and vice versa
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniUriBuilder` with unit tests)
- **Severity**: medium. A URI template with parameters in both its query and its fragment received its positional values swapped, silently.
- **Symptom**: `UriBuilder.fromPath("/{a}").replaceQuery("q={b}").fragment("{c}").build("1", "2", "3")` gave `/1?q=3#2`.
- **Minimal repro**: `UriBuilder.fromUri("http://h/{p}?q={v}#{f}")` (a template, parsed by `uri(String)`) `.build("1", "2", "3")` → `http://h/1?q=3#2`, expected `http://h/1?q=2#3`.
- **Cause**: `buildInternal` substituted the path, then the fragment, then the query, consuming the positional values in that order. The `UriBuilder#build` Javadoc takes them in the order the templates appear in the URI.
- **Fix**: substitute the query before the fragment. Regression test: `CassiniUriBuilderTest.build_substitutes_values_in_order_of_appearance`.

## CASSINI-012 — `UriBuilder.queryParam` / `matrixParam` encode the braces of a template
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniUriBuilder` with unit tests)
- **Severity**: medium. A template put in a query or matrix parameter could never be substituted, and its braces leaked into the built URI as `%7B…%7D`.
- **Symptom**: `UriBuilder.fromPath("/p").queryParam("q", "{v}").build("1")` gave `/p?q=%7Bv%7D` (the value `1` unused) instead of `/p?q=1`.
- **Minimal repro**: `UriBuilder.fromPath("/p").matrixParam("m", "{a}").toTemplate()` → `/p;m=%7Ba%7D`, expected `/p;m={a}`.
- **Cause**: `queryParam`, `replaceQueryParam`, `matrixParam` and `replaceMatrixParam` percent-encoded the whole name and value, braces included, while their Javadoc says both "may contain URI template parameters"; and `build*()` appended query parameter names as they were, never substituting them.
- **Fix**: those methods encode around `{name}` templates (`encodeKeepingTemplates`), and `build*()` substitutes the query parameter names before their values. Regression test: `CassiniUriBuilderTest.query_and_matrix_parameters_may_hold_templates`.

## CASSINI-013 — `Response.getStatusInfo()` drops the reason phrase of `status(int, String)` for a standard code
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniResponse` with unit tests)
- **Severity**: low. Only the response object is affected (the transports write the status code); an application or a filter reading the reason phrase back got the standard one.
- **Symptom**: `Response.status(201, "Made").build().getStatusInfo().getReasonPhrase()` returned `"Created"`.
- **Minimal repro**: as above; a non-standard code (`299`) already kept its phrase.
- **Cause**: `CassiniResponse.getStatusInfo` returned `Status.fromStatusCode(status)` whenever the code was standard, ignoring the reason given to the builder.
- **Fix**: the standard `Status` is returned only when no reason phrase, or the standard one, was given; otherwise a `StatusType` carrying the given phrase. Regression test: `CassiniResponseTest.a_custom_reason_phrase_wins_over_the_standard_one`.

## CASSINI-014 — `ContainerResponseContext`: null status info for a non-standard code, header strings bypass the header delegates
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniResponseContext` with unit tests)
- **Severity**: low to medium. A response filter calling `getStatusInfo().getFamily()` on a `299`/`599` response failed with a `NullPointerException`; a filter reading a `Date` header as a string got `Date.toString()` (local time zone, not an HTTP date).
- **Symptom**: `new CassiniResponseContext(599, …).getStatusInfo()` returned `null`; with a `Date` value in `Expires`, `getHeaderString("Expires")` returned `"Sun Nov 06 09:49:37 CET 1994"`.
- **Minimal repro**: a `ContainerResponseFilter` on a resource answering `Response.status(599)`, calling `ctx.getStatusInfo().getFamily()`.
- **Cause**: the status info came from `Response.Status.fromStatusCode`, null for an unknown code, although the Javadoc says it is never null; `getHeaderString`/`getStringHeaders` used `String.valueOf` where the Javadoc asks for the `HeaderDelegate` of the value's class.
- **Fix**: a bare `StatusType` (code, family, empty reason) for a non-standard code; header values converted through `RuntimeDelegate.createHeaderDelegate`. Regression tests: `CassiniResponseContextTest.a_non_standard_status_still_has_a_status_info`, `header_strings_go_through_the_header_delegates`.

## CASSINI-015 — `HttpHeaders.getAcceptableLanguages()` is empty without an `Accept-Language`
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniHttpHeaders` with unit tests)
- **Severity**: low. A resource picking a language with `headers.getAcceptableLanguages().get(0)` failed with an `IndexOutOfBoundsException` on a request without `Accept-Language`.
- **Symptom**: `@Context HttpHeaders h; h.getAcceptableLanguages()` returned `[]`.
- **Minimal repro**: any request without (or with a blank) `Accept-Language`.
- **Cause**: `CassiniHttpHeaders.getAcceptableLanguages` returned `List.of()`; the Javadoc asks for "a read-only list containing a single wildcard Locale instance (with language field set to "*")" (the media-type counterpart already returned `*/*`).
- **Fix**: return `List.of(Locale.of("*"))`. Regression test: `CassiniHttpHeadersTest.without_accept_language_any_language_is_acceptable`.

## CASSINI-016 — `Variant.VariantListBuilder.build()` does not reset the builder
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `CassiniRuntimeDelegate` with unit tests)
- **Severity**: low. Reusing one `VariantListBuilder` for two lists gave the second list the variants of the first.
- **Symptom**: `b.mediaTypes(TEXT_PLAIN).build()` then `b.languages(ENGLISH).build()` returned both variants the second time.
- **Minimal repro**: as above, with `Variant.VariantListBuilder b = Variant.VariantListBuilder.newInstance()`.
- **Cause**: `StubVariantListBuilder.build()` copied the accumulated list without clearing it; the Javadoc says "After this method is called the builder is reset to an empty state".
- **Fix**: clear the accumulated variants after copying them. Regression test: `CassiniRuntimeDelegateTest.variantListBuilder_is_reset_by_build`.

## CASSINI-017 — `Request.evaluatePreconditions(EntityTag)` compares `If-Match` weakly
- **Date**: 2026-10-27 — **Status**: FIXED d484eb4 (cassini#118, `fix/if-match-strong`; REST 4.0 TCK: 2670 run, 0 failures, 131 skipped). `matchesEtag` takes a `strong` flag, set for `If-Match` only (found while covering `CassiniRequest` with unit tests; not fixed: the conditional-request path deserves its own change, checked against the TCK)
- **Severity**: low. A conditional update guarded by a weak tag goes through where it should be refused (lost-update protection weakened).
- **Symptom**: `If-Match: W/"a"` against the current tag `W/"a"` (or `"a"`) meets the precondition; RFC 9110 §13.1.1 requires the strong comparison for `If-Match`, under which a weak tag never matches, so the answer must be 412.
- **Minimal repro**: `PUT` with `If-Match: W/"a"`, resource calling `request.evaluatePreconditions(new EntityTag("a", true))` → `null` (precondition met), expected a 412 builder.
- **Cause**: `CassiniRequest.matchesEtag` strips `W/` on both sides for every header ("ignore weak/strong distinction in MVP"); that is right for `If-None-Match` (weak comparison, §13.1.2) only.
- **Fix to do**: give `matchesEtag` a `strong` flag; for `If-Match`, fail when either tag is weak or the opaque values differ. Add the case to `CassiniRequestTest` and run the REST TCK.

## CASSINI-018 — The client needs a JSON-B provider for any entity that is not a `String` or a `byte[]`
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `cassini-client` with unit tests)
- **Severity**: medium. `cassini-client` depends on the JSON-B API only and the implementation comes from the application; without one, sending `Entity.text(42)` failed, although no JSON is involved.
- **Symptom**: the first entity whose value is neither a `String` nor a `byte[]` (any media type) threw `ExceptionInInitializerError`, and every later one `NoClassDefFoundError: Could not initialize class ClientEntityJsonb`. Reading a JSON body into a POJO without a provider failed the same way instead of with a `ProcessingException`.
- **Minimal repro**: no JSON-B implementation on the module path, then `client.target(url).request().post(Entity.entity(42, MediaType.TEXT_PLAIN_TYPE))`.
- **Cause**: `ClientEntityJsonb` created its default `Jsonb` in a static initialiser, run by its first use, the `isJson(mediaType)` check.
- **Fix**: the default `Jsonb` is created on the first JSON (de)serialisation; a missing provider then fails with a `ProcessingException`. Regression tests: `WithoutJsonbProviderTest`, run by the `without-jsonb` surefire execution of `cassini-client`, which leaves Champollion's JSON-B out.

## CASSINI-019 — Client responses look header names up case-sensitively: no media type, location or charset from a real server
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `cassini-client` with unit tests)
- **Severity**: medium. HTTP field names are case-insensitive (RFC 9110 §5.1), but the client kept them as the server spelled them; over HTTP/2 they are all lower case. `Response.getMediaType()`, `getLocation()`, `getLanguage()` and `getHeaderString("Content-Type")` returned `null`, and `readEntity(String.class)` ignored the `charset` of the response.
- **Symptom**: a server answering `Content-type: text/plain;charset=ISO-8859-1` (the JDK `HttpServer` spelling) with `café` in Latin-1: `getMediaType()` is `null` and the entity reads `caf�`.
- **Minimal repro**: `ClientResponseTest.response_headers_are_exposed` and `string_entity_is_decoded_with_the_charset_of_the_content_type` against the JDK `HttpServer`.
- **Cause**: `CassiniClientResponseContext` and `CassiniClientResponse` copied the headers into `MultivaluedHashMap`s and looked them up with `getFirst("Content-Type")`; only the JSON path searched the names case-insensitively.
- **Fix**: the response headers live in a map whose names compare case-insensitively, shared by the response context and the response; the unused `CassiniClientResponse.from(HttpResponse)` is removed.

## CASSINI-020 — Client `Response.readEntity` breaks its contract: closed responses stay readable, unreadable types throw `UnsupportedOperationException`
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `cassini-client` with unit tests)
- **Severity**: low. `Response.readEntity` must throw `IllegalStateException` once the response is closed and `ProcessingException` when the entity cannot be mapped to the requested type; callers catching `ProcessingException` missed the failure.
- **Symptom**: `response.close(); response.readEntity(String.class)` returned the body; `readEntity(Locale.class)` on a `text/plain` body threw `UnsupportedOperationException`. `hasEntity()` and `bufferEntity()` also ignored the close.
- **Minimal repro**: `ClientResponseTest.closed_response_cannot_be_read` and `unreadable_entity_type_fails_with_a_processing_exception`.
- **Cause**: `CassiniClientResponse.close()` set a flag nothing read; the fallback of `readEntity` threw `UnsupportedOperationException`.
- **Fix**: `readEntity`, `hasEntity` and `bufferEntity` throw `IllegalStateException` on a closed response; an unreadable type throws `ProcessingException`.

## CASSINI-021 — Typed client invocations return the body of an error response instead of throwing `WebApplicationException`
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `cassini-client` with unit tests)
- **Severity**: medium. `SyncInvoker`/`AsyncInvoker`/`Invocation` contract: a typed invocation (`get(String.class)`, `invoke(GenericType)`…) whose response status is not successful throws `WebApplicationException`; `Response.class` returns the response.
- **Symptom**: `target.request().get(String.class)` on a 404 returned the error body. The async invoker threw a bare `WebApplicationException` for statuses ≥ 400 only (a 302 was read as a success). `get(Response.class)` failed in `readEntity`.
- **Minimal repro**: `ErrorStatusTest` (a server answering the status it is asked for).
- **Cause**: the typed sync methods and `CassiniInvocation.invoke(Class)` called `readEntity` directly; `CassiniAsyncInvoker` had its own `≥ 400` check.
- **Fix**: one `readTyped` for the three paths: `Response.class` passes the response through, a status outside 2xx throws the matching subclass (`NotFoundException`, `RedirectionException`, `ServerErrorException`…), as other implementations do.

## CASSINI-022 — Async client invocations: wrapped failures, untyped callbacks, `Invocation.submit(InvocationCallback)` unsupported
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `cassini-client` with unit tests)
- **Severity**: medium. `AsyncInvoker` contract: `Future.get()` throws an `ExecutionException` whose cause is the `ProcessingException` (or `WebApplicationException`); an `InvocationCallback<T>` receives the entity read as `T` in `completed`, and the exception itself in `failed`.
- **Symptom**: a connection failure reached `Future.get()` as `RuntimeException(ProcessingException)` and `failed(...)` as a `CompletionException`; an `InvocationCallback<String>` was handed the `Response` (a `ClassCastException` in user code) and never failed on an error status; `Invocation.submit(InvocationCallback)` threw `UnsupportedOperationException`.
- **Minimal repro**: `AsyncInvokerTest.typed_callback_receives_the_entity_read_as_its_type`, `processing_failure_reaches_the_future_and_the_callback_unwrapped`; `InvocationTest.submitted_invocation_with_a_callback_completes_it`.
- **Cause**: every async helper wrapped any exception in a `RuntimeException` (the `try`-with-resources on an `AutoCloseable` forced a `catch (Exception)`), and the callback path cast the `Response` to `T`.
- **Fix**: `AsyncContextPropagator.activate` returns a `Scope` whose `close()` throws nothing, so exceptions pass through unwrapped; the callback's type argument is resolved from its class (`Response` when unresolved) and read through the same `readTyped` as the futures; `failed` gets the cause of the `CompletionException`. `Invocation.submit(...)` delegates to the async invoker.

## CASSINI-023 — Client properties: `null` does not remove, request properties never reach the filters
- **Date**: 2026-10-27 — **Status**: FIXED (found while covering `cassini-client` with unit tests)
- **Severity**: medium. `Configurable.property(name, null)` must remove the property; `Invocation.Builder.property` / `Invocation.property` set a property of the request, read by the filters through `ClientRequestContext.getProperty`.
- **Symptom**: `client.property("k", null)` left `k` among the property names, mapped to `null`. `request().property("k", "v")` was invisible to `ClientRequestContext.getProperty("k")`, and was written to the client's configuration instead, so it leaked into every later request of the client.
- **Minimal repro**: `PropertiesTest`.
- **Cause**: `CassiniClientConfiguration.putProperty` always `put`; `CassiniInvocationBuilder.property` delegated to it, and the request context started with an empty property map.
- **Fix**: `putProperty` removes on `null`; the invocation builder keeps its own properties and copies them into the request context of each invocation.

## CASSINI-024 — Client API gaps left open after the coverage pass
- **Date**: 2026-10-27 — **Status**: FIXED and merged (cassini#120, closed 2026-11-08: #125, #126, #127, #128, #129, #130, #131, #132, #133) (item 2: `scheduledExecutorService` accepted but unused, Cassini schedules nothing) (found while covering `cassini-client` with unit tests; each item is larger than a test-first fix of this pass, so the tests leave them out instead of asserting the current behaviour)
- **Severity**: low to medium, by item. None of them fails the official TCK run (2670 run, 0 failures, 131 skipped).
- **Items**:
  1. `Invocation.Builder.rx()` and `rx(Class)` throw `UnsupportedOperationException`; `rx()` is mandatory (a `CompletionStageRxInvoker`). **Fixed** (cassini#120, `fix/client-rx`): `CassiniRxInvoker` (a `CompletionStageRxInvoker` on the client's executor, virtual threads by default); `rx(Class)` returns the built-in one or asks a registered `RxInvokerProvider` (`IllegalStateException` otherwise); test `RxInvokerTest`.
  2. `ClientBuilder.executorService(...)` only backs the `HttpClient`: the async invoker always runs on its own virtual-thread executor. *Partly fixed in `fix/client-rx`*: the executor given to the builder now backs the async and reactive invocations; the `HttpClient` always runs on an internal virtual-thread executor, so a small user pool cannot starve a blocked send (test `rx_runs_on_the_executor_given_to_the_builder` uses a single-thread pool). `keyStore`, `trustStore` and `hostnameVerifier` are **fixed** in `fix/client-tls` (`HostnameVerifyingSslContext`: the verifier replaces the JDK endpoint identification and runs right after the handshake, before any application byte; tests `TlsConfigurationTest` on a local HTTPS server with a certificate generated by the test, a rejecting verifier lets no request through). `scheduledExecutorService` is accepted but not used: Cassini schedules nothing (timeouts are `HttpRequest.timeout`), so there is no work to hand it. `scheduledExecutorService`, `keyStore`, `trustStore` are ignored, and the `hostnameVerifier` is stored but never applied.
  3. `WebTarget.register(...)` / `property(...)` change the parent `Client`'s configuration; a target must get its own copy. **Fixed** (cassini#120, `fix/client-target-config`): a target, and each request of it, owns a `CassiniClientConfiguration.copy()`; `ClientBuilder.build()`/`withConfig` copy too.
  4. A registered `Feature` is never configured, and `Configuration.isEnabled(...)` is always `false`. **Fixed** (same branch): a `Feature` is configured when registered (client, builder or target) against that configuration, `isEnabled` reports it; test `TargetConfigurationTest`.
  5. Closing a `Client` only blocks new `target(...)` calls: existing targets and builders keep sending requests, `register`/`property` keep working. **Fixed** (cassini#120, `fix/client-close-semantics`): `CassiniClient.ensureOpen()` guards the client's `register`/`property`, every target (creation, request, and through the client its `register`/`property`), `Invocation.Builder` sends and the async invoker; test `ClosedClientTest`.
  6. `register(Class, int priority)` / `register(Object, int)` take the contracts from `getInterfaces()` of the class itself: a provider that inherits its interface from a superclass gets no contract. **Fixed** (cassini#120, `fix/client-register-contracts`): `CassiniClientConfiguration.contractsOf` walks the superclasses and the super-interfaces; test `RegisterContractsTest`.
  7. `ClientRequestContext.getEntityStream()` / `setEntityStream()` throw `UnsupportedOperationException`; `getCookies()`, `getDate()`, `getLanguage()` ignore the headers; the request header names are case-sensitive. `ClientResponseContext` and the client `Response` ignore `Set-Cookie`, `Date`, `Link`, `ETag`, `Allow` and `Last-Modified`. **Request side fixed** (cassini#120, `fix/client-request-context`): the request headers are a case-insensitive map, `getDate()` / `getLanguage()` / `getCookies()` read the `Date` / `Content-Language` / `Cookie` headers, `getEntityStream()` / `setEntityStream()` give filters the stream the entity is written through (a streamed entity too); test `RequestContextTest`. **Response side fixed** (`fix/client-response-headers`): the client `Response` and the `ClientResponseContext` derive `Set-Cookie` (`NewCookie`), `Date`, `Last-Modified`, `ETag`, `Allow` and `Link` (`getLinks`/`hasLink`/`getLink`/`getLinkBuilder`) from the headers, the context after the filters have changed them; test `ResponseHeadersTest`. Relative links are not resolved against the request URI.
  8. Several cookies are joined into one `Cookie` header with `", "`; RFC 6265 §5.4 uses `"; "`. **Fixed b0d772b** (cassini#120, `fix/client-cookie-separator`).
  9. An `InputStream` entity is sent through its `toString()` (or, with a JSON media type, serialised by JSON-B as an object). The same defect hit `Reader` (sent as `{}`), `StreamingOutput` (`{}`) and `File` (`StackOverflowError` in JSON-B). **Fixed** (cassini#120, `fix/client-inputstream-entity`): the four standard entity types of Jakarta REST 4.0 §4.2.4 are written as their content whatever the media type; an `InputStream` and a `File` are streamed to the `HttpClient`, not buffered. Test: `StreamEntityTest` (6 cases, all red before the fix).
- **Minimal repro**: per item, through `ClientBuilder.newClient()` against `RecordingServer` (cassini-client tests).

## CASSINI-025 — An annotation value with a newline or another control character breaks the APT-generated adapter
- **Date**: 2026-10-28 — **Status**: FIXED
- **Severity**: low. A resource or application whose annotation value carries a control character failed to compile under `cassini-processor` (a ` ` / ` ` would have been carried raw into the generated source).
- **Symptom**: `@DefaultValue("a\nb")` (or the same value in `@ApplicationPath`, `@Path`, `@QueryParam`, `@Produces`...) made javac fail on the generated `$$CassiniAdapter` / `$$CassiniApplication` with `unclosed string literal`.
- **Minimal repro**: `@Path("/ctl") class R { @QueryParam("q") @DefaultValue("a\nb") public String q; @GET public String get() { return q; } }` compiled with `cassini-processor` on the processor path.
- **Cause**: `CassiniResourceProcessor.escapeString` escaped only `\` and `"` when rendering values into generated string literals. The Maven-plugin and runtime generators write Class-File constants directly and are not affected.
- **Fix**: escape `\n`, `\r`, `\t`, `\b`, `\f` by name and any other character below 0x20, 0x7f, U+2028 and U+2029 as a Unicode escape, like grimm/dirac `lit` and foy `literal`. Regression test: `CassiniResourceProcessorTest.controlCharactersInAnnotationValuesSurviveTheGeneratedSource`.

## CASSINI-026 — A multipart part whose `filename` comes before `name` gets the file name as its name
- **Date**: 2026-10-28 — **Status**: FIXED
- **Severity**: medium. RFC 7578 does not order the `Content-Disposition` parameters; a client that writes `filename` first had every file part delivered under the wrong name (an `@FormParam` lookup by name then missed it).
- **Symptom**: `Content-Disposition: form-data; filename="photo.png"; name="upload"` was read as a part named `photo.png`.
- **Minimal repro**: `MultipartFormDataProvider.readFrom` on a body with the header above (boundary `XyZ`).
- **Cause**: `MultipartFormDataProvider.paramValue` looked up `name=` with a plain `indexOf`, which matched inside `filename=`.
- **Fix**: a parameter only matches at the start of the header value or after a `;`. Regression test: `MultipartFormDataProviderTest.theNameIsFoundWhenTheFileNameComesFirst`.

## CASSINI-027 — `EntityPart.Builder` departs from its contract
- **Date**: 2026-10-28 — **Status**: FIXED
- **Severity**: low. The official TCK does not exercise these cases (2670 run, 0 failures, 131 skipped before and after).
- **Symptom**: four departures from the `jakarta.ws.rs.core.EntityPart.Builder` Javadoc: (1) `mediaType(TEXT_PLAIN).fileName("a.txt")` built an `application/octet-stream` part, while octet-stream is only the default "if not otherwise specified"; (2) `mediaType(null)`, `headers(null)`, `header(null, …)`, `fileName(null)`, `content(null, stream)` were accepted instead of throwing `IllegalArgumentException`; (3) `header(name, …)` appended to the previous values instead of replacing them; (4) `build()` without content returned an empty part instead of throwing `IllegalStateException`.
- **Minimal repro**: `EntityPart.withName("p").mediaType(MediaType.TEXT_PLAIN_TYPE).fileName("a.txt").content("x").build().getMediaType()` gives `application/octet-stream`.
- **Cause**: `CassiniEntityPartBuilder` stored `text/plain` as an initial value and overwrote it when a file name was set, delegated `header` to `MultivaluedMap.add`, and had no argument or content checks.
- **Fix**: the media type is resolved at `build()` (the explicit one, else octet-stream with a file name, else text/plain); null arguments throw `IllegalArgumentException`; `header` replaces, `headers` calls it per entry; `build()` without content throws `IllegalStateException`. Regression tests: `CassiniEntityPartTest.anExplicitTextPlainMediaTypeSurvivesAFileName`, `nullArgumentsAreRejected`, `aHeaderReplacesTheValuesOfAPreviousOneOfTheSameName`, `buildingAPartWithoutContentIsAnIllegalState`.

## CASSINI-028 — A `CassiniStack` built from an `Application` hides it from its requests
- **Date**: 2026-10-28 — **Status**: FIXED
- **Severity**: medium. Affects every stack built through `CassiniStack.builder().application(app)`, among them the SE bootstrap (`ChappeRuntimeDelegate`). The official TCK deploys through `CassiniTestHarness`, which seeds the application itself, so it did not see it.
- **Symptom**: (1) `@Context Application` in a resource was a bare `new Application()` instead of the deployed one; (2) `UriInfo.getMatchedResourceTemplate()` left out the `@ApplicationPath` (`/r/{id}` instead of `/shop/r/{id}`); once the application was seeded, (3) `@ApplicationPath("/")` gave `//r/{id}`.
- **Minimal repro**: `new CassiniStackBuilderImpl().application(new ShopApplication()).build()` (`@ApplicationPath("shop/")`) and `GET /r/7` on a resource returning `uriInfo.getMatchedResourceTemplate()`.
- **Cause**: `CassiniStackBuilderImpl.build()` never called `Invoker.setApplication`, so the request scope carried no application; `CassiniUriInfo.getMatchedResourceTemplate` kept the trailing `/` of a root application path.
- **Fix**: the builder seeds the invoker with its application; a trailing `/` of the application path is always dropped. Regression tests: `UriInfoTest.theMatchedTemplateStartsWithTheApplicationPath`, `UriInfoTest.contextApplicationIsTheApplicationOfTheStack`.

## CASSINI-029 — `UriInfo` decodes `+` as a space and keeps matrix parameters inside the path segments
- **Date**: 2026-10-28 — **Status**: FIXED
- **Severity**: low to medium. `@PathParam PathSegment` injection was right (`ParamValueConverter.parsePathSegment`); only `UriInfo` was affected.
- **Symptom**: (1) `UriInfo.getPath()` of `/a+b%20c` was `a b c` instead of `a+b c` (and so were `getPathSegments()` and `getMatchedURIs()`); (2) `getPathSegments()` of `/cars;color=red/x` gave a first segment whose path was `cars;color=red` and whose matrix parameters were empty.
- **Minimal repro**: `new CassiniUriInfo(InMemoryExchange.get("/cars;color=red/x"), "", Map.of()).getPathSegments().get(0).getPath()`.
- **Cause**: `CassiniUriInfo` decoded paths with `URLDecoder`, a form decoder that turns `+` into a space, and built each segment from the decoded text without parsing its matrix parameters (a stub `SimpleSegment` with an empty map).
- **Fix**: path decoding only decodes escaped octets (`+` is kept, as `getPathParameters` already did); segments are split on the raw path, parsed with `ParamValueConverter.parsePathSegment`, then decoded part by part. Regression tests: `UriInfoTest.aPlusSignInThePathIsKeptByTheDecoding`, `UriInfoTest.pathSegmentsSeparateTheirMatrixParameters`.

## CASSINI-030 — `SseBroadcaster.close(false)` closes the sinks, and a closed broadcaster keeps accepting calls
- **Date**: 2026-10-28 — **Status**: FIXED
- **Severity**: low. An application that hands its sinks over to another broadcaster lost them on `close(false)`.
- **Symptom**: (1) `close(false)` closed every registered `SseEventSink`; (2) once closed, `broadcast`, `onClose` and `onError` were silently accepted instead of throwing `IllegalStateException`; (3) a second `close()` ran the close listeners again over the (empty) sink list — harmless, but not "no effect".
- **Minimal repro**: `var b = new CassiniSse().newBroadcaster(); b.register(sink); b.close(false); sink.isClosed()` is `true`.
- **Cause**: `CassiniSseBroadcaster.close(boolean)` delegated to `close()` whatever the flag, and only `register` checked the closed state.
- **Fix**: `close()` is `close(true)`; the sinks are closed only when cascading; the first close wins (`AtomicBoolean`) and every other method throws `IllegalStateException` afterwards, as the `SseBroadcaster` Javadoc specifies. Regression tests: `SseTest.aNonCascadingCloseLeavesTheSinksOpen`, `SseTest.aClosedBroadcasterRefusesEveryOtherCallAndIgnoresAnotherClose`.

## CASSINI-031 — `UriInfo.relativize` ignores its specified algorithm, and the filter's `UriInfo` view keeps the query in the absolute path
- **Date**: 2026-10-28 — **Status**: FIXED
- **Severity**: low. Neither case is reached by the official TCK.
- **Symptom**: (1) with base `http://example.com:8080/app/root/` and request `…/app/root/a/b/c/resource.html`, `uriInfo.relativize(URI.create("a/b/c/d/file.txt"))` returned `a/b/c/d/file.txt` instead of `d/file.txt` (the first example of the Javadoc); (2) after `ContainerRequestContext.setRequestUri(base, "…/items/9?x=1")`, `getUriInfo().getAbsolutePath()` kept `?x=1` and `getMatchedResourceTemplate()` was always `""`.
- **Minimal repro**: `new CassiniUriInfo(InMemoryExchange.get("example.com:8080", "/app/root/a/b/c/resource.html"), "/app/root", Map.of()).relativize(URI.create("a/b/c/d/file.txt"))`.
- **Cause**: `CassiniUriInfo.relativize` called `URI.relativize` on the request URI itself, without resolving a relative argument first and against the request's file rather than its directory; `CassiniRequestContext.MutableUriInfoView` returned the request URI as the absolute path and a constant template.
- **Fix**: `relativize` resolves a relative URI against the base URI, then relativizes it against the request URI's directory (the resolved URI is returned when they share no prefix); the view drops the query and fragment from the absolute path and delegates the matched template. Regression tests: `UriInfoTest.relativizeFollowsTheExamplesOfTheSpecification`, `CassiniRequestContextTest.theChangedUriInfoKeepsTheAbsolutePathAndTheMatchedTemplate`.

## CASSINI-032 — A generated adapter called another resource method than the one matched, after a generic override
- **Date**: 2026-10-29 — **Status**: FIXED
- **Severity**: high for an application whose adapters are `ServiceLoader` providers (the module-path, zero-export setup of `cassini-examples-jdkhttp`): the request ran another method of the resource. Not reached by the official TCK, whose adapters are not providers.
- **Symptom**: a resource `Generic extends Base<String>` overriding `get(String)` and declaring `put(String)`: the `PUT` request ran `get`. A resource inheriting a public method from a package-private superclass had that method called reflectively on every request.
- **Minimal repro**: `GeneratedAdapterMethodIdsTest` (cassini-core), route `SERVICE`: `id 1 of Generic.put(String) called another method ==> expected: <put:x> but was: <get:x>`.
- **Cause**: two copies of the method numbering. The processor's adapter numbers the methods it walks up the hierarchy, keeping the erased `Base.get(Object)` next to the override; for a `ServiceLoader` provider the registry numbered them again with `RuntimeAdapterGenerator.collectMethods` (`getMethods()`, bridges dropped), so every id after the extra method was shifted. The run-time numbering also dropped the bridge that is the only public form of a method inherited from a package-private class. An adapter found by `Class.forName` (processor or Maven plugin) got no ids at all: every call was reflective.
- **Fix**: each adapter states which method each id calls (`ResourceAdapter.methodKeys()`, name plus JVM parameter descriptor, emitted by the processor and by `RuntimeAdapterGenerator`, so by the Maven plugin too); `AdapterRegistry` reads the ids from it on every route, and numbers them itself only for a provider generated before this change. `collectMethods` keeps a bridge no other method of the same parameters stands for. Regression test: `GeneratedAdapterMethodIdsTest` (generic override and package-private superclass, through a provider, an adapter found by name, and one generated at run time).

## CASSINI-033 — An abstract resource, or an inner one, gets an adapter that calls `new` on it
- **Date**: 2026-10-29 — **Status**: FIXED
- **Severity**: medium. An abstract `@Path` class made the application fail to compile under the annotation processor; at run time the generated adapter threw `InstantiationError` instead of falling back.
- **Symptom**: (1) with the processor, `@Path("/abstract") public abstract class Abstract { … }` failed with `Abstract is abstract; cannot be instantiated` in the generated `Abstract$$CassiniAdapter`; an inner (non-static) `@Path` class failed the same way, `new Outer.Inner()` needing an enclosing instance; (2) the runtime generator (and so the Maven plugin) emitted `new` for an abstract class, and `newInstance()` threw `InstantiationError` rather than `UnsupportedOperationException`, the signal callers fall back on.
- **Minimal repro**: `RuntimeAdapterGenerator.generate(AbstractResource.class)`, then `newInstance()` on the adapter.
- **Cause**: both `hasAccessibleNoArgCtor` checked only the constructor's visibility, not whether the class can be instantiated at all.
- **Fix**: the processor's check also returns `false` for an abstract type and for a non-static member type; the runtime one for an abstract class (an inner class already has no no-arg constructor). Such adapters inherit the default `newInstance()`. Regression tests: `CassiniResourceProcessorTest.anAbstractResourceCompilesAndGetsNoNewInstance`, `CassiniResourceProcessorTest.anInnerResourceCompilesAndGetsNoNewInstance`, `RuntimeAdapterGeneratorTest.anAbstractResourceGetsNoNewInstance`.

## CASSINI-034 — The Maven plugin keeps the adapter of a previous build
- **Date**: 2026-10-29 — **Status**: FIXED
- **Severity**: medium. Only a build without `clean` is affected, but the result is silent: the application runs with an adapter of an older version of its resource.
- **Symptom**: `cassini:generate` skipped every resource whose `$$CassiniAdapter.class` already existed. Built again after a change to the resource, the project kept the adapter of the previous build: a new `@Context` or `@*Param` field was not injected by it, and a new or changed method had no id (reflective dispatch).
- **Minimal repro**: run the goal on a `target/classes` that already holds an adapter for the resource (`GenerateAdaptersMojoExecuteTest.aSecondRunReplacesTheAdapterItGeneratedBefore`).
- **Cause**: `GenerateAdaptersMojo.writeAdapter` returned on `dest.exists()`, to leave the annotation processor's adapter in place, without telling it from one the goal itself wrote.
- **Fix**: the goal keeps only an adapter javac compiled from the processor's source (it carries a `SourceFile` attribute, and javac regenerates it with its resource); it writes its own again on every run. Regression tests: `GenerateAdaptersMojoExecuteTest.aSecondRunReplacesTheAdapterItGeneratedBefore`, `anAdapterCompiledFromTheProcessorsSourceIsKept`.

## CASSINI-035 — A method that inherits its JAX-RS annotations is lost or loses its parameters, depending on how the routes are built
- **Date**: 2026-10-29 — **Status**: FIXED
- **Severity**: high. §3.6 inheritance (an implementation of an annotated interface, a common way to share a REST contract) answered 404 or ran without its parameters, and each way of building the routes failed differently.
- **Symptom**: with `interface Api { @GET @Path("{id}") String get(@PathParam("id") String id); }` and `@Path("/r") class R implements Api { public String get(String id) … }`:
  - **annotation processor**: `GET /r/7` answered 404, the processor reading the annotations of the implementation only;
  - **Maven plugin** (`RuntimeRoutesGenerator`): the route existed, but `id` did not receive the path segment (`interface:` instead of `interface:7`): `RouteRegistry` resolved the table's method on the resource class, whose parameters carry no `@PathParam`; and a method inherited without being overridden was not found by `getDeclaredMethod`, which sent the whole class back to the scanner with a warning;
  - **scanner**: annotations were looked up on the direct interfaces only, so a method of a superinterface or of a superclass's interface answered 404;
  - a locator, or a resource method of the class a locator returns at run time, inherited none (`DynamicLocatorDispatch` and `RuntimeRoutesGenerator.hasSubResourceLocators` read `Method.getAnnotation` on the implementation).
- **Minimal repro**: `InheritedResourceMethodsTest`, through each of the three route sources.
- **Cause**: four copies of the rule choosing a class's resource methods, three without §3.6 or with only part of it.
- **Fix**: one rule, `ResourceScanner.resourceMethods`/`annotatedMethod` (its own annotations when the method or a parameter has any, else those of the overridden method, superclasses first, then every interface at any depth), used by the scanner, the run-time locator dispatch, the route generator and `RouteRegistry` (which now resolves the table's method with `getMethod`, inherited ones included). The processor applies the same rule on the language model. Regression tests: `InheritedResourceMethodsTest`.

## CASSINI-036 — A provider without `@Context` field gets an adapter generated at run time on the first request
- **Date**: 2026-10-30 — **Status**: FIXED
- **Severity**: medium. No wrong answer, but the ecosystem rule (generate at build time, never at run time) is broken on the first request of almost every application, the first request pays a Class-File generation, and the path has no ahead-of-time equivalent. Reported by the vidocq-benchmarks session (cassini#100).
- **Symptom**: the first JSON request logs `CASSINI — RUNTIME CODE GENERATION FALLBACK … io.vidocq.cassini.internal.json.CassiniJsonbReaderWriter`, and the warning tells the application to add `cassini-processor`, which cannot help: the class is Cassini's own. The filters of other bricks built without the processor (Humboldt, Cervantes, the CDI request-scope filters) take the same path.
- **Minimal repro**: `ProviderContextInjectionTest`: a JSON resource whose adapter comes from the build, plus a post-matching filter without `@Context`; two requests generated two adapters at run time.
- **Cause**: `Invoker.injectProviderContexts` looked an adapter up for every reader, writer and post-matching filter it injects `@Context` fields into, and skipped only the `MessageBodyRegistry$` built-ins.
- **Fix**: a provider whose class declares no injectable field (`InjectableFields.declaredBy`, read once per class, as for resources) is not injected and gets no adapter. A provider with `@Context` fields keeps its build-time adapter, or the run-time one as before (cassini#100, option 3, still open).

## CASSINI-037 — The `@Context` fields of a response filter are not injected
- **Date**: 2026-10-30 — **Status**: FIXED c423a55 (cassini#119, `fix/response-filter-context`; `ProviderContextInjectionTest`). `ResponsePipeline` now calls `injectProviderContexts` before each response filter
- **Severity**: to assess (§9.2: the `@Context` fields of every provider are injected).
- **Symptom**: a `ContainerResponseFilter` with `@Context private UriInfo uriInfo` sees `null` in `filter(request, response)`, on the in-memory stack. Post-matching request filters, readers and writers are injected (`Invoker.injectProviderContexts`); response filters do not go through it.
- **Minimal repro**: a response filter putting `uriInfo == null ? "none" : uriInfo.getPath()` in a header answers `none` (found while writing `ProviderContextInjectionTest`).
- **Cause hypothesis**: `injectProviderContexts` is called for post-matching filters (`Invoker`) and message body providers (`ResponsePipeline`), not for response filters. To check against the TCK, which may cover it through CDI-managed filters.

## CASSINI-038 — The default scopes are logged at INFO at every build of an application
- **Date**: 2026-10-30 — **Status**: FIXED (fix/build-noise-26)
- **Severity**: low (build noise)
- **Symptom**: Vauban runs `CassiniScopeExtension` when an application compiles, and each resource or provider
  without a scope printed an INFO line: `@Path class … has no CDI scope, defaulting to @RequestScoped`,
  `@Provider class … has no CDI scope, defaulting to @Dependent` (vidocq BUG-20261030-26).
- **Cause**: these are the specification's defaults, logged at INFO.
- **Fix**: logged at DEBUG. Regression test: `CassiniScopeExtensionTest.theDefaultScopesAreSaidAtDebugLevel`.

## CASSINI-039 — Every request re-reads its resource method's annotations and converts parameters by reflection
- **Date**: 2026-11-02 — **Status**: FIXED (fix/reflection-per-request)
- **Severity**: medium (reflection on the request path of a Vidocq application)
- **Symptom**: the Vidocq reflection audit (tracing agent on a multi-extension application) shows, at every request,
  `getAnnotation(@Encoded)` on the resource method and its class (`ParamExtractor.resolve`), `getAnnotations()` and
  `getGenericReturnType()` on the method (`ResponsePipeline`), and `Method.invoke` / `Constructor.newInstance` for a
  parameter converted through its `valueOf` / `fromString` / `String` constructor (`ParamValueConverter`).
- **Cause**: these values never change but were read again for each request; the converter factory, resolved once
  per type, still called the factory by reflection.
- **Fix**: `MethodParameters` (already cached per method) holds `encoded()`, `annotations()` and
  `genericReturnType()`; the class-level `@Encoded` is cached in a `ClassValue`; the arrays handed to writers and
  filters are copies. The converter calls a `MethodHandle` resolved once (Cassini reads the type's module first, as
  reflection did implicitly), and falls back to the reflective call only when the handle cannot be resolved.
  Regression test: `PerRequestReflectionTest` (red first); cassini-core 531 tests; official REST TCK 2670 run,
  0 failures, 131 skipped.

## CASSINI-040 — Every provider is read by reflection at startup, even when `cassini-processor` ran on it
- **Date**: 2026-11-02 — **Status**: FIXED (feat/provider-descriptors)
- **Severity**: low (startup reflection in the standard Vidocq path; no wrong answer)
- **Symptom**: the Vidocq reflection audit shows, at startup, `getAnnotation(@Priority / @PreMatching / @Produces /
  @Consumes / @ConstrainedTo)`, `getAnnotations()` for name bindings and `getGenericInterfaces()` for the type
  argument of every filter, interceptor, reader, writer, exception mapper, context resolver and param converter
  provider (`FilterEntry`, `FilterRegistry`, `MessageBodyRegistry`, `ExceptionMapperRegistry`, `CassiniProviders`), and
  `ResponsePipeline.producedMediaType` reads the writer's `@Produces` on every response without a content type.
  Resources had build-time adapters; providers had nothing.
- **Fix**: `cassini-processor` generates a `<provider>$$CassiniProvider` (`io.vidocq.cassini.spi.gen.ProviderDescriptor`,
  listed in `META-INF/services`) holding what those sites read: priority, media types, pre-matching, constraint, name
  bindings and the plain-class type arguments of the provider interfaces the class implements itself. Each site asks
  `ProviderDescriptors.of(class)` first (found by type through the `ServiceLoader` of the class's layer, or of its class
  loader outside a named module, cached in a `ClassValue`) and keeps its reflective reading, unchanged, when there is
  no descriptor (Weld, Open Liberty, a class built without the processor, a provider registered by hand). The adapter
  lookup also asks the layer of the resource class before `Class.forName`. Tests: `ProviderDescriptorsTest` (5 red
  before the wiring, 2 fallback tests green both ways), `CassiniResourceProcessorTest.aProviderGetsADescriptorOfWhatCassiniReadsOnIt`.
- **Limit**: a brick on the boot module path (Humboldt, Cervantes, ...) needs `provides
  io.vidocq.cassini.spi.gen.ProviderDescriptor with ...` in its `module-info.java` for the descriptors to be found;
  until then it keeps the reflective reading. With it, the audit application reads 18 fewer annotations for Humboldt's
  three filters alone.

## CASSINI-041 — Sub-resource locators and `@BeanParam` classes run on run-time adapters or reflection
- **Date**: 2026-11-02 — **Status**: FIXED (feat/build-time-subresources)
- **Severity**: low (run-time code generation and per-request reflection in the standard Vidocq path; no wrong answer)
- **Symptom**: every request through a sub-resource locator called it with `Method.invoke` (`Invoker.walkLocators`,
  `DynamicLocatorDispatch.walkLocators`); a `@BeanParam` method parameter was created with
  `getDeclaredConstructor().newInstance()` and filled by `FieldInjector` on every request; the sub-resource classes
  and `@BeanParam` classes, which carry neither `@Path` nor `@Provider`, got their adapter from the Class-File
  generator at run time, with the `RUNTIME CODE GENERATION FALLBACK` warning.
- **Fix**: `cassini-processor` also generates the adapter of each class of the build a processed class reaches: the
  types of its `@BeanParam` fields and parameters and the declared return types of its locators, transitively. A
  locator is called through the adapter of the class that declares it when that adapter numbers it, by
  `Method.invoke` otherwise; a `@BeanParam` parameter whose class has a build-time adapter is created and filled
  through it, by reflection otherwise. Tests: `LocatorDispatchTest` (red first for the locator and the bean
  parameter; the bean without build-time adapter keeps the reflective path),
  `CassiniResourceProcessorTest.theBeanParamAndSubResourceClassesAResourceReachesGetTheirAdapter`.

## CASSINI-042 — Generated routes still resolve their methods by name and scan annotations at startup
- **Date**: 2026-11-02 — **Status**: FIXED (feat/startup-from-build-data)
- **Severity**: low (startup reflection in the standard Vidocq path; no wrong answer)
- **Symptom**: the Vidocq reflection audit shows, for every route a `$$CassiniRoutes` describes, `Class.forName` for
  each parameter type (`RouteRegistry.resolveType`), `getMethod`, then `ResourceScanner.annotatedMethod`, whose
  `hasJaxrsAnnotation` reads the annotations of the method and of its parameters (46 annotation parsings on the
  audit application).
- **Fix**: `RouteDescriptor` carries two more components the processor fills: the parameter types as class literals
  and the class whose declaration of the method carries its annotations (§3.6). `RouteRegistry.resolveMethod` then
  calls one `getDeclaredMethod` on that class. A descriptor without them (a route generated by an older
  processor, a type the generated class cannot name, a generic supertype erasing differently) is resolved by name
  and scanned, as before; the 8-argument constructor stays for older generated classes. Tests:
  `RouteMethodResolutionTest` (red first), `CassiniResourceProcessorTest.aGeneratedRouteNamesItsParameterTypesAndItsAnnotatedClass`.

## CASSINI-043 — The Vauban bean provider reads two annotations of every bean by reflection at startup
- **Date**: 2026-11-02 — **Status**: FIXED (feat/beans-from-index, with vauban `VaubanContainer.indexedAnnotationNames`)
- **Severity**: low (startup reflection in the standard Vidocq path; no wrong answer)
- **Symptom**: `VaubanBeanProvider.getResourceClasses` calls `isAnnotationPresent(@Path)` and
  `isAnnotationPresent(@Provider)` on the class of every bean of the deployment: 300 + 300 reflective calls on the
  audit application, for 4 resources and 6 providers.
- **Fix**: the annotations come from the deployment's index, which Vauban built from the class files
  (`VaubanContainer.indexedAnnotationNames`). A class the index does not know is read by reflection, as before.
  Tests: `ResourceClassesFromIndexTest`. Audit application: 600 reflective calls at startup → 0.
  `cassini-cdi-vauban` also runs `cassini-processor` and provides the descriptor of `VaubanRequestScopeFilter`
  (`ProviderDescriptorProvidesTest`): with Cervantes and Humboldt shipping theirs, `FilterEntry` reads no annotation
  by reflection on the audit application (80 → 0).

## CASSINI-044 — A hot reload keeps the previous application layers reachable through the default JSON-B instances
- **Date**: 2026-11-02 — **Status**: FIXED (fix/layer-leaks-cassini)
- **Severity**: medium in `vidocq dev` (about 0.8 MB of heap per hot reload, every discarded layer retained); none
  in production
- **Symptom**: the static audit of vidocq-workspace#40 (item 25): 15 hot reloads of the audit application left 16
  live `VaubanClassLoader`s instead of one. The heap dump showed `CassiniJsonbReaderWriter.DefaultJsonb.INSTANCE`, a
  JVM-wide static, holding a Champollion `Jsonb` whose index lists the generated JSON-B bindings of the first layer
  (it is built by the first JSON request, under that layer's context class loader). `ClientEntityJsonb.DEFAULT` of
  `cassini-client` did the same. The route and adapter caches were already reset by `CassiniMaintenance`.
- **Repro**: `CassiniMaintenanceLayerLeakTest` (a layer whose generated binding wrote an entity is collectable once
  `resetDiscoveryCaches()` ran; it failed before the fix) and `ClientEntityJsonbResetTest`.
- **Fix**: the default instance of the built-in reader/writer is an `AtomicReference` that
  `CassiniMaintenance.resetDiscoveryCaches()` clears (the Vidocq Cassini extension already calls it when a layer
  stops); `cassini-client` registers its own reset through the new `CassiniMaintenance.onReset(Runnable)`. The next
  JSON request builds a `Jsonb` for the layer then current. Champollion's later-layer lookup (champollion#82) stays
  as a safety net, and the reflective paths are unchanged.

## CASSINI-045 — The server reads request header names case-sensitively: `authorization` in lower case gets a 401
- **Date**: 2026-11-13 — **Status**: FIXED (fix/request-headers-case-insensitive)
- **Severity**: high. HTTP field names are case-insensitive (RFC 9110 §5.1), and HTTP/2 sends them all in lower case;
  a filter looking up `Authorization` missed an `authorization` header, so a token sent that way was never read.
- **Symptom**: the tutorial replay (Vidocq): `curl -H "authorization: Bearer $T"` on a resource Cervantes protects
  answers 401, `-H "Authorization: Bearer $T"` answers 200; Cervantes' `JwtAuthenticationFilter` reads the token with
  `ContainerRequestContext#getHeaderString(AUTHORIZATION)`. `HttpHeaders#getHeaderString` was right (it asks the
  exchange by name); `ContainerRequestContext#getHeaderString`, `#getHeaders`, `HttpHeaders#getRequestHeaders` and
  the headers handed to a `MessageBodyReader` were not.
- **Minimal repro**: `CassiniRequestContextTest.headerNamesAreCaseInsensitiveWhateverCaseTheClientSent`,
  `CassiniHttpHeadersTest.request_headers_are_read_case_insensitively` (`getRequestHeaders().get("x-a")`) and
  `CassiniResponseContextTest.header_strings_are_read_case_insensitively`, red before the fix.
- **Cause**: `CassiniRequestContext`, `CassiniHttpHeaders.getRequestHeaders` and `MessageBodyRegistry.adaptExchangeHeaders`
  each copied the exchange headers into a `MultivaluedHashMap`, keyed by the spelling the client sent. On the response
  side, `CassiniResponseContext.getHeaderString` and `getStringHeaders` did the same, while its `containsHeaderString`
  already ignored the case. The client side had the same flaw (CASSINI-019).
- **Fix**: `HeaderMaps` builds the request header maps over a `TreeMap` ordered by `String.CASE_INSENSITIVE_ORDER`,
  as `cassini-client` does; the three copies use it, and the response context looks a header up ignoring the case.

## CASSINI-046 — An unmapped exception answers a 500 whose body is the exception's message
- **Date**: 2026-11-14 — **Status**: FIXED (fix/unmapped-error-body)
- **Severity**: high. Information disclosure (CWE-209): any failure no `ExceptionMapper` handles sent its message to
  the client, in every mode; a persistence error carries SQL text and the values of the request.
- **Symptom**: the tutorial replay (Vidocq, 0.4.0-SNAPSHOT, `prod` mode): a resource method throwing
  `new AssertionError("boom from a handler")` answers `500` with the body `java.lang.AssertionError: boom from a
  handler`; a duplicate insert answers `500` with `EntityExistsException: ERROR: duplicate key value violates unique
  constraint "books_isbn_key"` and the SQL detail `Key (isbn)=(9780321349606) already exists.` The failure was logged
  at `ERROR` (`Cassini dispatch error`), as it should be.
- **Minimal repro**: `UnmappedFailureResponseTest` (cassini-core): an unmapped `RuntimeException`, an `Error` and a
  checked exception thrown by a resource method, an `AsyncResponse` resumed with an exception or an `Error`, a failed
  `CompletionStage`, a `MessageBodyWriter` that throws, an SSE method that throws, a sub-resource reached through a
  dynamic locator, a `WebApplicationException` without a response, and `Invoker.renderThrowable`: all red before the
  fix, on the body.
- **Cause**: `DefaultCassiniHttpAdapter.dispatch` answered every exception that reached it with a `text/plain` 500
  whose body was `e.getMessage()`. An `Error` reaches it too: `Invoker.handleResourceThrowable` (and
  `Invocation.fail` for an asynchronous result, `DynamicLocatorDispatch.asException` for a locator) wraps any
  `Throwable` that is not an `Exception` in `new IllegalStateException(cause)`, whose message is `cause.toString()`,
  hence `java.lang.AssertionError: …`. `Invoker.renderThrowable` and `renderWebAppException` wrote the message the
  same way, for a failure without a mapper or a response of its own.
- **Fix**: the three answer a bare `500`: no body, no `Content-Type`, as the Chappe and JDK transports answer theirs
  (CASSINI-005, CASSINI-006) and as an unmapped `WebApplicationException` without an entity is answered. The log line
  keeps the message and the stack trace. A `WebApplicationException`'s own response and a mapper's response are
  unchanged. The wrapping of an `Error` stays: Cassini acts as the container of §3.3.4 and answers the 500 itself.
  Rethrown as it is, the `Error` would reach code that catches exceptions only (`JdkHttpAdapter`, the inline dispatch
  of `ChappeHttpAdapter`, Chappe's `HttpConnection.dispatch`), where nothing turns it into a 500.

## CASSINI-047 — An exception thrown by a sub-resource locator skips the ExceptionMappers
- **Date**: 2026-11-14 — **Status**: FIXED (fix/locator-exceptions-port-race)
- **Severity**: medium. Spec conformance (Jakarta REST 4.0 §3.3.4, §4.4): the application's mapper never ran, and the
  client got a bare `500` instead of the mapper's response.
- **Symptom**: a sub-resource locator that throws an exception other than a `WebApplicationException` (a
  `RuntimeException` the application maps, for example `EntityNotFoundException` → `404`) answers `500`, and the
  failure is logged as unmapped (`Cassini dispatch error`), on both locator paths: a locator that returns a typed
  sub-resource (static routes) and one that returns `Object` (dynamic dispatch). The same exception thrown by a method
  of the located sub-resource reaches its mapper. A `WebApplicationException` thrown by a locator was answered right.
  The official TCK does not exercise it: no locator of the TCK throws from its body; its locator tests only throw from
  the conversion of a locator parameter, which Cassini turns into a `WebApplicationException` before the locator runs.
- **Minimal repro**: `LocatorExceptionMappingTest.anExceptionTheApplicationMapsIsAnsweredByItsMapper` (cassini-core),
  `/typed/mapped` and `/object/mapped`, and `theResponseFiltersRunOnTheMappedResponseOfAStaticLocatorAsOnAResourceMethods`
  on `/typed/mapped`: red before the fix (`500` instead of the mapper's `409`).
- **Cause**: `Invoker.resolveTarget` (static path) and `DynamicLocatorDispatch.walkThenDispatch` (dynamic path) handed
  only a `WebApplicationException` cause of the locator's `InvocationTargetException` to `renderWebAppException`; any
  other cause was thrown at once (`Invocation.fail`, `asException`), without asking `ExceptionMapperRegistry`, so it
  reached `DefaultCassiniHttpAdapter.dispatch` as an unmapped failure.
- **Fix**: both go through the handler of a resource method's failure: `Invoker.mapResourceThrowable` (extracted from
  `handleResourceThrowable`, and reused by `mapFilterThrowable`) renders a `WebApplicationException` or asks the
  mappers, and runs the response filters on the static path. Only a failure no mapper handles is still thrown, and
  answered with the bare `500` of CASSINI-046.

## CASSINI-048 — Test servers pick a free port, close it, then bind it again: another process can take it
- **Date**: 2026-11-14 — **Status**: FIXED in the tests (fix/locator-exceptions-port-race); see "Not covered"
- **Severity**: low (test only). A flaky build, not a defect of what ships.
- **Symptom**: a full `-Pquality` build failed once in `cassini-examples-jdkhttp`: all 7 tests of `TodoResourceTest`
  errored with `java.net.ProtocolException: Invalid status line: "SSH-2.0-OpenSSH_10.0p2 Debian-7+deb13u4"`. The
  client reached an SSH server on the test server's port.
- **Minimal repro**: the race cannot be forced from a test. The mechanism was checked on macOS with JDK 25 (scratch
  program, not committed): a listener on `127.0.0.1:P` does not stop the JDK `HttpServer` from binding `*:P`, and a
  client of `127.0.0.1:P` then reaches that listener; a listener on `127.0.0.1` can also bind the port of a running
  `*:P` server and take its `127.0.0.1` clients. Only a server bound on `127.0.0.1:0` refuses a second listener on its
  port (`BindException`).
- **Cause**: the test servers took a port with `new ServerSocket(0)`, closed it, and had the server bind it later.
  Another process could take the port in between. In `cassini-examples-jdkhttp` the server then bound the wildcard
  address (`JdkHttpAdapter.serve(int)`), so a listener on `127.0.0.1` shadowed it silently instead of failing the
  bind.
- **Fix**: each server binds port 0 itself and the test reads the port it got. Chappe servers (`cassini-examples-vauban`,
  `cassini-it-vauban`, `cassini-it-weld`) bind `127.0.0.1` on port 0 and read `Server.port()`.
  `cassini-examples-jdkhttp` binds its `HttpServer` on `127.0.0.1:0` with `JdkHttpAdapter.asHandler()`, since
  `serve(int)` binds every address. `cassini-examples-chappe` asks `SeBootstrap` for `FREE_PORT` and reads
  `configuration().port()`.
- **Not covered**: the same pick-then-bind remains in main code. `ChappeRuntimeDelegate.portOf` and
  `JdkHttpRuntimeDelegate` (`SeBootstrap` with `FREE_PORT` or no port) pick a port on `127.0.0.1` and bind it again,
  the JDK one on every address; `CassiniTestHarness.freePort` does it for a harness without a fixed port, with
  retries. `CassiniTestHarnessTest.aFixedPortIsUsedAsGiven` needs a free port number before the start, by design.
  Fixed by CASSINI-049.

## CASSINI-049 — `SeBootstrap` with a free port picks the port, builds the application, then binds the port again
- **Date**: 2026-11-14 — **Status**: FIXED (fix/sebootstrap-port-locator-filters)
- **Severity**: low. A race at start, but a silent one with the JDK transport: the instance could serve none of its
  loopback clients while another process answered them.
- **Symptom**: `SeBootstrap` with `FREE_PORT`, or without a port, on the Chappe transport fails to start with
  `ChappeException$ServerException: Failed to bind to 127.0.0.1:<port>` / `BindException` when another process takes
  the port while the application is built. On the JDK transport the start succeeds, and on macOS a client of
  `127.0.0.1:<port>` reaches the other process.
- **Minimal repro**: `ChappeSeBootstrapTest.a_free_port_is_bound_by_the_server_itself_so_nothing_can_take_it_before`
  and `JdkHttpSeBootstrapTest.a_free_port_is_bound_by_the_server_itself_on_the_loopback_address`: the application's
  `getClasses()` listens on `127.0.0.1:<next ephemeral port>`. macOS hands out its ephemeral ports in sequence, so
  that is the port the instance picked: red before the fix (`BindException`; `SocketTimeoutException` from the
  listener that is not the instance). On a system that picks ephemeral ports at random both tests pass either way.
  `JdkHttpSeBootstrapTest.without_a_port_the_instance_listens_on_a_free_loopback_port` was red too (bound on `::`).
- **Cause**: `ChappeRuntimeDelegate.portOf` and the `JdkHttpRuntimeDelegate` instance took a port with
  `new ServerSocket(0, 50, 127.0.0.1)`, closed it, built the `CassiniStack` (which runs the application's code), and
  only then had the server bind that port. The JDK transport bound it on every address (`JdkHttpAdapter.serve(int)`):
  on macOS that bind succeeds beside a listener on `127.0.0.1`, which keeps the loopback clients (CASSINI-048).
  `CassiniTestHarness.freePort` did the same, with five attempts.
- **Fix**: the server binds port 0 itself and the effective configuration reports the port it got (Jakarta REST
  §5.2, `SeBootstrap.Configuration.FREE_PORT`: the implementation chooses a free port; `Instance.configuration()` is
  the actual one). Chappe binds `127.0.0.1:0` and reads `Server.port()`. The JDK transport binds `127.0.0.1:0`
  through the new `JdkHttpAdapter.serve(InetSocketAddress)`, not every address: on macOS a bind of port 0 on every
  address can land on a port a listener holds on `127.0.0.1` (scratch check: 20 times out of 20 on the next ephemeral
  port), as Chappe notes for its own wildcard binds (CHAPPE-004). An explicit port is unchanged: Chappe binds it on
  `127.0.0.1`, the JDK transport on every address. `CassiniTestHarness` binds port 0 and reads `Server.port()`; it
  retries a fixed port only. No test of the harness fails before the fix: Chappe retries a failed bind for about a
  second, and by the harness's next attempt the other processes of the machine have moved the ephemeral counter on.

## CASSINI-050 — A resource method reached through a locator that returns `Object` runs no container filter
- **Date**: 2026-11-14 — **Status**: FIXED (fix/sebootstrap-port-locator-filters)
- **Severity**: medium. Spec conformance (Jakarta REST 4.0 §6.5.1, §6.5.2, §4.4, §6.7.1), and a security one: an
  authentication or authorization filter, global or `@NameBinding`, never ran on such a method, so the method
  answered as if the filter had let the request through.
- **Symptom**: on a sub-resource method reached through a sub-resource locator that returns `Object` (an instance, or
  a `Class` resolved at run time, §3.4.1), no post-matching `ContainerRequestFilter` and no `ContainerResponseFilter`
  ran, global or bound by name to the method or its class; nor did the response filters on the response mapped from
  an exception of the method or of the locator. The same method reached through a locator that returns its type ran
  them all. Seen while fixing CASSINI-047. The official TCK does not exercise it: its only `Object` locators
  (`spec/resource/requestmatching`) are deployed without any container filter, and its `Class` locator
  (`jaxrs21/spec/classsubresourcelocator`) returns a typed `Class<SubResource>`, which Cassini routes statically
  (checked from the TCK 4.0.1 bytecode).
- **Minimal repro**: `LocatorFilterTest` (cassini-core): `/object/item`, `/object/class`, `/object/item/audited`,
  `/object/audited-item`, `/object/item/mapped` (no request filter in the trail, no response filter header) and
  `/object/failing` (no global response filter on the mapped `409`): the 6 dynamic cases red before the fix, the 5
  `/typed` ones green. `LocatorExceptionMappingTest.theResponseFiltersRunOnTheMappedResponseOfALocatorAsOnAResourceMethods`
  now covers the `/object` paths too.
- **Cause**: `DynamicLocatorDispatch.invokeFinalOnInstance` ran the final method through its own copy of a subset of
  `Invoker.invokeInternal` (negotiation, arguments, the call, the `CompletionStage`, the writer) without its
  post-matching filters and response filters, and the locator chain handed no request context to the error paths,
  so a mapped response skipped the response filters too.
- **Fix**: the copy is gone. `Invoker.invokeLocated` runs the final method through the phases of `invokeInternal`,
  the post-matching filters and the response filters included, on the object the chain returned (`resolveTarget`
  takes it as the target instead of resolving one). The filters bound by name are matched on the final method and
  its class, and a filter's `@Context ResourceInfo` names that method. The locator chain gets a post-matching
  request context when there are response filters (`Invoker.locatorContext`): the response mapped from a locator's
  failure goes through the filters bound to the locator's route, the global ones, since no resource method is matched
  yet (§6.7.1). The dynamic path also gains what the static one had and the copy lacked: a failed `CompletionStage`
  and a writer failure reach the `ExceptionMapper`s, and `@Suspended` / `SseEventSink` parameters are prepared.
- **Follow-ups**: three gaps seen here have their own entries: CASSINI-051 (the static path ran the post-matching
  filters before the locators), CASSINI-052 (a `DynamicFeature` never saw the methods of a class a locator returns at
  run time) and CASSINI-053 (no response filter on a `404`, `405`, `406` or `415`).

## CASSINI-051 — With a typed sub-resource locator, the post-matching filters run before the locators
- **Date**: 2026-11-14 — **Status**: FIXED (fix/locator-filter-order)
- **Severity**: low. Spec conformance (Jakarta REST 4.0 §3.7.2, §6.5.1, §6.7.1); the two locator paths disagreed.
- **Symptom**: on a resource method reached through a sub-resource locator that returns a typed sub-resource, the
  post-matching request filters ran before the locators; through a locator that returns `Object` they ran after them.
  When such a typed locator threw, its mapped response went through the response filters bound by name to the method
  the locator would have led to, though no resource method was matched. The official TCK does not exercise it: none of
  its packages that deploy a container filter has a sub-resource locator (checked from the TCK 4.0.1 bytecode).
- **Minimal repro**: `LocatorFilterTest` (cassini-core): `thePostMatchingFiltersRunAfterTheLocators` on `/typed/ordered`
  gave `[global request plain, locator]` (`/object/ordered` gave `[locator, global request plain]`), and
  `theGlobalResponseFiltersRunOnTheMappedResponseOfALocator` on `/typed/failing/audited` carried the `X-Audited` header
  of the bound response filter. Both red before the fix.
- **Spec**: §3.7.2 counts the locators in the matching: step 2 takes the sub-resource locator `L` whose template
  matched, sets `C'` to the class it leads to and goes back to step 2a, and step 3 outputs "`O` = instance of resource
  class matched, `D` = resource method matched from `M`"; in the section's own example, `O` is the `WidgetResource`
  instance that the locator `getWidget` returned. §6.5.1: "a global `ContainerRequestFilter` is executed after resource
  matching unless annotated with `@PreMatching`". §6.7.1: when no resource method has been matched at the time of the
  exception, "only global filters and interceptors will be included". Jersey and RESTEasy agree: Jersey invokes a
  locator in its routing stage (`SubResourceLocatorRouter`), which `ApplicationHandler` chains before the post-matching
  filtering stage; RESTEasy's `ResourceLocatorInvoker` resolves the locator's object, then
  `ResourceMethodInvoker.invokeOnTarget` runs the post-matching request filters.
- **Cause**: `Invoker.resolveTarget`, which walked the static locator chain, ran after `runPostMatchingFilters`, and
  rendered a locator's failure with the post-matching context and the route of the final resource method.
- **Fix**: `Invoker.walkStaticLocators` walks the chain before the post-matching filters, as `DynamicLocatorDispatch`
  does on the dynamic path. A locator's failure is rendered with `Invoker.locatorContext`, now marked unmatched
  (`CassiniRequestContext.markUnmatched`), for which `ResponsePipeline` runs the global response filters only, on
  both paths. Unchanged, for the TCK: on the static path the negotiation (`415`, `406`) and the arguments still come
  before the locators, and the root resource of a route without locator is still created after the post-matching
  filters.

## CASSINI-052 — A `DynamicFeature` misses the classes a locator returns at run time, and runs twice for some methods
- **Date**: 2026-11-14 — **Status**: FIXED (fix/locator-filter-order)
- **Severity**: medium. Spec conformance (Jakarta REST 4.0 §6.5.3, §3.4.1), and a security one: a filter that a
  `DynamicFeature` binds (an authorization check such as `RolesAllowedDynamicFeature`) never ran on a method of a
  class that a locator returns as `Object`, so the method answered as if the check had passed.
- **Symptom**: the dynamic features ran once per route at startup. A class that a sub-resource locator returns as
  `Object` has no route then, so no feature ever saw its methods, and the filters a feature would bind to them never
  ran. A method that several typed locators lead to has one route per locator: the features ran once per route and
  bound their filters as many times, and each filter ran that many times per request. The two routes that stand for a
  locator returning `Object` were handed to the features too, as resource methods of the class `Object`. The official
  TCK does not exercise it: its two packages with a dynamic feature (`spec/filter/dynamicfeature`,
  `jaxrs31/spec/extensions`) have no locator.
- **Minimal repro**: `LocatorFilterTest` (cassini-core): `aDynamicFeatureBindsItsFiltersOnceToEachMethodALocatorLeadsTo`
  ran the bound filter 3 times on `/typed/item/dynamic`, `/object/item/dynamic` and `/object/class/dynamic` (`Item` is
  reached by three typed locators) and never on `/object/runtime-only/dynamic`; `aDynamicFeatureSeesEachResourceMethodOnce`
  listed 25 configurations for 11 methods, `Object.item` twice among them. All red before the fix.
- **Spec**: §6.5.3: dynamic features "are used to augment the set of filters and entity interceptors bound to a
  resource method. [...] JAX-RS implementations SHOULD resolve dynamic features for filters and interceptors once for
  each resource method. It is RECOMMENDED to process dynamic features at application deployment time." §3.4.1: the
  methods of the object a locator returns are "treated like a normal resource method", and its class MUST be
  determined from the object at run time, so deployment time cannot know them all. Jersey builds the model of the
  class a locator returns at run time (`RuntimeLocatorModelBuilder`, cached per class), and each
  `ResourceMethodInvoker` it builds runs the dynamic features; RESTEasy's `ResourceLocatorInvoker` keeps a registry
  per class of the returned object, whose `ResourceMethodInvoker`s run the server dynamic features when built. Neither
  runs a feature for a locator.
- **Cause**: `FilterRegistry.applyDynamicFeatures` ran the features for every route `CassiniStackBuilderImpl` passed at
  startup, without telling routes of the same method apart or leaving out the dynamic-locator routes, and
  `DynamicLocatorDispatch` never ran them for the routes of a class it scans at run time.
- **Fix**: `FilterRegistry.applyDynamicFeatures(ResourceMethod)` runs the features once per resource class and method
  and skips the dynamic-locator routes. `DynamicLocatorDispatch.dispatchOnInstance` calls it before running the method
  of a class returned at run time; the first request to reach that method configures it, under a lock, and the method
  counts as configured only once the features ran. The registry's filter and interceptor lists are now copied on
  write, so that a request reading one while a feature registers a filter for another method reads it whole.
