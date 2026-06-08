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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JAX-RS 4.0 URI template (§3.1.1 / §3.7).
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>literal segments: {@code /foo/bar}</li>
 *   <li>default parameters: {@code /{id}} (regex {@code [^/]+})</li>
 *   <li>constrained parameters: {@code /{id:[0-9]+}} or {@code /{path:.*}}</li>
 * </ul>
 *
 * <p>Exposes the three specificity metrics used for best-match selection
 * (§3.7.2):</p>
 * <ol>
 *   <li>{@link #literalChars()} — uncaptured (literal) characters</li>
 *   <li>{@link #totalCaptures()} — total number of capturing groups</li>
 *   <li>{@link #defaultCaptures()} — capturing groups using the default regex</li>
 * </ol>
 */
public final class UriTemplate {

    private static final String DEFAULT_REGEX = "[^/]+";

    private final String template;
    private final Pattern pattern;
    /** Logical parameter names (in order, with duplicates). */
    private final List<String> paramNames;
    /** Capturing group names in the regex (unique, parallel to paramNames). */
    private final List<String> groupNames;
    /** Deduplicated logical names (order of first appearance). */
    private final List<String> uniqParamNames;
    private final int literalChars;
    private final int totalCaptures;
    private final int defaultCaptures;

    private UriTemplate(String template, Pattern pattern, List<String> paramNames,
                        List<String> groupNames, List<String> uniqParamNames,
                        int literalChars, int totalCaptures, int defaultCaptures) {
        this.template = template;
        this.pattern = pattern;
        this.paramNames = paramNames;
        this.groupNames = groupNames;
        this.uniqParamNames = uniqParamNames;
        this.literalChars = literalChars;
        this.totalCaptures = totalCaptures;
        this.defaultCaptures = defaultCaptures;
    }

    public static UriTemplate compile(String template) {
        String t = normalize(template);
        StringBuilder regex = new StringBuilder("^");
        List<String> names = new ArrayList<>();   // logical, with duplicates
        List<String> groups = new ArrayList<>();  // regex group names, unique
        // §3.7: the same parameter may appear several times (e.g. /{id}/{id}/{id}).
        // Each occurrence captures independently to feed List<String> @PathParam.
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        int literals = 0;
        int total = 0;
        int defaults = 0;

        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '{') {
                int end = findClosingBrace(t, i);
                if (end < 0) throw new IllegalArgumentException("Unclosed '{' in template: " + template);
                String inside = t.substring(i + 1, end).trim();
                int colon = inside.indexOf(':');
                String name;
                String paramRegex;
                if (colon < 0) {
                    name = inside;
                    paramRegex = DEFAULT_REGEX;
                    defaults++;
                } else {
                    name = inside.substring(0, colon).trim();
                    paramRegex = inside.substring(colon + 1).trim();
                }
                if (name.isEmpty()) throw new IllegalArgumentException("Empty param name in template: " + template);
                seen.merge(name, 1, Integer::sum);
                names.add(name);
                // Positional group name p0, p1, p2… — valid in Java regex (alphanum only)
                String groupName = "p" + total;
                groups.add(groupName);
                total++;
                regex.append("(?<").append(groupName).append(">").append(paramRegex).append(")");
                i = end + 1;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                literals++;
                i++;
            }
        }
        regex.append("$");
        List<String> uniq = new ArrayList<>(new java.util.LinkedHashSet<>(names));
        return new UriTemplate(t, Pattern.compile(regex.toString()),
                List.copyOf(names), List.copyOf(groups), List.copyOf(uniq),
                literals, total, defaults);
    }

    /** Returns multi-valued path params (multiple occurrences of the same name → List). */
    public Optional<Map<String, List<String>>> match(String path) {
        Matcher m = pattern.matcher(path);
        if (!m.matches()) return Optional.empty();
        if (paramNames.isEmpty()) return Optional.of(Map.of());
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            String logicalName = paramNames.get(i);
            String groupName = groupNames.get(i);
            String val = m.group(groupName);
            params.computeIfAbsent(logicalName, k -> new ArrayList<>()).add(val);
        }
        return Optional.of(params);
    }

    public String template() { return template; }
    public List<String> paramNames() { return uniqParamNames; }
    public int literalChars() { return literalChars; }
    public int totalCaptures() { return totalCaptures; }
    public int defaultCaptures() { return defaultCaptures; }

    private static int findClosingBrace(String s, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
