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
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ClientHeaders#responseCookies}: a {@code Set-Cookie} value that cannot be read is skipped. */
class ClientHeadersTest {

    @Test
    void a_set_cookie_value_that_cannot_be_read_is_skipped_and_the_others_kept() {
        Object unreadable = new Object() {
            @Override public String toString() { throw new IllegalStateException("unreadable"); }
        };
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.add("Set-Cookie", unreadable);
        headers.add("Set-Cookie", null);
        headers.add("Set-Cookie", "ok=1");

        Map<String, NewCookie> cookies = ClientHeaders.responseCookies(headers);

        assertEquals(Set.of("ok"), cookies.keySet());
    }
}
