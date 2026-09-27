package com.example.crosspath.registration;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RegistrationRetryPolicy のユニットテスト。仕様: crosspath-registration-spec.md §5.4
 */
public class RegistrationRetryPolicyTest {

    private RegistrationResponse makeResponse(int httpStatus, String errorCode,
                                              boolean errorRetryable, int retryAfter) {
        return RegistrationResponse.from(httpStatus,
                "{\"error\":{\"code\":\"" + (errorCode != null ? errorCode : "")
                        + "\",\"retryable\":" + errorRetryable + "}}",
                "trace", retryAfter);
    }

    private RegistrationResponse makeResponse(int httpStatus, String errorCode, boolean errorRetryable) {
        return makeResponse(httpStatus, errorCode, errorRetryable, 0);
    }

    @Test
    public void isRetryable_429_rateLimited_returnsTrue() {
        RegistrationResponse r = makeResponse(429, "RATE_LIMITED", true);
        assertTrue(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_500_internalError_returnsTrue() {
        RegistrationResponse r = makeResponse(500, "INTERNAL_ERROR", true);
        assertTrue(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_503_registrationBusy_returnsTrue() {
        RegistrationResponse r = makeResponse(503, "REGISTRATION_BUSY", true);
        assertTrue(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_gatewayErrors_returnsTrue() {
        // Cloud Run のゲートウェイ障害（ボディ無し）も一時障害としてリトライする
        assertTrue(RegistrationRetryPolicy.isRetryable(
                RegistrationResponse.from(502, null, "trace", 0), 0));
        assertTrue(RegistrationRetryPolicy.isRetryable(
                RegistrationResponse.from(504, null, "trace", 0), 0));
        assertTrue(RegistrationRetryPolicy.isRetryable(
                RegistrationResponse.from(408, null, "trace", 0), 0));
    }

    @Test
    public void isRetryable_409_conflict_returnsFalse() {
        RegistrationResponse r = makeResponse(409, "REGISTRATION_CONFLICT", false);
        assertFalse(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_410_retired_returnsFalse() {
        RegistrationResponse r = makeResponse(410, "REGISTRATION_RETIRED", false);
        assertFalse(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_400_invalidRequest_returnsFalse() {
        RegistrationResponse r = makeResponse(400, "INVALID_REQUEST", false);
        assertFalse(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_401_invalidCredential_returnsFalse() {
        RegistrationResponse r = makeResponse(401, "INVALID_CREDENTIAL", false);
        assertFalse(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_idSpaceExhausted_returnsFalse() {
        RegistrationResponse r = makeResponse(503, "ID_SPACE_EXHAUSTED", false);
        assertFalse(RegistrationRetryPolicy.isRetryable(r, 0));
    }

    @Test
    public void isRetryable_exceedsMaxAttempts_returnsFalse() {
        RegistrationResponse r = makeResponse(429, "RATE_LIMITED", true);
        assertFalse(RegistrationRetryPolicy.isRetryable(r, 4));
        assertFalse(RegistrationRetryPolicy.isRetryable(r, 5));
    }

    @Test
    public void delayMillis_retryAfterPreferred() {
        RegistrationResponse r = makeResponse(429, "RATE_LIMITED", true, 6);
        long delay = RegistrationRetryPolicy.delayMillis(r, 0);
        // Retry-After: 6 秒 = 6000ms
        assertTrue("delay should be >= 6000", delay >= 6000);
        assertTrue("delay should be <= 6000 (no jitter for retry-after)", delay <= 6000);
    }

    @Test
    public void delayMillis_retryAfterOne() {
        RegistrationResponse r = makeResponse(503, "REGISTRATION_BUSY", true, 1);
        long delay = RegistrationRetryPolicy.delayMillis(r, 0);
        assertEquals(1000L, delay);
    }

    @Test
    public void delayMillis_backoffIncreasesWithAttempt() {
        RegistrationResponse r = makeResponse(500, "INTERNAL_ERROR", true, 0);
        long d0 = RegistrationRetryPolicy.delayMillisWithJitter(
                RegistrationRetryPolicy.BASE_DELAY_MS * (1L << 0), 0);
        long d1 = RegistrationRetryPolicy.delayMillisWithJitter(
                RegistrationRetryPolicy.BASE_DELAY_MS * (1L << 1), 0);
        long d2 = RegistrationRetryPolicy.delayMillisWithJitter(
                RegistrationRetryPolicy.BASE_DELAY_MS * (1L << 2), 0);
        assertEquals(1000L, d0);
        assertEquals(2000L, d1);
        assertEquals(4000L, d2);
    }

    @Test
    public void delayMillis_jitterStaysWithinBounds() {
        long base = 1000L;
        for (int i = 0; i < 50; i++) {
            long delayed = RegistrationRetryPolicy.delayMillisWithJitter(base, 0.2);
            assertTrue("delay too small: " + delayed, delayed >= 800);
            assertTrue("delay too large: " + delayed, delayed <= 1200);
        }
    }
}