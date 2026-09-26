package com.example.crosspath.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class WireRecordTest {
    @Test public void acceptsBoundaryValues() {
        assertEquals(1, new WireRecord(1, 0).userId);
        assertEquals(255, new WireRecord(0xFFFFFF, 255).municipalityCode);
    }

    @Test public void rejectsReservedAndOutOfRangeIds() {
        for (int id : new int[]{Integer.MIN_VALUE, -1, 0, 0x1000000, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new WireRecord(id, 0));
        }
    }

    @Test public void rejectsOutOfRangeLocations() {
        for (int location : new int[]{Integer.MIN_VALUE, -1, 256, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new WireRecord(1, location));
        }
    }

    @Test public void wireValueContainsOnlyTwoIntegers() {
        assertEquals(2, WireRecord.class.getDeclaredFields().length);
        for (java.lang.reflect.Field field : WireRecord.class.getDeclaredFields()) {
            assertEquals(int.class, field.getType());
        }
    }
}
