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
) {

    /** Value equality: the arrays are compared by content, not by identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RouteDescriptor that
                && classPathLiterals == that.classPathLiterals
                && java.util.Objects.equals(beanClass, that.beanClass)
                && java.util.Objects.equals(methodName, that.methodName)
                && java.util.Arrays.equals(paramTypeNames, that.paramTypeNames)
                && java.util.Objects.equals(httpMethod, that.httpMethod)
                && java.util.Objects.equals(pathTemplate, that.pathTemplate)
                && java.util.Arrays.equals(produces, that.produces)
                && java.util.Arrays.equals(consumes, that.consumes);
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hash(beanClass, methodName, httpMethod, pathTemplate, classPathLiterals);
        h = 31 * h + java.util.Arrays.hashCode(paramTypeNames);
        h = 31 * h + java.util.Arrays.hashCode(produces);
        return 31 * h + java.util.Arrays.hashCode(consumes);
    }

    @Override
    public String toString() {
        return "RouteDescriptor[beanClass=" + beanClass
                + ", methodName=" + methodName
                + ", paramTypeNames=" + java.util.Arrays.toString(paramTypeNames)
                + ", httpMethod=" + httpMethod
                + ", pathTemplate=" + pathTemplate
                + ", produces=" + java.util.Arrays.toString(produces)
                + ", consumes=" + java.util.Arrays.toString(consumes)
                + ", classPathLiterals=" + classPathLiterals + "]";
    }
}
