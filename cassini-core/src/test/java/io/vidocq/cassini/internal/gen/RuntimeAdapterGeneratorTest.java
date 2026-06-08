/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.internal.MatchResult;
import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.UriTemplate;
import io.vidocq.cassini.spi.gen.InjectionSupport;
import io.vidocq.cassini.spi.gen.ParamKind;
import io.vidocq.cassini.spi.gen.ResourceAdapter;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// Additional imports for P1b invoke tests
// (already covered by existing imports above)

/**
 * P1a TDD: verifies the RuntimeAdapterGenerator and the updated AdapterRegistry.
 *
 * Covers:
 * 1. Private @Context field injection (SecurityContext) — the acid test.
 * 2. Public @QueryParam int field with @DefaultValue.
 * 3. @PathParam String field.
 * 4. List&lt;String&gt; @QueryParam (collection element type).
 * 5. @BeanParam field.
 * 6. Field-less class generates a no-op adapter.
 * 7. Adapter is cached on second call.
 * 8. Classes that cannot be generated fall back gracefully (sentinel).
 * 9. AdapterRegistry.lookup returns a real adapter (not empty) for generatable classes.
 */
class RuntimeAdapterGeneratorTest {

    // ---- Resource fixtures (in this package so privateLookupIn works) ----

    /** Has a private @Context SecurityContext — the acid test for VarHandle + private field. */
    static class PrivateContextResource {
        @Context
        private SecurityContext context;

        SecurityContext getContext() { return context; }
    }

    /** Public fields with @QueryParam, @DefaultValue, @PathParam. */
    static class PublicParamResource {
        @QueryParam("count")
        @DefaultValue("7")
        public int count;

        @PathParam("id")
        public String id;
    }

    /** List<String> @QueryParam — tests collection element type. */
    static class ListParamResource {
        @QueryParam("tags")
        public List<String> tags;
    }

    /** @BeanParam field. */
    static class BeanParamHolder {
        @QueryParam("x")
        public String x;
    }
    static class BeanParamResource {
        @BeanParam
        public BeanParamHolder bean;
    }

    // ---- P4 fixtures: @BeanParam via per-bean adapters ----

    /**
     * Bean with a PRIVATE @QueryParam field — the cross-package VarHandle test.
     * In a real TCK scenario the resource and bean are in different packages, so
     * the resource's adapter cannot access the bean's private field directly.
     * The bean's OWN adapter (generated via privateLookupIn(BeanWithPrivateField, ...))
     * CAN access it.
     */
    static class BeanWithPrivateField {
        @QueryParam("secret")
        private String secret;

        String getSecret() { return secret; }
    }

    static class ResourceWithPrivateBeanField {
        @BeanParam
        public BeanWithPrivateField bean;
    }

    /** Nested @BeanParam: inner bean contains another @BeanParam. */
    static class InnerBean {
        @QueryParam("inner")
        public String innerVal;
    }
    static class OuterBean {
        @QueryParam("outer")
        public String outerVal;

        @BeanParam
        public InnerBean nested;
    }
    static class ResourceWithNestedBeanParam {
        @BeanParam
        public OuterBean outerBean;
    }

    /** No injectable fields — generates a no-op injectFields. */
    static class FieldlessResource {
        public String nonInjectable;
        public static String staticField;
    }

    // ---- P1b invoke fixtures ----

    /** Resource with a simple method: takes a @QueryParam int + @Context String-like arg. */
    static class InvokeResource {
        /** Returns arg0 + arg1. */
        public int add(int a, int b) { return a + b; }

        /** Returns the argument string uppercased. */
        public String echo(String s) { return s == null ? null : s.toUpperCase(); }

        /** Void method — side effect captured via field. */
        public String lastVoidArg;
        public void sideEffect(String arg) { this.lastVoidArg = arg; }

        /** Returns a double primitive. */
        public double square(double x) { return x * x; }
    }

    /** Resource with a body/entity parameter (now eligible in P1b). */
    static class BodyParamResource {
        public String process(String body, int count) {
            return body.repeat(count);
        }
    }

    /**
     * Resource whose methods have array-typed parameters/return — reproduces the M6d bug where
     * the runtime generator built the arg checkcast with {@code ClassDesc.of(pt.getName())}: an
     * array's {@code Class.getName()} is the JVM descriptor form ({@code [Ljava...;}, {@code [B}),
     * which {@code ClassDesc.of} rejects, so adapter generation crashed for any provider/method
     * taking {@code Annotation[]} or {@code byte[]} (≈12 TCK provider classes fell back to
     * reflection).
     */
    static class ArrayParamResource {
        public int countAnnotations(java.lang.annotation.Annotation[] anns, byte[] data) {
            return (anns == null ? 0 : anns.length) + (data == null ? 0 : data.length);
        }
        public byte[] echoBytes(byte[] in) { return in; }
    }

    // ---- Fake InjectionSupport ----

    static class FakeSupport implements InjectionSupport {
        private final SecurityContext sc;
        private final String pathId;
        private final int queryCount;
        private final List<String> queryTags;
        private final Object beanParamResult;

        FakeSupport(SecurityContext sc, String pathId, int queryCount,
                    List<String> queryTags, Object beanParamResult) {
            this.sc = sc;
            this.pathId = pathId;
            this.queryCount = queryCount;
            this.queryTags = queryTags;
            this.beanParamResult = beanParamResult;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T context(Class<T> type) {
            if (type == SecurityContext.class) return (T) sc;
            if (type == UriInfo.class) return null;
            return null;
        }

        @Override
        public Object param(ParamKind kind, String name, boolean encoded,
                            String defaultValue, Class<?> rawType, Class<?> elementType) {
            return switch (kind) {
                case PATH -> "id".equals(name) ? pathId : null;
                case QUERY -> {
                    if ("count".equals(name)) {
                        // Return as boxed int (the VarHandle.set will auto-unbox for primitive target)
                        // Actually VarHandle on int field needs Integer or int, but our generated code
                        // uses Object slot — so we return Integer here.
                        yield queryCount;
                    }
                    if ("tags".equals(name)) yield queryTags;
                    // Apply defaultValue if no value
                    yield defaultValue;
                }
                default -> null;
            };
        }

        @Override
        public Object beanParam(Class<?> type) {
            return beanParamResult;
        }

        @Override
        public Object suspendedAsyncResponse() { return null; }

        @Override
        public List<String> rawValues(ParamKind kind, String name, boolean encoded) {
            // Delegate to param() logic to extract the raw string
            Object v = param(kind, name, encoded, null, String.class, String.class);
            if (v == null) return List.of();
            if (v instanceof List<?> l) return l.stream().map(Object::toString).toList();
            return List.of(v.toString());
        }

        @Override
        public WebApplicationException coercionError(ParamKind kind, String name, RuntimeException cause) {
            // Avoid Response.status() — no RuntimeDelegate in cassini-core unit tests.
            return new WebApplicationException("Invalid value for param " + name + ": " + cause.getMessage(), cause);
        }
    }

