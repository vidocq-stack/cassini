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
package io.vidocq.cassini.jdkhttp;

import java.util.List;
import java.util.Map;

/**
 * What a response header must be to go on the wire (cassini#58). The JDK {@code HttpServer} only refuses CR and LF,
 * and sends anything else mangled; the Chappe adapter refuses the rest since cassini#56. This applies the same rule
 * before anything is sent.
 */
final class ResponseHeaders {

    /** The rule, worded for the log line a refusal prints. */
    static final String RULE = "A header name must be an RFC 9110 token, a value ISO-8859-1 text without CR, LF, NUL"
            + " or other control characters, and not null. Encode non-Latin-1 text per RFC 8187,"
            + " e.g. Content-Disposition: attachment; filename*=UTF-8''%E2%82%AC.pdf";

    /** A header that cannot be sent: its name and why, never its value. */
    static final class Rejected extends RuntimeException {
        private final String name;
        private final String reason;

        Rejected(String name, String reason) {
            super(null, null, false, false);
            this.name = name;
            this.reason = reason;
        }

        String name() { return name; }
        String reason() { return reason; }
    }

    private ResponseHeaders() {}

    /** Throws {@link Rejected} for the first header of {@code headers} the wire cannot carry. */
    static void check(Map<String, List<String>> headers) {
        for (var e : headers.entrySet()) {
            String name = e.getKey();
            if (!isToken(name)) {
                throw new Rejected(name, "the name is not an RFC 9110 token");
            }
            if (e.getValue() == null) {
                throw new Rejected(name, "the value is null");
            }
            for (String value : e.getValue()) {
                String reason = valueProblem(value);
                if (reason != null) {
                    throw new Rejected(name, reason);
                }
            }
        }
    }

    private static boolean isToken(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean tchar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tchar) {
                return false;
            }
        }
        return true;
    }

    /** Why {@code value} cannot be sent, or {@code null} when it can. */
    private static String valueProblem(String value) {
        if (value == null) {
            return "the value is null";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0xFF) {
                return "the value is not ISO-8859-1 text";
            }
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                return "the value holds a control character";
            }
        }
        return null;
    }

    /** {@code s} quoted, with every char outside printable ASCII escaped as {@code \}{@code uXXXX}. */
    static String printable(String s) {
        if (s == null) {
            return "null";
        }
        var sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.append('"').toString();
    }
}
