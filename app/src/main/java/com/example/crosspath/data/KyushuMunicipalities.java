package com.example.crosspath.data;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen e-Stat snapshot; see municipalities/README.md for provenance and allocation rules. */
public final class KyushuMunicipalities {
    public static final String VERSION = "kyushu-2026-09-26-v1";

    private KyushuMunicipalities() { }

    /** 都道府県とその市町村の一覧をTSV出現順・コード昇順で返す（不変）。 */
    public static List<Prefecture> prefectures() {
        return parsed().prefectures;
    }

    public static MunicipalityMaster load() {
        return new MunicipalityMaster(VERSION, parsed().names);
    }

    private static volatile ParsedCache cache;

    private static ParsedCache parsed() {
        if (cache != null) return cache;
        synchronized (KyushuMunicipalities.class) {
            if (cache != null) return cache;
            cache = doParse();
            return cache;
        }
    }

    private static ParsedCache doParse() {
        InputStream stream = KyushuMunicipalities.class.getResourceAsStream(
                "/municipalities/kyushu-2026-09-26-v1.tsv");
        if (stream == null) throw new IllegalStateException("Missing bundled municipality master");
        Map<Integer, String> names = new HashMap<>();
        Set<String> officialCodes = new HashSet<>();
        List<Prefecture> prefectures = new ArrayList<>();
        List<Entry> currentEntries = null;
        String currentPrefecture = null;
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
                // 都道府県でグループ化（TSV は都道府県ごとに連続している前提）
                if (!fields[2].equals(currentPrefecture)) {
                    currentPrefecture = fields[2];
                    currentEntries = new ArrayList<>();
                    prefectures.add(new Prefecture(currentPrefecture, currentEntries));
                }
                currentEntries.add(new Entry(code, fields[3]));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load municipality master", e);
        }
        if (names.size() != 233) throw new IllegalStateException("Incomplete municipality master");
        return new ParsedCache(Collections.unmodifiableMap(names), Collections.unmodifiableList(prefectures));
    }

    private static final class ParsedCache {
        final Map<Integer, String> names;
        final List<Prefecture> prefectures;
        ParsedCache(Map<Integer, String> names, List<Prefecture> prefectures) {
            this.names = names;
            this.prefectures = prefectures;
        }
    }

    /** 都道府県とその市町村一覧。 */
    public static final class Prefecture {
        public final String name;
        public final List<Entry> municipalities;
        Prefecture(String name, List<Entry> municipalities) {
            this.name = name;
            this.municipalities = Collections.unmodifiableList(municipalities);
        }
    }

    /** 1つの市町村エントリ（コード・名称）。 */
    public static final class Entry {
        public final int code;
        public final String name;
        Entry(int code, String name) {
            this.code = code;
            this.name = name;
        }
    }
}
