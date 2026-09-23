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
package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.cassini.spi.http.CassiniStatistics;
import io.vidocq.cassini.spi.http.RouteDescription;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link CassiniStack} implementation — wraps a {@link DefaultCassiniHttpAdapter}
 * the route table its router resolved, and the adapter's counters.
 */
final class CassiniStackImpl implements CassiniStack {

    /** Suffix of the catch-all twin {@link ResourceScanner} emits for each dynamic locator. */
    private static final String CATCH_ALL_SUFFIX = "/{__rest:.*}";

    private final CassiniHttpAdapter adapter;
    private final List<RouteDescription> routes;
    private final Optional<CassiniStatistics> statistics;

    /** @param statistics the counters the adapter updates, {@code null} when they are off */
    CassiniStackImpl(CassiniHttpAdapter adapter, List<ResourceMethod> sortedRoutes, RequestStatistics statistics) {
        this.adapter = adapter;
        this.routes = describe(sortedRoutes);
        this.statistics = Optional.ofNullable(statistics);
    }

    @Override
    public CassiniHttpAdapter adapter() {
        return adapter;
    }

    @Override
    public List<RouteDescription> routes() {
        return routes;
    }

    @Override
    public Optional<CassiniStatistics> statistics() {
        return statistics;
    }

    /**
     * Turns the router's table into plain values, keeping its order. A dynamic
     * locator is routed through two entries, its path and a catch-all below it;
     * only the first is listed, as the locator itself.
     */
    static List<RouteDescription> describe(List<ResourceMethod> sortedRoutes) {
        List<RouteDescription> out = new ArrayList<>(sortedRoutes.size());
        for (ResourceMethod r : sortedRoutes) {
            if (r.dynamicLocator()) {
                if (r.path().endsWith(CATCH_ALL_SUFFIX)) continue;
                Method locator = r.locatorChain().getLast();
                out.add(new RouteDescription("", r.path(), locator.getDeclaringClass().getName(),
                        locator.getName(), r.produces(), r.consumes()));
            } else {
                out.add(new RouteDescription(r.httpMethod(), r.path(), r.beanClass().getName(),
                        r.javaMethod().getName(), r.produces(), r.consumes()));
            }
        }
        return List.copyOf(out);
    }
}
