package com.example.crosspath.data;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Frozen e-Stat snapshot; see municipalities/README.md for provenance and allocation rules. */
public final class KyushuMunicipalities {
    public static final String VERSION = "kyushu-2026-09-26-v1";

    private KyushuMunicipalities() { }

    public static MunicipalityMaster load() {
        InputStream stream = KyushuMunicipalities.class.getResourceAsStream(
                "/municipalities/kyushu-2026-09-26-v1.tsv");
        if (stream == null) throw new IllegalStateException("Missing bundled municipality master");
        Map<Integer, String> names = new HashMap<>();
        Set<String> officialCodes = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 4) throw new IllegalStateException("Malformed municipality row");
                int code = Integer.parseInt(fields[0]);
                MunicipalityMaster.requireName(fields[2]);
                MunicipalityMaster.requireName(fields[3]);
                if (code < 1 || code > 233 || !fields[1].matches("4[0-6][0-9]{3}")
                        || !officialCodes.add(fields[1])
                        || names.put(code, fields[2] + fields[3]) != null) {
                    throw new IllegalStateException("Invalid or duplicate municipality code");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load municipality master", e);
        }
        if (names.size() != 233) throw new IllegalStateException("Incomplete municipality master");
        return new MunicipalityMaster(VERSION, names);
    }
}
