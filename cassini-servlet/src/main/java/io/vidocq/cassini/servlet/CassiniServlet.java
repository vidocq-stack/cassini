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

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Application;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves a Jakarta REST application as a Servlet. Every request goes through the container first, so its context
 * path, filters, security constraints and authentication mechanism apply to the resources like to any servlet.
 *
 * <p>The beans come from the {@link BeanProvider} found in the {@value #BEAN_PROVIDER_ATTRIBUTE} attribute of the
 * {@code ServletContext}, which the host sets before the servlet initialises; without it the stack takes the one
 * {@code ServiceLoader} finds, if any.
 */
public final class CassiniServlet extends HttpServlet {

    /** Name of the {@code ServletContext} attribute that carries the {@link BeanProvider}. */
    public static final String BEAN_PROVIDER_ATTRIBUTE = "io.vidocq.cassini.servlet.beanProvider";

    private final Class<? extends Application> applicationClass;
    private final AtomicReference<CassiniHttpAdapter> adapter = new AtomicReference<>();

    /**
     * @param applicationClass the {@code Application} subclass this servlet serves, or {@code null} to serve every
     *                         resource and provider the beans offer
     */
    public CassiniServlet(Class<? extends Application> applicationClass) {
        this.applicationClass = applicationClass;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        try {
            CassiniStack.Builder builder = CassiniStack.builder();
            if (applicationClass != null) {
                builder.application(applicationClass.getDeclaredConstructor().newInstance());
            }
            if (getServletContext().getAttribute(BEAN_PROVIDER_ATTRIBUTE) instanceof BeanProvider beans) {
                builder.beanProvider(beans);
            }
            this.adapter.set(builder.build().adapter());
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ServletException("The REST application of servlet " + getServletName() + " does not start", e);
        }
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        ServletHttpExchange exchange = new ServletHttpExchange(request, response);
        try {
            adapter.get().dispatch(exchange).toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServletException("The request was interrupted", e);
        } catch (ExecutionException | CompletionException e) {
            throw new ServletException("The REST dispatch failed", e.getCause() == null ? e : e.getCause());
        }
        exchange.commit();
    }
}