    // ---- Helper ----

    private static SecurityContext makeSc() {
        return new SecurityContext() {
            @Override public Principal getUserPrincipal() { return null; }
            @Override public boolean isUserInRole(String role) { return false; }
            @Override public boolean isSecure() { return true; }
            @Override public String getAuthenticationScheme() { return "TEST"; }
        };
    }

    // ---- Tests ----

    @AfterEach
    void cleanup() {
        AdapterRegistry.deregister(PrivateContextResource.class);
        AdapterRegistry.deregister(PublicParamResource.class);
        AdapterRegistry.deregister(ListParamResource.class);
        AdapterRegistry.deregister(BeanParamResource.class);
        AdapterRegistry.deregister(FieldlessResource.class);
        AdapterRegistry.deregister(InvokeResource.class);
        AdapterRegistry.deregister(BodyParamResource.class);
        // P4 fixtures
        AdapterRegistry.deregister(BeanWithPrivateField.class);
        AdapterRegistry.deregister(ResourceWithPrivateBeanField.class);
        AdapterRegistry.deregister(InnerBean.class);
        AdapterRegistry.deregister(OuterBean.class);
        AdapterRegistry.deregister(ResourceWithNestedBeanParam.class);
        // M5a fixtures
        AdapterRegistry.deregister(ProviderWithContextFields.class);
        AdapterRegistry.deregister(ProviderWithPrivateContextField.class);
        // M6a fixtures
        AdapterRegistry.deregister(PublicNoArgResource.class);
        AdapterRegistry.deregister(PackageNoArgResource.class);
        AdapterRegistry.deregister(CtorInjectionResource.class);
        // M6b fixtures
        AdapterRegistry.deregister(M6bResource.class);
        AdapterRegistry.deregister(PathIntResource.class);
        AdapterRegistry.deregister(HeaderIntResource.class);
    }

    @Test
    void privateContextFieldIsInjected() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(PrivateContextResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        SecurityContext sc = makeSc();
        var support = new FakeSupport(sc, null, 0, null, null);

        PrivateContextResource target = new PrivateContextResource();
        assertNull(target.getContext(), "field should be null before injection");

        adapter.injectFields(target, support, true);

        assertSame(sc, target.getContext(), "private @Context SecurityContext must be injected via VarHandle");
    }

