package io.vidocq.cassini.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code application/x-www-form-urlencoded} decoder (RFC 3986).
 *
 * <p>Lazily reads the HTTP body, parsing it into {@code Map<name, List<value>>}.
 * Values are decoded as UTF-8 after replacing {@code '+'} → {@code ' '}
 * as required by the historical MIME type.</p>
 */
public final class FormDecoder {

    private FormDecoder() {}

    public static Map<String, List<String>> decode(byte[] body) {
        return parse(new String(body, StandardCharsets.UTF_8));
    }

    public static Map<String, List<String>> decode(InputStream in) throws IOException {
        return decode(in.readAllBytes());
    }

    public static Map<String, List<String>> parse(String body) {
        return parse(body, true);
    }

    /** If {@code decode} is {@code false}, preserves raw {@code %XX}
     *  triplets and does not turn {@code '+'} into a space — used for
     *  {@code @Encoded} §3.2. */
    public static Map<String, List<String>> parse(String body, boolean decode) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return out;
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name;
            String value;
            if (eq < 0) {
                name = decode ? decodeToken(pair) : pair;
                value = "";
            } else {
                String rawName = pair.substring(0, eq);
                String rawValue = pair.substring(eq + 1);
                name = decode ? decodeToken(rawName) : rawName;
                value = decode ? decodeToken(rawValue) : rawValue;
            }
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String decodeToken(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
