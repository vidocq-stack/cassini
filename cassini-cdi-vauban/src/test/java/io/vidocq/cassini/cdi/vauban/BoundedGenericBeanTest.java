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

import io.vidocq.cassini.cdi.CassiniScopeExtension;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resource or a provider of a bounded generic class has the bean type {@code NumberResource<T extends Number>}
 * (CDI 4.1 §5.2.4), which its raw class does not resolve: the provider finds it by its bean class (CASSINI-061).
 */
class BoundedGenericBeanTest {

    @Dependent
    public static class Answer {
        int value() {
            return 42;
        }
    }

    /**
     * Declares {@code @Dependent}, the one scope CDI 4.1 §3.1 allows a generic managed bean. Its producer is a bean of
     * the same bean class, which the lookup by bean class must not take for it.
     */
    @Path("/numbers")
    @Dependent
    public static class NumberResource<T extends Number> {
        @Inject
        Answer answer;

        @GET
        public String get() {
            return String.valueOf(answer.value());
        }

        @Produces
        String label() {
            return "numbers";
        }
    }

    /** Declares no scope: {@code CassiniScopeExtension} makes a provider {@code @Dependent}. */
    @Provider
    public static class NumberResolver<T extends Number> implements ContextResolver<T> {
        @Inject
        Answer answer;

        @Override
        public T getContext(Class<?> type) {
            return null;
        }
    }

    /** A qualifier other than {@code @Default}. */
    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    public @interface Tagged {
    }

    /** Its bean has {@code @Tagged} and {@code @Any}, no {@code @Default}: a lookup by class does not take it. */
    @Path("/tagged")
    @Dependent
    @Tagged
    public static class TaggedResource {
    }

    /** No bean at all: Cassini builds it itself. */
    @Path("/none")
    public static class NotABean {
    }

    private VaubanContainer container;
    private VaubanBeanProvider provider;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder()
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(Answer.class)
                .addBeanClass(NumberResource.class)
                .addBeanClass(NumberResolver.class)
                .addBeanClass(TaggedResource.class)
                .build();
        provider = new VaubanBeanProvider(container);
    }

    @AfterEach
    void stop() {
        container.close();
    }

    @Test
    void aBoundedGenericResourceIsANewBeanOnEachLookup() {
        NumberResource<?> first = provider.getBean(NumberResource.class);
        NumberResource<?> second = provider.getBean(NumberResource.class);
        assertNotSame(first, second);
        assertEquals("42", first.get(), "the container injected it");
        assertSame(first, provider.contextualInstance(NumberResource.class, first), "no client proxy to look behind");
    }

    @Test
    void aBoundedGenericProviderWithoutAScopeIsTheDependentBeanCassiniMakesIt() {
        NumberResolver<?> first = provider.getBean(NumberResolver.class);
        assertNotNull(first.answer, "the container injected it");
        assertNotSame(first, provider.getBean(NumberResolver.class));
    }

    @Test
    void bothAreResourceClasses() {
        var classes = provider.getResourceClasses();
        assertTrue(classes.contains(NumberResource.class), classes::toString);
        assertTrue(classes.contains(NumberResolver.class), classes::toString);
    }

    @Test
    void aClassThatIsNoBeanIsStillNotManaged() {
        var failure = assertThrows(IllegalArgumentException.class, () -> provider.getBean(NotABean.class));
        assertTrue(failure.getMessage().startsWith("Not managed by Vauban"), failure.getMessage());
        Object instance = new NotABean();
        assertSame(instance, provider.contextualInstance(NotABean.class, instance));
    }

    /**
     * The lookup by bean class changes the type match only: it takes the beans a lookup by class takes, those that
     * have {@code @Default}. A class whose bean has only another qualifier is still built by Cassini, as before
     * CASSINI-061 (CASSINI-064).
     */
    @Test
    void aClassWhoseBeanHasOnlyAnotherQualifierIsStillNotManaged() {
        var classes = provider.getResourceClasses();
        assertTrue(classes.contains(TaggedResource.class), classes::toString);
        var failure = assertThrows(IllegalArgumentException.class, () -> provider.getBean(TaggedResource.class));
        assertTrue(failure.getMessage().startsWith("Not managed by Vauban"), failure.getMessage());
    }
}
