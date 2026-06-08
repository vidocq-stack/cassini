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

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaTypesTest {

    @Test
    void parsesSimpleType() {
        MediaType mt = MediaTypes.parse("application/json");
        assertEquals("application", mt.getType());
        assertEquals("json", mt.getSubtype());
    }

    @Test
    void parsesParameters() {
        MediaType mt = MediaTypes.parse("text/plain;charset=UTF-8;q=0.5");
        assertEquals("UTF-8", mt.getParameters().get("charset"));
        assertEquals(0.5, MediaTypes.quality(mt));
    }

    @Test
    void wildcardMatchesAnything() {
        assertTrue(MediaTypes.matches(MediaType.WILDCARD_TYPE, MediaTypes.parse("application/json")));
        assertTrue(MediaTypes.matches(MediaTypes.parse("text/*"), MediaTypes.parse("text/plain")));
        assertFalse(MediaTypes.matches(MediaTypes.parse("text/*"), MediaTypes.parse("application/json")));
    }

    @Test
    void pickProducedPrefersSpecific() {
        List<MediaType> accepts = List.of(
                MediaTypes.parse("text/*;q=0.5"),
                MediaTypes.parse("application/json;q=0.9"));
        List<MediaType> produces = List.of(
                MediaTypes.parse("application/json"),
                MediaTypes.parse("text/plain"));
        Optional<MediaType> best = MediaTypes.pickProduced(accepts, produces);
        assertTrue(best.isPresent());
        assertEquals("application/json", best.get().getType() + "/" + best.get().getSubtype());
    }

    @Test
    void pickProducedReturnsEmptyWhenNoOverlap() {
        List<MediaType> accepts = List.of(MediaTypes.parse("image/png"));
        List<MediaType> produces = List.of(MediaTypes.parse("application/json"));
        assertTrue(MediaTypes.pickProduced(accepts, produces).isEmpty());
    }

    @Test
    void consumesMatchesAllowsEmpty() {
        assertTrue(MediaTypes.consumesMatches(MediaTypes.parse("application/json"), List.of()));
    }

    @Test
    void formatRoundtrip() {
        MediaType mt = new MediaType("application", "json", java.util.Map.of("charset", "UTF-8"));
        assertTrue(MediaTypes.format(mt).startsWith("application/json;charset=UTF-8"));
    }
}
