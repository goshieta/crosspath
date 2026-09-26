package com.example.crosspath.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, caller-supplied code table. No fabricated or fallback municipality names. */
public final class MunicipalityMaster {
    private final Map<Integer, String> names;
    public final String version;

    public MunicipalityMaster(String version, Map<Integer, String> names) {
        requireName(version);
        this.version = version;
        Objects.requireNonNull(names, "names");
        if (names.isEmpty()) throw new IllegalArgumentException("Empty municipality master");
        Map<Integer, String> copy = new HashMap<>();
        for (Map.Entry<Integer, String> entry : names.entrySet()) {
            Integer code = entry.getKey();
            if (code == null || code < 0 || code > 255) {
                throw new IllegalArgumentException("municipalityCode must be 0..255");
            }
            requireName(entry.getValue());
            copy.put(code, entry.getValue());
        }
        this.names = Collections.unmodifiableMap(copy);
    }

    public String requireName(int code) {
        String name = names.get(code);
        if (name == null) throw new IllegalArgumentException("Unknown municipality code: " + code);
        return name;
    }

    public void requireVersion(String peerVersion) {
        if (!version.equals(peerVersion)) {
            throw new IllegalArgumentException("Municipality master version mismatch");
        }
    }

    static void requireName(String name) {
        if (name == null || name.codePoints().allMatch(
                c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Name must contain a non-whitespace character");
        }
    }
}
