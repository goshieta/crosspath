package com.example.crosspath.data;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class KyushuMunicipalitiesTest {
    @Test public void loadsAllSevenPrefecturesAndRejectsUnassignedCodes() {
        MunicipalityMaster master = KyushuMunicipalities.load();
        Map<String, Integer> counts = new HashMap<>();
        for (int code = 1; code <= 233; code++) {
            String name = master.requireName(code);
            String prefecture = name.substring(0, name.indexOf('県') + 1);
            counts.put(prefecture, counts.getOrDefault(prefecture, 0) + 1);
        }
        assertEquals(7, counts.size());
        assertEquals(Integer.valueOf(60), counts.get("福岡県"));
        assertEquals(Integer.valueOf(20), counts.get("佐賀県"));
        assertEquals(Integer.valueOf(21), counts.get("長崎県"));
        assertEquals(Integer.valueOf(45), counts.get("熊本県"));
        assertEquals(Integer.valueOf(18), counts.get("大分県"));
        assertEquals(Integer.valueOf(26), counts.get("宮崎県"));
        assertEquals(Integer.valueOf(43), counts.get("鹿児島県"));
        for (int code : new int[]{-1, 0, 234, 255, 256, 40100}) {
            assertThrows(IllegalArgumentException.class, () -> master.requireName(code));
        }
    }

    @Test public void freezesRepresentativeCodeAssignmentsAndVersion() {
        MunicipalityMaster master = KyushuMunicipalities.load();
        assertEquals("kyushu-2026-09-26-v1", master.version);
        assertEquals("福岡県北九州市", master.requireName(1));
        assertEquals("福岡県福岡市", master.requireName(2));
        assertEquals("佐賀県佐賀市", master.requireName(61));
        assertEquals("長崎県長崎市", master.requireName(81));
        assertEquals("熊本県熊本市", master.requireName(102));
        assertEquals("大分県大分市", master.requireName(147));
        assertEquals("宮崎県宮崎市", master.requireName(165));
        assertEquals("鹿児島県鹿児島市", master.requireName(191));
        assertEquals("鹿児島県与論町", master.requireName(233));
        master.requireVersion(master.version);
        for (String version : new String[]{null, "", "kyushu-2026-09-26-v2"}) {
            assertThrows(IllegalArgumentException.class, () -> master.requireVersion(version));
        }
    }
}
