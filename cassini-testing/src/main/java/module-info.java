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
/**
 * Testing support for Cassini (JAX-RS 4.0 on Chappe).
 *
 * <p>Starts an embedded Cassini stack on a free port and hands back its base
 * URI, so a test — or another brick's TCK runner — can exercise real HTTP
 * without pulling in a servlet container.</p>
 *
 * <p>This module is <b>published</b>, unlike {@code cassini-tck} which runs the
 * official Jakarta RESTful WS TCK and is never deployed.</p>
 */
module io.vidocq.cassini.testing {
    requires io.vidocq.cassini.api;
    requires io.vidocq.cassini.core;
    requires io.vidocq.cassini.chappe;
    requires io.vidocq.chappe.api;
    requires jakarta.ws.rs;
    requires jakarta.cdi;

    requires static java.net.http;

    exports io.vidocq.cassini.testing;
}
