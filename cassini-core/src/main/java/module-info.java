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
module io.vidocq.cassini.core {
    requires transitive io.vidocq.cassini.api;
    requires transitive jakarta.ws.rs;
    requires static jakarta.annotation;
    requires static jakarta.inject;

    requires static jakarta.xml.bind;
    requires static jakarta.activation;
    requires jakarta.json;
    requires jakarta.json.bind;
    requires io.vidocq.champollion.jsonp;
    requires io.vidocq.champollion.jsonb;

    requires static java.net.http;
    requires java.xml;
    requires java.logging;

    exports io.vidocq.cassini.internal
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.client,
               io.vidocq.cassini.maven.plugin;
    exports io.vidocq.cassini.internal.gen
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.maven.plugin;
    exports io.vidocq.cassini.internal.context
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.filter
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.multipart
            to io.vidocq.cassini.tck;
    exports io.vidocq.cassini.internal.runtime
            to io.vidocq.cassini.tck,
               io.vidocq.cassini.chappe,
               io.vidocq.cassini.jdkhttp,
               io.vidocq.cassini.client;
    exports io.vidocq.cassini.internal.transport
            to io.vidocq.cassini.tck;

    // No opens: every transport extends CassiniRuntimeDelegate through the
    // qualified export above — the reflective shims that needed deep access
    // are gone (CASSINI-003 unification).

    provides io.vidocq.cassini.spi.http.CassiniStack.BuilderFactory
            with io.vidocq.cassini.internal.CassiniStackBuilderFactory;

    // ServiceLoader-registered, build-time generated dispatch metadata. An application that
    // `provides ResourceAdapter with <Class>$$CassiniAdapter` (and likewise RouteProvider) lets the
    // module system instantiate the providers from its CLOSED resource package, so cassini-core
    // never reflects into it — no `opens`, no `exports` required (AdapterRegistry / RouteRegistry).
    uses io.vidocq.cassini.spi.gen.ResourceAdapter;
    uses io.vidocq.cassini.spi.gen.RouteProvider;

    // jakarta.ws.rs.ext.RuntimeDelegate provided by cassini-chappe or
    // cassini-jdk-http (transport-specific for SeBootstrap). Cassini-core
    // does not expose its CassiniRuntimeDelegate via ServiceLoader to avoid
    // a collision: that is the role of the active transport adapter.
}
