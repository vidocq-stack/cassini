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
module io.vidocq.cassini.examples.jdkhttp {
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.jdkhttp;
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires java.net.http;
    requires jdk.httpserver;

    // ZERO opens AND zero exports on the resource package. cassini-processor generates a
    // <Resource>$$CassiniAdapter (dispatch + field injection, in-package) and a
    // <Resource>$$CassiniRoutes (route table) for each @Path class; we publish them as
    // ServiceLoader providers, so cassini-core obtains them through the module system
    // (which instantiates a provider even from a closed package) and never reflects into
    // this package — no `opens`, no `exports`. The adapter's invoke() does typed dispatch
    // (no reflective Method.invoke), so the package stays fully encapsulated. This is a pure
    // Mode-A app (cassini news the resource via the adapter's newInstance()).
    provides io.vidocq.cassini.spi.gen.ResourceAdapter
            with io.vidocq.cassini.examples.jdkhttp.resource.GreetingResource$$CassiniAdapter,
                 io.vidocq.cassini.examples.jdkhttp.resource.TodoResource$$CassiniAdapter;
    provides io.vidocq.cassini.spi.gen.RouteProvider
            with io.vidocq.cassini.examples.jdkhttp.resource.GreetingResource$$CassiniRoutes,
                 io.vidocq.cassini.examples.jdkhttp.resource.TodoResource$$CassiniRoutes;

    // §R-3 — Champollion resolves records via publicLookup, just exports.
    exports io.vidocq.cassini.examples.jdkhttp.model;
}
