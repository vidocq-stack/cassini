package io.vidocq.cassini.spi.gen;

/**
 * Immutable descriptor for a single resource method route, emitted as literals by
 * {@code <ResourceClass>$$CassiniRoutes} generated classes.
 *
 * <p>This is a plain value type — no dependency on {@code cassini-core} internals
 * ({@code ResourceMethod}, {@code UriTemplate}). {@code cassini-core}'s
 * {@code RouteRegistry} converts each descriptor to a {@code ResourceMethod} at
 * startup, resolving the {@link java.lang.reflect.Method} via a targeted
 * {@code beanClass.getDeclaredMethod(methodName, paramTypes)} call (not an
 * annotation scan) and calling {@code UriTemplate.compile(pathTemplate)}.</p>
 *
 * <p>Only direct resource methods (those carrying an {@code @HttpMethod}
 * meta-annotation) are described here. Sub-resource locators and
 * {@code dynamicLocator} catch-all routes are NOT described — the
 * {@code RouteRegistry} falls back to {@code ResourceScanner} for any class
 * that has locators. See {@link RouteProvider#hasLocators()} for the signal.</p>
 *
 * @param beanClass       the class declaring the method (same as the root resource class for
 *                        direct methods)
 * @param methodName      the Java method name
 * @param paramTypeNames  binary names of the method's parameter types (for targeted reflection)
 * @param httpMethod      HTTP verb literal (e.g. {@code "GET"}, {@code "POST"})
 * @param pathTemplate    the combined (class + method) URI template string
 * @param produces        {@code @Produces} media types (empty array = no restriction)
 * @param consumes        {@code @Consumes} media types (empty array = no restriction)
 * @param classPathLiterals number of literal characters in the class-level {@code @Path}
 */
public record RouteDescriptor(
        Class<?> beanClass,
        String methodName,
        String[] paramTypeNames,
        String httpMethod,
        String pathTemplate,
        String[] produces,
        String[] consumes,
        int classPathLiterals
) {}
