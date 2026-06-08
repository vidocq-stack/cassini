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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormDecoderTest {

    @Test
    void parsesBasicPairs() {
        Map<String, List<String>> m = FormDecoder.parse("a=1&b=2");
        assertEquals(List.of("1"), m.get("a"));
        assertEquals(List.of("2"), m.get("b"));
    }

    @Test
    void mergesRepeatedKeys() {
        Map<String, List<String>> m = FormDecoder.parse("tag=java&tag=rest&tag=cdi");
        assertEquals(List.of("java", "rest", "cdi"), m.get("tag"));
    }

    @Test
    void decodesPercentAndPlus() {
        Map<String, List<String>> m = FormDecoder.parse("q=hello+world&accent=%C3%A9");
        assertEquals(List.of("hello world"), m.get("q"));
        assertEquals(List.of("é"), m.get("accent"));
    }

    @Test
    void handlesEmpty() {
        assertEquals(Map.of(), FormDecoder.parse(""));
        assertEquals(Map.of(), FormDecoder.parse(null));
    }

    @Test
    void handlesFlagParam() {
        Map<String, List<String>> m = FormDecoder.parse("flag&k=v");
        assertEquals(List.of(""), m.get("flag"));
        assertEquals(List.of("v"), m.get("k"));
    }
}
