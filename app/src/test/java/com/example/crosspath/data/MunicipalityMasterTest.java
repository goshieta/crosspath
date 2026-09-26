package com.example.crosspath.data;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class MunicipalityMasterTest {
    @Test public void resolvesOnlyKnownCodesAndCopiesInput() {
        Map<Integer, String> names = new HashMap<>();
        names.put(20, "テスト自治体");
        MunicipalityMaster master = new MunicipalityMaster("test-v1", names);
        names.clear();
        assertEquals("テスト自治体", new WireRecord(1, 20).requireMunicipalityName(master));
        assertThrows(IllegalArgumentException.class,
                () -> new WireRecord(1, 21).requireMunicipalityName(master));
    }

    @Test public void rejectsUnresolvableNames() {
        for (String name : new String[]{null, "", "   ", "\t\n", "\u3000", "\u00a0"}) {
            Map<Integer, String> names = new HashMap<>();
            names.put(20, name);
            assertThrows(IllegalArgumentException.class, () -> new MunicipalityMaster("test-v1", names));
        }
    }

    @Test public void rejectsInvalidMasterCodesAndEmptyMaster() {
        assertThrows(IllegalArgumentException.class, () -> new MunicipalityMaster("test-v1", new HashMap<>()));
        for (Integer code : new Integer[]{null, -1, 256}) {
            Map<Integer, String> names = new HashMap<>();
            names.put(code, "テスト自治体");
            assertThrows(IllegalArgumentException.class, () -> new MunicipalityMaster("test-v1", names));
        }
    }
}
