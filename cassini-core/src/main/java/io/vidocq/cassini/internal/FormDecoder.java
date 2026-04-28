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
 * Décodeur {@code application/x-www-form-urlencoded} (RFC 3986).
 *
 * <p>Lecture paresseuse du corps HTTP, parsing en {@code Map<name, List<value>>}.
 * Les valeurs sont décodées en UTF-8 après remplacement {@code '+'} → {@code ' '}
 * comme le veut le MIME type historique.</p>
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

    /** Si {@code decode} est {@code false}, préserve les triplets {@code %XX}
     *  bruts et ne transforme pas {@code '+'} en espace — utilisé pour
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
