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
package io.vidocq.cassini.cdi.vauban;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code @Path} and {@code @Provider} beans are told apart from the deployment's index; a class the index does
 *  not know is read by reflection, as before. */
class ResourceClassesFromIndexTest {

    @Path("/indexed")
    @ApplicationScoped
    public static class IndexedResource {
        @GET
        public String get() {
            return "indexed";
        }
    }

    @Provider
    @Dependent
    public static class IndexedFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            // only its discovery is read
        }
    }

    @ApplicationScoped
    public static class PlainBean {
    }

    /** Not a bean: the index of the deployment does not know it. */
    @Path("/outside")
    public static class OutsideTheIndex {
    }

    @Test
    void theJaxrsBeansAreFoundAndTheOthersLeftOut() {
        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addBeanClass(IndexedResource.class)
                .addBeanClass(IndexedFilter.class)
                .addBeanClass(PlainBean.class)
                .build()) {
            Set<Class<?>> found = new VaubanBeanProvider(container).getResourceClasses();

            assertTrue(found.containsAll(Set.of(IndexedResource.class, IndexedFilter.class)), found.toString());
            assertFalse(found.contains(PlainBean.class));
        }
    }

    @Test
    void aClassOutsideTheIndexIsReadByReflection() {
        try (var container = VaubanContainer.builder()
                .classLoader(getClass().getClassLoader())
                .addBeanClass(PlainBean.class)
                .build()) {
            VaubanBeanProvider provider = new VaubanBeanProvider(container);

            assertTrue(container.indexedAnnotationNames(OutsideTheIndex.class).isEmpty());
            assertTrue(provider.isJaxrsClass(OutsideTheIndex.class));
            assertEquals(Set.of(), container.indexedAnnotationNames(PlainBean.class).orElseThrow().stream()
                    .filter(n -> n.startsWith("jakarta.ws.rs")).collect(java.util.stream.Collectors.toSet()));
            assertFalse(provider.isJaxrsClass(PlainBean.class));
        }
    }
}
