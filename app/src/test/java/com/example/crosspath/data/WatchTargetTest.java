package com.example.crosspath.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchTargetTest {
    @Test public void rejectsMissingOrWhitespaceOnlyName() {
        for (String name : new String[]{null, "", "   ", "\t\r\n", "\u3000", "\u00a0"}) {
            assertThrows(IllegalArgumentException.class, () -> new WatchTarget(1, name, 0));
        }
    }

    @Test public void keepsNonBlankName() {
        assertEquals(" 山田 太郎 ", new WatchTarget(1, " 山田 太郎 ", 0).displayName);
    }
}