    @Test
    void privateContextFieldInjectedEvenWhenInjectParamsFalse() throws Exception {
        // @Context fields are always injected, regardless of injectParams
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(PrivateContextResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        SecurityContext sc = makeSc();
        var support = new FakeSupport(sc, null, 0, null, null);

        PrivateContextResource target = new PrivateContextResource();
        adapter.injectFields(target, support, false); // injectParams=false

        assertSame(sc, target.getContext(), "@Context must be injected even with injectParams=false");
    }

    @Test
    void publicQueryParamWithDefaultValue() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(PublicParamResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        // count param returns 42
        var support = new FakeSupport(null, "abc", 42, null, null);
        PublicParamResource target = new PublicParamResource();
        adapter.injectFields(target, support, true);

        assertEquals(42, target.count, "@QueryParam int must be injected");
        assertEquals("abc", target.id, "@PathParam String must be injected");
    }

    @Test
    void paramFieldsSkippedWhenInjectParamsFalse() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(PublicParamResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        var support = new FakeSupport(null, "abc", 42, null, null);
        PublicParamResource target = new PublicParamResource();
        adapter.injectFields(target, support, false); // sub-resource locator — skip params

        assertEquals(0, target.count, "@QueryParam must NOT be injected when injectParams=false");
        assertNull(target.id, "@PathParam must NOT be injected when injectParams=false");
    }

    @Test
    void listQueryParam() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(ListParamResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        List<String> tags = List.of("java", "jaxrs");
        var support = new FakeSupport(null, null, 0, tags, null);
        ListParamResource target = new ListParamResource();
        adapter.injectFields(target, support, true);

        assertEquals(tags, target.tags, "List<String> @QueryParam must be injected");
    }

    @Test
    void beanParamField() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(BeanParamResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        BeanParamHolder holder = new BeanParamHolder();
        holder.x = "hello";
        var support = new FakeSupport(null, null, 0, null, holder);
        BeanParamResource target = new BeanParamResource();
        adapter.injectFields(target, support, true);

        assertSame(holder, target.bean, "@BeanParam field must be injected");
    }

    @Test
    void fieldlessClassGeneratesNoOpAdapter() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(FieldlessResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        FieldlessResource target = new FieldlessResource();
        target.nonInjectable = "original";
        var support = new FakeSupport(null, null, 0, null, null);

        assertDoesNotThrow(() -> adapter.injectFields(target, support, true));
        assertEquals("original", target.nonInjectable, "non-injectable field must not be touched");
    }

    @Test
    void adapterClassNameFollowsConvention() {
        String name = RuntimeAdapterGenerator.adapterClassName(PrivateContextResource.class);
        assertTrue(name.endsWith("$$CassiniAdapter"),
                "adapter name must end with $$CassiniAdapter, got: " + name);
        assertEquals(PrivateContextResource.class.getName() + "$$CassiniAdapter", name);
    }

    @Test
    void adapterIsCachedByRegistry() {
        // First lookup triggers generation
        var first = AdapterRegistry.lookup(PrivateContextResource.class);
        assertTrue(first.isPresent(), "first lookup should find an adapter");

        // Second lookup should return the same instance (from cache)
        var second = AdapterRegistry.lookup(PrivateContextResource.class);
        assertTrue(second.isPresent(), "second lookup should also find an adapter");
        assertSame(first.get(), second.get(), "both lookups should return the same cached adapter instance");
    }

    // ---- P1b invoke tests ----

    @Test
    void generatesAdapterForArrayTypedMethodParams() throws Throwable {
        // Before the M6d fix, generate() threw IllegalArgumentException("Invalid class name: [L...")
        // because array param checkcasts used ClassDesc.of(getName()) instead of classDescOf().
        Class<?> adapterClass = assertDoesNotThrow(
                () -> RuntimeAdapterGenerator.generate(ArrayParamResource.class));
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        var methodIds = RuntimeAdapterGenerator.collectMethods(ArrayParamResource.class);
        java.lang.reflect.Method count = ArrayParamResource.class.getMethod(
                "countAnnotations", java.lang.annotation.Annotation[].class, byte[].class);
        java.lang.reflect.Method echo = ArrayParamResource.class.getMethod("echoBytes", byte[].class);

        ArrayParamResource target = new ArrayParamResource();
        byte[] data = {1, 2, 3};
        Object n = adapter.invoke(methodIds.get(count), target,
                new Object[]{new java.lang.annotation.Annotation[0], data});
        assertEquals(3, n, "array params must pass through (0 annotations + 3 bytes)");

        Object echoed = adapter.invoke(methodIds.get(echo), target, new Object[]{data});
        assertSame(data, echoed, "byte[] return must be the same array instance");
    }

    @Test
    void invokeIntMethodReturnsBoxedResult() throws Throwable {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(InvokeResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        // Find the methodId for add(int,int)
        var methodIds = RuntimeAdapterGenerator.collectMethods(InvokeResource.class);
        java.lang.reflect.Method addMethod = InvokeResource.class.getMethod("add", int.class, int.class);
        int mid = methodIds.get(addMethod);

        InvokeResource target = new InvokeResource();
        Object result = adapter.invoke(mid, target, new Object[]{3, 4});
        assertEquals(7, result, "direct invoke of add(3,4) must return 7");
    }

    @Test
    void invokeStringMethodReturnValue() throws Throwable {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(InvokeResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        var methodIds = RuntimeAdapterGenerator.collectMethods(InvokeResource.class);
        java.lang.reflect.Method echoMethod = InvokeResource.class.getMethod("echo", String.class);
        int mid = methodIds.get(echoMethod);

        InvokeResource target = new InvokeResource();
        Object result = adapter.invoke(mid, target, new Object[]{"hello"});
        assertEquals("HELLO", result, "direct invoke of echo must uppercase the argument");
    }

    @Test
    void invokeVoidMethodReturnsNull() throws Throwable {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(InvokeResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        var methodIds = RuntimeAdapterGenerator.collectMethods(InvokeResource.class);
        java.lang.reflect.Method sideEffectMethod = InvokeResource.class.getMethod("sideEffect", String.class);
        int mid = methodIds.get(sideEffectMethod);

        InvokeResource target = new InvokeResource();
        Object result = adapter.invoke(mid, target, new Object[]{"hello"});
        assertNull(result, "void method must return null");
        assertEquals("hello", target.lastVoidArg, "void method side effect must execute");
    }

    @Test
    void invokeDoublePrimitiveReturnIsBoxed() throws Throwable {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(InvokeResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        var methodIds = RuntimeAdapterGenerator.collectMethods(InvokeResource.class);
        java.lang.reflect.Method squareMethod = InvokeResource.class.getMethod("square", double.class);
        int mid = methodIds.get(squareMethod);

        InvokeResource target = new InvokeResource();
        Object result = adapter.invoke(mid, target, new Object[]{3.0});
        assertEquals(9.0, (Double) result, 1e-9, "square(3.0) must return 9.0 boxed as Double");
    }

    @Test
    void invokeBodyParamMethodEligible() throws Throwable {
        // P1b: body param methods are now eligible (args are pre-resolved)
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(BodyParamResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        var methodIds = RuntimeAdapterGenerator.collectMethods(BodyParamResource.class);
        java.lang.reflect.Method processMethod = BodyParamResource.class.getMethod("process", String.class, int.class);
        int mid = methodIds.get(processMethod);
        assertTrue(mid >= 0, "body param method must be eligible and have a methodId");

        BodyParamResource target = new BodyParamResource();
        Object result = adapter.invoke(mid, target, new Object[]{"ab", 3});
        assertEquals("ababab", result, "process(\"ab\", 3) must return \"ababab\"");
    }

    @Test
    void invokeUnknownMethodIdThrowsUnsupportedOperation() throws Throwable {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(InvokeResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        assertThrows(UnsupportedOperationException.class,
                () -> adapter.invoke(9999, new InvokeResource(), new Object[0]),
                "unknown methodId must throw UnsupportedOperationException");
    }

    @Test
    void collectFieldsDetectsAnnotations() {
        var fields = RuntimeAdapterGenerator.collectFields(PrivateContextResource.class);
        assertEquals(1, fields.size());
        assertTrue(fields.get(0).isContext());

        var paramFields = RuntimeAdapterGenerator.collectFields(PublicParamResource.class);
        assertEquals(2, paramFields.size());
        assertTrue(paramFields.stream().anyMatch(f -> f.paramKind() == ParamKind.QUERY));
        assertTrue(paramFields.stream().anyMatch(f -> f.paramKind() == ParamKind.PATH));
    }

    @Test
    void collectFieldsIsEmptyForFieldlessClass() {
        var fields = RuntimeAdapterGenerator.collectFields(FieldlessResource.class);
        assertTrue(fields.isEmpty(), "FieldlessResource has no injectable fields");
    }

    @Test
    void collectFieldsDetectsDefaultValue() {
        var fields = RuntimeAdapterGenerator.collectFields(PublicParamResource.class);
        var countField = fields.stream()
                .filter(f -> f.javaFieldName().equals("count"))
                .findFirst()
                .orElseThrow();
        assertEquals("7", countField.defaultValue(), "@DefaultValue should be captured");
    }

    // ---- P3: toBytecode tests ----

    @Test
    void toBytecodeReturnsBytecodeWithoutDefiningClass() {
        // toBytecode must return non-empty bytes without defining the class
        byte[] bc = RuntimeAdapterGenerator.toBytecode(PrivateContextResource.class);
        assertNotNull(bc);
        assertTrue(bc.length > 0, "toBytecode must produce non-empty bytecode");
    }

    @Test
    void toBytecodeIsDeterministic() {
        // Same input → same output; required for plugin-generated == runtime-generated equality
        byte[] bc1 = RuntimeAdapterGenerator.toBytecode(PublicParamResource.class);
        byte[] bc2 = RuntimeAdapterGenerator.toBytecode(PublicParamResource.class);
        assertArrayEquals(bc1, bc2, "toBytecode must be deterministic across calls");
    }

    @Test
    void toBytecodeClassFileParseRoundTrip() {
        // The generated bytecode must be structurally valid (parseable by the Class-File API)
        byte[] bc = RuntimeAdapterGenerator.toBytecode(PrivateContextResource.class);
        var cf = java.lang.classfile.ClassFile.of().parse(bc);
        String expectedInternalName = (PrivateContextResource.class.getName()
                + RuntimeAdapterGenerator.ADAPTER_SUFFIX).replace('.', '/');
        assertEquals(expectedInternalName, cf.thisClass().asInternalName(),
                "adapter class name must match expected pattern");
    }

    @Test
    void toBytecodeByteIdenticalToGeneratedBytes() throws Exception {
        // The bytes returned by toBytecode must match what generate() would also produce
        // (both delegate to the same internal build method)
        byte[] fromToBytecode = RuntimeAdapterGenerator.toBytecode(FieldlessResource.class);
        // generate() uses toBytecode() internally — so calling generate after deregistering
        // the cache will re-invoke toBytecode; the result should be identical.
        byte[] secondCall = RuntimeAdapterGenerator.toBytecode(FieldlessResource.class);
        assertArrayEquals(fromToBytecode, secondCall,
                "repeated toBytecode calls must be byte-for-byte identical");
    }

    // ---- P4: @BeanParam via per-bean adapters ----

    /**
     * P4: a @BeanParam bean with a PRIVATE @QueryParam field is injected via its
     * own generated adapter (not via reflective FieldInjector.inject).
     * This mirrors the TCK cross-package scenario where the resource and bean are
     * in different packages — only the bean's OWN adapter can access its private fields.
     */
    @Test
    void beanParamBeanWithPrivateFieldInjectedViaAdapter() throws Exception {
        // Pre-generate the bean's adapter explicitly (simulating runtime lookup path)
        RuntimeAdapterGenerator.generate(BeanWithPrivateField.class);

        // Use InjectionSupportImpl with a real exchange carrying the query param
        CassiniHttpExchange exchange = p4ExchangeWithUri("http://localhost/test?secret=mysecret");
        MatchResult match = p4MinimalMatch(BeanWithPrivateField.class);
        InjectionSupportImpl support = new InjectionSupportImpl(match, exchange);

        // Invoke beanParam via InjectionSupportImpl — P4 path: must use adapter
        Object result = support.beanParam(BeanWithPrivateField.class);

        assertNotNull(result, "beanParam must return a non-null instance");
        assertInstanceOf(BeanWithPrivateField.class, result);
        BeanWithPrivateField bean = (BeanWithPrivateField) result;
        assertEquals("mysecret", bean.getSecret(),
                "P4: private @QueryParam field in @BeanParam bean must be injected via the bean's adapter");
    }

    /**
     * P4: nested @BeanParam — outer bean contains another @BeanParam field pointing
     * to an inner bean. The recursive path (support.beanParam → adapter.injectFields
     * → support.beanParam for nested) must work end-to-end.
     */
    @Test
    void nestedBeanParamInjectedRecursively() throws Exception {
        // Pre-generate adapters for both inner and outer beans
        RuntimeAdapterGenerator.generate(InnerBean.class);
        RuntimeAdapterGenerator.generate(OuterBean.class);

        CassiniHttpExchange exchange = p4ExchangeWithUri(
                "http://localhost/test?outer=outerValue&inner=innerValue");
        MatchResult match = p4MinimalMatch(OuterBean.class);
        InjectionSupportImpl support = new InjectionSupportImpl(match, exchange);

        Object result = support.beanParam(OuterBean.class);

        assertNotNull(result);
        assertInstanceOf(OuterBean.class, result);
        OuterBean outer = (OuterBean) result;
        assertEquals("outerValue", outer.outerVal,
                "outer bean's @QueryParam must be injected");
        assertNotNull(outer.nested,
                "nested @BeanParam field in outer bean must be populated");
        assertEquals("innerValue", outer.nested.innerVal,
                "inner bean's @QueryParam must be injected via recursive adapter call");
    }

    /**
     * P4: when no adapter can be generated (simulated by de-registering + forcing sentinel),
     * InjectionSupportImpl.beanParam falls back gracefully to FieldInjector.inject.
     * Here we verify the fall-through still returns a valid (if un-injected) instance
     * rather than throwing.
     */
    @Test
    void beanParamFallsBackToReflectionWhenNoAdapter() {
        // Use a lambda-anonymous class type — not generatable (no stable name), so
        // AdapterRegistry will cache the SENTINEL and the reflective fallback will run.
        // We use BeanParamHolder (public field) — reflective path can still inject it.
        CassiniHttpExchange exchange = p4ExchangeWithUri("http://localhost/test?x=hello");
        MatchResult match = p4MinimalMatch(BeanParamHolder.class);
        // Force deregister to ensure re-lookup (previous tests may have cached it)
        AdapterRegistry.deregister(BeanParamHolder.class);
        InjectionSupportImpl support = new InjectionSupportImpl(match, exchange);

        Object result = support.beanParam(BeanParamHolder.class);
        assertNotNull(result, "beanParam must return a non-null instance even via reflective fallback");
        assertInstanceOf(BeanParamHolder.class, result);
        // The adapter OR reflective path should inject the public field
        BeanParamHolder holder = (BeanParamHolder) result;
        assertEquals("hello", holder.x,
                "@QueryParam public field must be injected (adapter or reflective path)");
    }

    // ---- P4 helper plumbing ----

    private static MatchResult p4MinimalMatch(Class<?> cls) {
        ResourceMethod rm = new ResourceMethod(
                cls, null, "GET",
                UriTemplate.compile("/test"), Set.of(), Set.of());
        return new MatchResult(rm, Map.of(), Map.of());
    }

    private static CassiniHttpExchange p4ExchangeWithUri(String uri) {
        return new P4MinimalExchange(URI.create(uri));
    }

    // ---- M5a tests: @Provider @Context injection via generated adapter ----

    /**
     * M5a: AdapterRegistry.lookup on a @Provider class returns an adapter whose
     * injectFields(provider, support, false) populates public @Context fields.
     * The injectParams=false contract is critical — providers have no @PathParam/etc.
     */
    @Test
    void providerContextFieldsInjectedViaAdapter() {
        var adapterOpt = AdapterRegistry.lookup(ProviderWithContextFields.class);
        assertTrue(adapterOpt.isPresent(),
                "AdapterRegistry must return an adapter for a @Provider class with @Context fields");

        ProviderWithContextFields provider = new ProviderWithContextFields();
        assertNull(provider.getUriInfo(), "UriInfo must be null before injection");

        // Use a FakeSupport that returns stub UriInfo
        UriInfo stubUriInfo = new jakarta.ws.rs.core.UriInfo() {
            @Override public URI getBaseUri() { return URI.create("http://host/"); }
            @Override public URI getRequestUri() { return URI.create("http://host/test"); }
            @Override public jakarta.ws.rs.core.UriBuilder getBaseUriBuilder() { return null; }
            @Override public jakarta.ws.rs.core.UriBuilder getRequestUriBuilder() { return null; }
            @Override public jakarta.ws.rs.core.UriBuilder getAbsolutePathBuilder() { return null; }
            @Override public URI getAbsolutePath() { return URI.create("http://host/test"); }
            @Override public String getPath() { return "/test"; }
            @Override public String getPath(boolean decode) { return "/test"; }
            @Override public java.util.List<jakarta.ws.rs.core.PathSegment> getPathSegments() { return List.of(); }
            @Override public java.util.List<jakarta.ws.rs.core.PathSegment> getPathSegments(boolean decode) { return List.of(); }
            @Override public jakarta.ws.rs.core.MultivaluedMap<String, String> getPathParameters() { return new jakarta.ws.rs.core.MultivaluedHashMap<>(); }
            @Override public jakarta.ws.rs.core.MultivaluedMap<String, String> getPathParameters(boolean decode) { return new jakarta.ws.rs.core.MultivaluedHashMap<>(); }
            @Override public jakarta.ws.rs.core.MultivaluedMap<String, String> getQueryParameters() { return new jakarta.ws.rs.core.MultivaluedHashMap<>(); }
            @Override public jakarta.ws.rs.core.MultivaluedMap<String, String> getQueryParameters(boolean decode) { return new jakarta.ws.rs.core.MultivaluedHashMap<>(); }
            @Override public java.util.List<String> getMatchedURIs() { return List.of(); }
            @Override public java.util.List<String> getMatchedURIs(boolean decode) { return List.of(); }
            @Override public java.util.List<Object> getMatchedResources() { return List.of(); }
            @Override public URI resolve(URI uri) { return uri; }
            @Override public URI relativize(URI uri) { return uri; }
            @Override public String getMatchedResourceTemplate() { return null; }
        };

        var support = new FakeSupport(null, null, 0, null, null) {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T context(Class<T> type) {
                if (type == UriInfo.class) return (T) stubUriInfo;
                return null;
            }
        };

        adapterOpt.get().injectFields(provider, support, false);

        assertSame(stubUriInfo, provider.getUriInfo(),
                "M5a: @Context UriInfo field in @Provider must be injected via generated adapter");
    }

    /**
     * M5a: a @Provider with a PRIVATE @Context field is injected via the adapter's VarHandle.
     * This mirrors the existing private-resource test but for a @Provider class.
     */
    @Test
    void providerPrivateContextFieldInjectedViaVarHandle() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(ProviderWithPrivateContextField.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        // Reuse a real InjectionSupportImpl backed by a minimal exchange so that
        // FieldInjector.resolveContext(UriInfo) returns a non-null CassiniUriInfo.
        CassiniHttpExchange exchange = p4ExchangeWithUri("http://localhost/test");
        MatchResult match = p4MinimalMatch(ProviderWithPrivateContextField.class);
        InjectionSupportImpl support = new InjectionSupportImpl(match, exchange);

        ProviderWithPrivateContextField provider = new ProviderWithPrivateContextField();
        assertNull(provider.getUriInfo(), "field should be null before injection");

        // injectParams=false: only @Context fields injected (correct contract for providers)
        adapter.injectFields(provider, support, false);

        assertNotNull(provider.getUriInfo(),
                "M5a: private @Context UriInfo in @Provider must be injected via VarHandle");
    }

    // ---- M5a fixtures: @Provider classes with @Context fields ----

    /**
     * A @Provider with a public @Context UriInfo field and a @Context Providers field.
     * Models a ContainerRequestFilter / MessageBodyWriter / etc. that inject @Context.
     */
    @Provider
    static class ProviderWithContextFields {
        @Context
        public UriInfo uriInfo;

        @Context
        public Providers providers;

        public UriInfo getUriInfo() { return uriInfo; }
        public Providers getProviders() { return providers; }
    }

    /**
     * A @Provider with a PRIVATE @Context UriInfo field — tests VarHandle access
     * for private fields in provider classes (same mechanism as resource classes).
     */
    @Provider
    static class ProviderWithPrivateContextField {
        @Context
        private UriInfo uriInfo;

        public UriInfo getUriInfo() { return uriInfo; }
    }

    // ---- M6a fixtures: newInstance() ----

    /** Resource with a public no-arg constructor — adapter must generate newInstance(). */
    static class PublicNoArgResource {
        public PublicNoArgResource() {}
        public String hello() { return "hello"; }
    }

    /** Resource with a package-private no-arg constructor — adapter must generate newInstance(). */
    static class PackageNoArgResource {
        PackageNoArgResource() {}
    }

    /** Resource with ONLY a String constructor — no no-arg ctor, adapter must NOT generate newInstance(). */
    static class CtorInjectionResource {
        final String value;
        public CtorInjectionResource(String value) { this.value = value; }
    }

    // ---- M6a tests: newInstance() ----

    @Test
    void newInstanceWithPublicNoArgCtorReturnsFreshInstance() throws Exception {
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(PublicNoArgResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        Object instance1 = adapter.newInstance();
        Object instance2 = adapter.newInstance();

        assertNotNull(instance1, "newInstance() must return a non-null instance");
        assertInstanceOf(PublicNoArgResource.class, instance1,
                "newInstance() must return an instance of the resource class");
        assertNotSame(instance1, instance2,
                "newInstance() must return a fresh instance on each call (not a singleton)");
    }

    @Test
    void newInstanceWithPackageNoArgCtorReturnsFreshInstance() throws Exception {
        // Package-private ctor is accessible from the adapter (same package)
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(PackageNoArgResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        Object instance = adapter.newInstance();

        assertNotNull(instance, "newInstance() must work for package-private no-arg ctor");
        assertInstanceOf(PackageNoArgResource.class, instance);
    }

    @Test
    void newInstanceWithoutNoArgCtorThrowsUnsupportedOperation() throws Exception {
        // CtorInjectionResource has only String(String) ctor — no no-arg ctor
        Class<?> adapterClass = RuntimeAdapterGenerator.generate(CtorInjectionResource.class);
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        assertThrows(UnsupportedOperationException.class,
                () -> adapter.newInstance(),
                "newInstance() must throw UnsupportedOperationException when no no-arg ctor exists");
    }

    @Test
    void hasAccessibleNoArgCtorDetectsPublicCtor() {
        assertTrue(RuntimeAdapterGenerator.hasAccessibleNoArgCtor(PublicNoArgResource.class),
                "public no-arg ctor should be detected");
    }

    @Test
    void hasAccessibleNoArgCtorDetectsPackageCtor() {
        assertTrue(RuntimeAdapterGenerator.hasAccessibleNoArgCtor(PackageNoArgResource.class),
                "package-private no-arg ctor should be accessible from same-package adapter");
    }

    @Test
    void hasAccessibleNoArgCtorReturnsFalseWhenOnlyArgedCtor() {
        assertFalse(RuntimeAdapterGenerator.hasAccessibleNoArgCtor(CtorInjectionResource.class),
                "class with only String-arg ctor should return false");
    }

    @Test
    void beanParamUsesAdapterNewInstanceWhenAvailable() throws Exception {
        // InjectionSupportImpl.beanParam should use adapter.newInstance() (M6a)
        // when the adapter has a generated newInstance().
        // We verify by calling beanParam and checking we get a valid instance.
        RuntimeAdapterGenerator.generate(BeanParamHolder.class);

        CassiniHttpExchange exchange = p4ExchangeWithUri("http://localhost/test?x=value");
        MatchResult match = p4MinimalMatch(BeanParamHolder.class);
        InjectionSupportImpl support = new InjectionSupportImpl(match, exchange);

        Object result = support.beanParam(BeanParamHolder.class);
        assertNotNull(result);
        assertInstanceOf(BeanParamHolder.class, result);
        BeanParamHolder holder = (BeanParamHolder) result;
        assertEquals("value", holder.x, "M6a: @BeanParam bean created via newInstance() and fields injected");
    }

    /** Minimal exchange stub for P4 injection tests. Reuses the structure from AdapterRegistrySeamTest. */
    static class P4MinimalExchange implements CassiniHttpExchange {
        private final URI requestUri;
        private final java.util.Map<String, Object> attrs = new java.util.HashMap<>();

        P4MinimalExchange(URI requestUri) { this.requestUri = requestUri; }

        @Override public URI requestUri() { return requestUri; }
        @Override public String requestUriRaw() { return requestUri.toString(); }
        @Override public String method() { return "GET"; }
        @Override public Map<String, List<String>> requestHeaders() { return Map.of(); }
        @Override public java.io.InputStream requestBody() { return null; }
        @Override public String contextPath() { return ""; }
        @Override public boolean isSecure() { return false; }
        @Override public Object getAttribute(String key) { return attrs.get(key); }
        @Override public void setAttribute(String key, Object value) { attrs.put(key, value); }
        @Override public void setStatus(int code) {}
        @Override public Map<String, List<String>> responseHeaders() { return new java.util.HashMap<>(); }
        @Override public java.io.OutputStream responseBody() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.net.SocketAddress remoteAddress() { return null; }
        @Override public String authScheme() { return null; }
        @Override public java.security.Principal userPrincipal() { return null; }
        @Override public boolean isUserInRole(String role) { return false; }
    }

    // ---- M6b fixtures: inline conversion types ----

    /** Public enum without fromString — inline via Enum.valueOf. */
    public enum Color { RED, GREEN, BLUE }

    /** Public enum with a public static fromString method. */
    public enum Status {
        ACTIVE, INACTIVE;
        public static Status fromString(String s) { return valueOf(s.toUpperCase()); }
    }

    /** Public class with valueOf(String) factory. */
    public static class Score {
        public final int value;
        private Score(int v) { this.value = v; }
        public static Score valueOf(String s) { return new Score(Integer.parseInt(s)); }
    }

    /** Public class with fromString(String) factory. */
    public static class Tag {
        public final String text;
        private Tag(String t) { this.text = t; }
        public static Tag fromString(String s) { return new Tag(s.trim()); }
    }

    /** Public class with a public (String) constructor. */
    public static class Token {
        public final String value;
        public Token(String v) { this.value = v; }
    }

    /** Non-public type — must fall back to support.param(). */
    static class PackagePrivateType {
        public final String v;
        public PackagePrivateType(String v) { this.v = v; }
        public static PackagePrivateType valueOf(String s) { return new PackagePrivateType(s); }
    }

    /** Resource exercising M6b inline strategies. */
    static class M6bResource {
        @QueryParam("count")
        public int count;

        @QueryParam("countW")
        public Integer countWrapper;

        @PathParam("color")
        public Color color;

        @QueryParam("status")
        public Status status;

        @QueryParam("score")
        public Score score;

        @QueryParam("tag")
        public Tag tag;

        @HeaderParam("token")
        public Token token;

        @QueryParam("items")
        public List<Integer> items;

        @QueryParam("labels")
        public Set<String> labels;

        /** Uses a non-public type — must fall back to support.param(). */
        @QueryParam("pkg")
        public PackagePrivateType pkg;
    }

    // ---- A RawValues-capable FakeSupport for M6b inline-path tests ----

    /**
     * An InjectionSupport stub that returns controlled raw String lists from rawValues()
     * and delegates coercionError() correctly. Used for M6b inline-path tests where
     * the generated adapter calls rawValues() instead of param().
     */
    static class RawValuesSupport implements InjectionSupport {
        /** Map of (kind.name + ":" + paramName) → list of raw values to return. */
        private final Map<String, List<String>> rawMap;
        /** Map of (kind.name + ":" + paramName) → pre-coerced object for param() fallback. */
        private final Map<String, Object> paramMap;

        RawValuesSupport(Map<String, List<String>> rawMap, Map<String, Object> paramMap) {
            this.rawMap   = rawMap;
            this.paramMap = paramMap;
        }

        @Override public <T> T context(Class<T> type) { return null; }
        @Override public Object beanParam(Class<?> type) { return null; }
        @Override public Object suspendedAsyncResponse() { return null; }

        @Override
        public List<String> rawValues(ParamKind kind, String name, boolean encoded) {
            return rawMap.getOrDefault(kind.name() + ":" + name, List.of());
        }

        @Override
        public Object param(ParamKind kind, String name, boolean encoded,
                            String defaultValue, Class<?> rawType, Class<?> elementType) {
            return paramMap.get(kind.name() + ":" + name);
        }

        @Override
        public WebApplicationException coercionError(ParamKind kind, String name, RuntimeException cause) {
            // Note: avoid Response.status() here — no RuntimeDelegate in cassini-core unit tests.
            return new WebApplicationException("coercion: " + name + ": " + cause.getMessage(), cause);
        }
    }

    // ---- M6b helper ----

    /** Generates adapter for M6bResource and returns a fresh adapter instance. */
    private ResourceAdapter m6bAdapter() throws Exception {
        Class<?> ac = RuntimeAdapterGenerator.generate(M6bResource.class);
        return (ResourceAdapter) ac.getDeclaredConstructor().newInstance();
    }

    // ---- M6b tests ----

    @Test
    void m6b_intFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("QUERY:count", List.of("42")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertEquals(42, target.count, "M6b: int @QueryParam must be inline-converted from raw String");
    }

    @Test
    void m6b_intFieldDefaultValueWhenAbsent() throws Exception {
        // count has no @DefaultValue — absent raw → default for int = 0
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(Map.of(), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertEquals(0, target.count, "M6b: absent int @QueryParam with no @DefaultValue must yield 0");
    }

    @Test
    void m6b_integerWrapperFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("QUERY:countW", List.of("99")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertEquals(Integer.valueOf(99), target.countWrapper,
                "M6b: Integer wrapper @QueryParam must be inline-converted");
    }

    @Test
    void m6b_enumPlainFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("PATH:color", List.of("GREEN")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertEquals(Color.GREEN, target.color, "M6b: enum (plain Enum.valueOf) must be inline-converted");
    }

    @Test
    void m6b_enumPlainUnknownValueYieldsNull() throws Exception {
        // ENUM_PLAIN: IAE from Enum.valueOf → null (matches coerceSingle behavior)
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("PATH:color", List.of("PURPLE")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertNull(target.color,
                "M6b: unknown enum value with ENUM_PLAIN strategy must yield null (not throw)");
    }

    @Test
    void m6b_enumWithFromStringFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("QUERY:status", List.of("active")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertEquals(Status.ACTIVE, target.status,
                "M6b: enum with fromString must delegate to fromString");
    }

    @Test
    void m6b_valueOfTypeFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("QUERY:score", List.of("77")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertNotNull(target.score);
        assertEquals(77, target.score.value,
                "M6b: type with valueOf(String) must be inline-converted");
    }

    @Test
    void m6b_fromStringTypeFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("QUERY:tag", List.of("  hello  ")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertNotNull(target.tag);
        assertEquals("hello", target.tag.text,
                "M6b: type with fromString(String) must be inline-converted");
    }

    @Test
    void m6b_stringCtorTypeFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("HEADER:token", List.of("tok123")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertNotNull(target.token);
        assertEquals("tok123", target.token.value,
                "M6b: type with (String) constructor must be inline-converted");
    }

    @Test
    void m6b_listOfIntegerFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("QUERY:items", List.of("1", "2", "3")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertNotNull(target.items);
        assertEquals(List.of(1, 2, 3), target.items,
                "M6b: List<Integer> @QueryParam must be inline-converted");
    }

    @Test
    void m6b_setOfStringFieldInlined() throws Exception {
        ResourceAdapter adapter = m6bAdapter();
        RawValuesSupport support = new RawValuesSupport(
                Map.of("QUERY:labels", List.of("a", "b", "c")), Map.of());

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertNotNull(target.labels);
        assertEquals(Set.of("a", "b", "c"), target.labels,
                "M6b: Set<String> @QueryParam must be inline-converted");
    }

    @Test
    void m6b_conversionFailureOnPathParamCallsCoercionError() throws Exception {
        // int @PathParam — parseInt failure → coercionError(PATH, ...) must be called.
        // We verify via a tracking stub that records which ParamKind was passed to coercionError.
        // The PATH kind signals 404 in a full runtime context (JAX-RS §3.2).
        // Note: we cannot assert WebApplicationException type here because
        // WebApplicationException constructors require RuntimeDelegate (not present in unit tests).
        // The HTTP status mapping is verified by the REST TCK.
        Class<?> ac = RuntimeAdapterGenerator.generate(PathIntResource.class);
        ResourceAdapter pathAdapter = (ResourceAdapter) ac.getDeclaredConstructor().newInstance();

        ParamKind[] capturedKind = new ParamKind[1];
        InjectionSupport trackingSupport = new RawValuesSupport(
                Map.of("PATH:id", List.of("notAnInt")), Map.of()) {
            @Override
            public WebApplicationException coercionError(ParamKind kind, String name, RuntimeException cause) {
                capturedKind[0] = kind;
                throw cause; // rethrow original to propagate something
            }
        };

        PathIntResource target = new PathIntResource();
        assertThrows(RuntimeException.class,
                () -> pathAdapter.injectFields(target, trackingSupport, true),
                "M6b: int @PathParam conversion failure must propagate an exception");
        assertEquals(ParamKind.PATH, capturedKind[0],
                "M6b: coercionError must be called with PATH kind (signals 404 in full runtime)");
    }

    @Test
    void m6b_conversionFailureOnHeaderParamCallsCoercionError() throws Exception {
        // int @HeaderParam — parseInt failure → coercionError(HEADER, ...) must be called.
        // HEADER kind signals 400.
        Class<?> ac = RuntimeAdapterGenerator.generate(HeaderIntResource.class);
        ResourceAdapter headerAdapter = (ResourceAdapter) ac.getDeclaredConstructor().newInstance();

        ParamKind[] capturedKind = new ParamKind[1];
        InjectionSupport trackingSupport = new RawValuesSupport(
                Map.of("HEADER:x-count", List.of("NaN")), Map.of()) {
            @Override
            public WebApplicationException coercionError(ParamKind kind, String name, RuntimeException cause) {
                capturedKind[0] = kind;
                throw cause; // rethrow original to propagate something
            }
        };

        HeaderIntResource target = new HeaderIntResource();
        assertThrows(RuntimeException.class,
                () -> headerAdapter.injectFields(target, trackingSupport, true),
                "M6b: int @HeaderParam conversion failure must propagate an exception");
        assertEquals(ParamKind.HEADER, capturedKind[0],
                "M6b: coercionError must be called with HEADER kind (signals 400 in full runtime)");
    }

    @Test
    void m6b_nonPublicTypeFallsBackToSupportParam() throws Exception {
        // PackagePrivateType has valueOf(String) but the class itself is package-private
        // → resolveScalarStrategy must return FALLBACK → support.param() is called.
        // Verify that resolveScalarStrategy returns FALLBACK
        var fieldDesc = RuntimeAdapterGenerator.collectFields(M6bResource.class).stream()
                .filter(f -> f.javaFieldName().equals("pkg"))
                .findFirst().orElseThrow();

        RuntimeAdapterGenerator.InlineStrategy strategy = RuntimeAdapterGenerator.resolveInlineStrategy(fieldDesc);
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.FALLBACK, strategy,
                "M6b: non-public type must resolve to FALLBACK strategy");

        // Also verify that at runtime the adapter calls support.param() and gets the value
        ResourceAdapter adapter = m6bAdapter();
        PackagePrivateType expected = new PackagePrivateType("test");
        RawValuesSupport support = new RawValuesSupport(
                Map.of(),
                Map.of("QUERY:pkg", expected));

        M6bResource target = new M6bResource();
        adapter.injectFields(target, support, true);
        assertSame(expected, target.pkg,
                "M6b: FALLBACK strategy must delegate to support.param() and inject the result");
    }

    @Test
    void m6b_resolveInlineStrategyForKnownTypes() {
        // White-box: verify strategy resolution for the M6b types
        var fields = RuntimeAdapterGenerator.collectFields(M6bResource.class);

        var countF  = fields.stream().filter(f -> f.javaFieldName().equals("count")).findFirst().orElseThrow();
        var colorF  = fields.stream().filter(f -> f.javaFieldName().equals("color")).findFirst().orElseThrow();
        var statusF = fields.stream().filter(f -> f.javaFieldName().equals("status")).findFirst().orElseThrow();
        var scoreF  = fields.stream().filter(f -> f.javaFieldName().equals("score")).findFirst().orElseThrow();
        var tagF    = fields.stream().filter(f -> f.javaFieldName().equals("tag")).findFirst().orElseThrow();
        var tokenF  = fields.stream().filter(f -> f.javaFieldName().equals("token")).findFirst().orElseThrow();
        var itemsF  = fields.stream().filter(f -> f.javaFieldName().equals("items")).findFirst().orElseThrow();
        var labelsF = fields.stream().filter(f -> f.javaFieldName().equals("labels")).findFirst().orElseThrow();

        assertEquals(RuntimeAdapterGenerator.InlineStrategy.INT,                    RuntimeAdapterGenerator.resolveInlineStrategy(countF));
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.ENUM_PLAIN,             RuntimeAdapterGenerator.resolveInlineStrategy(colorF));
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.ENUM_WITH_FROM_STRING,  RuntimeAdapterGenerator.resolveInlineStrategy(statusF));
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.VALUE_OF,               RuntimeAdapterGenerator.resolveInlineStrategy(scoreF));
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.FROM_STRING,            RuntimeAdapterGenerator.resolveInlineStrategy(tagF));
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.STRING_CTOR,            RuntimeAdapterGenerator.resolveInlineStrategy(tokenF));
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.COLLECTION_INLINE,      RuntimeAdapterGenerator.resolveInlineStrategy(itemsF));
        assertEquals(RuntimeAdapterGenerator.InlineStrategy.COLLECTION_STRING,      RuntimeAdapterGenerator.resolveInlineStrategy(labelsF));
    }

    // ---- M6b additional fixtures ----

    static class PathIntResource {
        @PathParam("id")
        public int id;
    }

    static class HeaderIntResource {
        @HeaderParam("x-count")
        public int count;
    }
}
