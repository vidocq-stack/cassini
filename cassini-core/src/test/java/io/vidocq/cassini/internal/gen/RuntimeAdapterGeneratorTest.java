package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.spi.gen.InjectionSupport;
import io.vidocq.cassini.spi.gen.ParamKind;
import io.vidocq.cassini.spi.gen.ResourceAdapter;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;

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
}
