package io.vidocq.cassini.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template URI JAX-RS 4.0 (§3.1.1 / §3.7).
 *
 * <p>Supporte :</p>
 * <ul>
 *   <li>segments littéraux : {@code /foo/bar}</li>
 *   <li>paramètres par défaut : {@code /{id}} (regex {@code [^/]+})</li>
 *   <li>paramètres contraints : {@code /{id:[0-9]+}} ou {@code /{path:.*}}</li>
 * </ul>
 *
 * <p>Expose les trois métriques de spécificité utilisées pour la sélection
 * best-match (§3.7.2) :</p>
 * <ol>
 *   <li>{@link #literalChars()} — caractères non capturés (littéraux)</li>
 *   <li>{@link #totalCaptures()} — nombre total de groupes capturants</li>
 *   <li>{@link #defaultCaptures()} — groupes capturants utilisant le regex par défaut</li>
 * </ol>
 */
public final class UriTemplate {

    private static final String DEFAULT_REGEX = "[^/]+";

    private final String template;
    private final Pattern pattern;
    /** Noms logiques des paramètres (dans l'ordre, avec doublons). */
    private final List<String> paramNames;
    /** Noms de groupes capturants dans le regex (uniques, parallèles à paramNames). */
    private final List<String> groupNames;
    /** Noms logiques dédupliqués (ordre de première apparition). */
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
        List<String> names = new ArrayList<>();   // logiques, avec doublons
        List<String> groups = new ArrayList<>();  // noms de groupe regex, uniques
        // §3.7 : même paramètre peut apparaître plusieurs fois (ex. /{id}/{id}/{id}).
        // Chaque occurrence capture indépendamment pour alimenter List<String> @PathParam.
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
                // Nom de groupe positionnel p0, p1, p2… — valide en Java regex (alphanum seul)
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

    /** Retourne les path params multi-valués (plusieurs occurrences du même nom → List). */
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
