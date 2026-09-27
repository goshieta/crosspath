package com.example.crosspath.sync;
import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;
public class EncounterPolicyTest {
    @Test public void jitterBackoffAndNewRevision() {
        EncounterPolicy p = new EncounterPolicy(new Random(1)); p.begin("S");
        long now = 100;
        for (int i = 0; i < 9; i++) {
            p.record(9, 1, false, now);
            long delay = p.delay(9, 1, now), base = Math.min(300000, 5000L << Math.min(i, 6));
            assertTrue(delay >= base * 0.8 && delay <= Math.min(300000, base * 1.2));
            assertTrue(p.delay(9, 2, now) <= 5000); now += delay;
        }
        p.record(9, 2, true, now); assertTrue(p.delay(9, 2, now) >= 48000 && p.delay(9, 2, now) <= 72000);
        assertEquals(0, p.delay(9, 3, now + 5000));
    }
    @Test public void newPeriodAndRestartDiscardSuppressionAndKeepTokenWithinPeriod() {
        EncounterPolicy p = new EncounterPolicy(new Random(2)); long token = p.begin("A");
        p.record(8, 1, true, 0); assertEquals(token, p.begin("A"));
        p.begin("B"); assertEquals(0, p.delay(8, 1, 0));
        assertEquals(0, new EncounterPolicy(new Random()).delay(8, 1, 0));
    }
    @Test public void convergenceIsOptInDiagnosticAndRequiresVerifiedEmptyContacts() {
        EncounterPolicy p = new EncounterPolicy(new Random()); p.begin("A");
        p.convergence(1, true, true, 0); assertFalse(p.estimatedConvergence());
        p.setConvergenceThreshold(2); p.convergence(1, true, true, 0); assertFalse(p.estimatedConvergence());
        p.convergence(2, true, true, 0); assertTrue(p.estimatedConvergence());
        assertEquals(0, p.delay(3, 1, 0));
        p.convergence(3, false, false, 0); assertEquals(0, p.consecutiveEmpty());
        p.convergence(4, true, true, 1); assertFalse(p.estimatedConvergence());
    }
}
