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

## CASSINI-007 — Request properties are not shared between the request and response filters: the CDI request scope filters never deactivate
- **Date**: 2026-10-27 — **Status**: OPEN (found while removing the per-request thread of `ChappeHttpAdapter`)
- **Severity**: medium. JAX-RS conformance (a property set by a request filter must be visible to the response filters of the same request) and a request scope that is never ended by its filter: no `@PreDestroy` on `@RequestScoped` beans through the filter, and with Weld the request context stays on the thread.
- **Symptom**: every `CassiniRequestContext` has its own `properties` map, and a request builds several (the pre-matching run, the post-matching and response filter run). `CdiRequestScopeFilter` (cassini-cdi) and `VaubanRequestScopeFilter` (cassini-cdi-vauban) activate the request context in their pre-matching request filter, store a marker in the properties, and deactivate in their response filter only when they find it: they never find it. Both relied on the virtual thread started per request to discard what stayed on it.
- **Minimal repro**: dispatch on the calling thread with no scope hook (`new ChappeHttpAdapter(engine)` and an engine whose `mayStream()` is `false`), then `WeldPortabilityTest.requestScopedBeanIsOnePerRequest` (cassini-it-weld): the second request on the same keep-alive connection gets the first request's `@RequestScoped` instance.
- **Cause**: the properties belong to the `ContainerRequestContext` object instead of the request.
- **Workaround in place**: `ChappeHttpAdapter` dispatches on the calling thread only with a `Scoped` hook, which bounds the request scope by the call (the Vidocq runtime installs one); without a hook every request keeps its own thread.
- **Fix to do**: keep the properties on the exchange so that every context of a request shares them. It changes when the scope filters end the request scope (before the entity is written instead of after the dispatch), which needs its own review of the request-scoped beans used while writing an entity.

## CASSINI-008 — `@Context ContextResolver` injects the `Providers` and fails the invocation
- **Date**: 2026-10-27 — **Status**: OPEN (found while covering the request path with unit tests)
- **Severity**: low. `ContextResolver` is not among the `@Context` types of §9.2; an application that asks for one gets a 500 instead of a clear "unsupported" error.
- **Symptom**: a resource method parameter `@Context ContextResolver<?> cr` receives the request's `CassiniProviders`, which is not a `ContextResolver`: the reflective invocation throws a `ClassCastException`, answered 500.
- **Minimal repro**: `@GET public String get(@Context ContextResolver<?> cr)`, then any request to it.
- **Cause**: `ParamExtractor` maps `ContextResolver.class` to `currentProviders()`, a copy of the `Providers` case.
- **Fix to do**: drop the mapping, so that the parameter ends in "Unsupported @Context type" like any other type outside §9.2; or, if a `ContextResolver` is meant to be injectable, resolve it through `Providers.getContextResolver` with the parameter's generic type.

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
- **Date**: 2026-10-27 — **Status**: OPEN (found while covering `CassiniRequest` with unit tests; not fixed: the conditional-request path deserves its own change, checked against the TCK)
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
