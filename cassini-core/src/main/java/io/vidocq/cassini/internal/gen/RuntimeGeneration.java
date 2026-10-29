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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The adapters Cassini generates at run time because the build did not: a resource compiled without the annotation
 * processor ({@code cassini-processor}) and not processed by the {@code cassini-maven-plugin}. That fallback is kept,
 * but it is the exception: it costs startup time, needs the resource's package opened to Cassini on the module path,
 * and has no ahead-of-time equivalent. So it is announced loudly: one warning when a stack is built, for the resource
 * classes without a build-time adapter, then one for each class generated later that no warning named yet (a
 * sub-resource, a bean parameter). Each class is announced once per JVM.
 */
public final class RuntimeGeneration {

    private static final Logger LOG = Logger.getLogger(RuntimeGeneration.class.getName());

    private static final String RULE = "=".repeat(100);

    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private static final AtomicReference<Consumer<String>> WARN = new AtomicReference<>(LOG::warning);

    private RuntimeGeneration() {
    }

    /** When a stack is built: warns once for the resource classes no build-time adapter serves. */
    public static void stackBuilt(Collection<Class<?>> resourceClasses) {
        List<String> generated = new ArrayList<>();
        for (Class<?> resource : resourceClasses) {
            if (!AdapterRegistry.hasBuildTimeAdapter(resource) && ANNOUNCED.add(resource.getName())) {
                generated.add(resource.getName());
            }
        }
        if (!generated.isEmpty()) {
            WARN.get().accept(warning(generated, "when the application starts"));
        }
    }

    /** An adapter generated at run time: warns unless a warning already named its class. */
    static void generated(Class<?> resource) {
        if (ANNOUNCED.add(resource.getName())) {
            WARN.get().accept(warning(List.of(resource.getName()), "on first use"));
        }
    }

    /** The warning, framed so that it stands out of the startup log. */
    static String warning(Collection<String> classNames, String when) {
        var sb = new StringBuilder("\n").append(RULE).append('\n');
        sb.append("CASSINI — RUNTIME CODE GENERATION FALLBACK\n");
        sb.append(classNames.size()).append(classNames.size() == 1 ? " adapter is" : " adapters are")
                .append(" generated ").append(when).append(" instead of at build time, for:\n");
        classNames.forEach(name -> sb.append("  - ").append(name).append('\n'));
        sb.append("""
                These classes were compiled without the Cassini annotation processor (cassini-processor) and not
                processed by the cassini-maven-plugin. This fallback is exceptional: it slows the startup, needs the
                package opened to io.vidocq.cassini.core on the module path (else Cassini falls back to reflection),
                and cannot run ahead of time (native image, CDS).
                Fix: add cassini-processor to the annotation processor path of the module that declares them, or
                run the cassini-maven-plugin `generate` goal on the archive that holds them.
                """);
        return sb.append(RULE).toString();
    }

    /** Routes the warnings to {@code sink}, and forgets what was announced; {@code null} restores the logger. */
    static void redirectForTests(Consumer<String> sink) {
        WARN.set(sink == null ? LOG::warning : sink);
        ANNOUNCED.clear();
    }
}
