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
package io.vidocq.cassini.servlet;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.HandlesTypes;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * Registers a {@link CassiniServlet} for each {@code Application} subclass that carries {@code @ApplicationPath}
 * (Jakarta REST 4.0 section 2, Servlet container deployment). A servlet of the same name that the application already
 * declared, in {@code web.xml} for instance, is left alone.
 */
@HandlesTypes(Application.class)
public final class CassiniServletInitializer implements ServletContainerInitializer {

    @Override
    @SuppressWarnings("unchecked")
    public void onStartup(Set<Class<?>> classes, ServletContext context) {
        if (classes == null) {
            return;
        }
        for (Class<?> c : classes) {
            ApplicationPath path = c.getAnnotation(ApplicationPath.class);
            if (path == null || !Application.class.isAssignableFrom(c) || Modifier.isAbstract(c.getModifiers())
                    || context.getServletRegistration(c.getName()) != null) {
                continue;
            }
            ServletRegistration.Dynamic servlet =
                    context.addServlet(c.getName(), new CassiniServlet((Class<? extends Application>) c));
            servlet.setAsyncSupported(true);
            servlet.setLoadOnStartup(1);
            servlet.addMapping(mapping(path.value()));
        }
    }

    /** {@code /api}, {@code api/} and {@code /api/*} all become {@code /api/*}; the root becomes {@code /*}. */
    static String mapping(String applicationPath) {
        String p = applicationPath == null ? "" : applicationPath.trim();
        if (p.endsWith("/*")) {
            p = p.substring(0, p.length() - 2);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.isEmpty() && !p.startsWith("/")) {
            p = "/" + p;
        }
        return p + "/*";
    }
}
