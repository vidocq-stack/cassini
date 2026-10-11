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
package io.vidocq.cassini.cdi;

import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default scopes {@link CassiniScopeExtension} gives: only to classes a container can
 * instantiate. An interface carrying {@code @Path} is a client contract (MicroProfile Rest
 * Client), not a resource; scoping it makes a container generate a client proxy for it
 * (Vidocq/cassini#59).
 */
class CassiniScopeExtensionTest {

    private final CassiniScopeExtension extension = new CassiniScopeExtension();

    @Test
    void aPathClassWithoutScopeIsRequestScoped() {
        List<Class<?>> added = new ArrayList<>();

        extension.addPathDefaultScope(config(false, added));

        assertEquals(List.of(jakarta.enterprise.context.RequestScoped.class), added);
    }

    @Test
    void aPathInterfaceGetsNoScope() {
        List<Class<?>> added = new ArrayList<>();

        extension.addPathDefaultScope(config(true, added));

        assertEquals(List.of(), added);
    }

    @Test
    void aProviderInterfaceGetsNoScope() {
        List<Class<?>> added = new ArrayList<>();

        extension.addProviderDefaultScope(config(true, added));

        assertEquals(List.of(), added);
    }

    /** A class (or interface) without any annotation; records the annotations added to it. */
    private static ClassConfig config(boolean isInterface, List<Class<?>> added) {
        ClassInfo info = (ClassInfo) Proxy.newProxyInstance(CassiniScopeExtensionTest.class.getClassLoader(),
                new Class<?>[]{ClassInfo.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isInterface" -> isInterface;
                    case "isAbstract" -> isInterface;
                    case "hasAnnotation" -> false;
                    case "name" -> "Sample";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (ClassConfig) Proxy.newProxyInstance(CassiniScopeExtensionTest.class.getClassLoader(),
                new Class<?>[]{ClassConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "info" -> info;
                    case "addAnnotation" -> {
                        Object a = args[0];
                        added.add(a instanceof Class<?> c ? c : ((Annotation) a).annotationType());
                        yield proxy;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
