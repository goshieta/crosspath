package com.example.crosspath.registration;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RegistrationResponse のユニットテスト。仕様: crosspath-registration-spec.md §5.3
 */
public class RegistrationResponseTest {

    @Test
    public void from_201_success() {
        RegistrationResponse r = RegistrationResponse.from(201,
                "{\"request_id\":\"uuid\",\"user_id\":67,\"created_at\":\"2026-09-26T03:00:00Z\"}",
                "trace-1", 0);
        assertTrue(r.isSuccess());
        assertEquals(201, r.getHttpStatus());
        assertEquals(67, r.getUserId());
        assertEquals("uuid", r.getRequestId());
        assertEquals("2026-09-26T03:00:00Z", r.getCreatedAt());
        assertEquals("trace-1", r.getTraceId());
    }

    @Test
    public void from_200_success() {
        RegistrationResponse r = RegistrationResponse.from(200,
                "{\"request_id\":\"uuid\",\"user_id\":123456,\"created_at\":\"2026-09-26T03:00:00Z\"}",
                "trace-2", 0);
        assertTrue(r.isSuccess());
        assertEquals(200, r.getHttpStatus());
        assertEquals(123456, r.getUserId());
    }

    @Test
    public void from_errorWithNestedCode() {
        String body = "{\"error\":{\"code\":\"REGISTRATION_CONFLICT\",\"retryable\":false}}";
        RegistrationResponse r = RegistrationResponse.from(409, body, "trace-3", 0);
        assertFalse(r.isSuccess());
        assertEquals(409, r.getHttpStatus());
        assertEquals("REGISTRATION_CONFLICT", r.getErrorCode());
        assertFalse(r.getErrorRetryable());
    }

    @Test
    public void from_idSpaceExhausted() {
        String body = "{\"error\":{\"code\":\"ID_SPACE_EXHAUSTED\",\"retryable\":false}}";
        RegistrationResponse r = RegistrationResponse.from(503, body, "trace-4", 0);
        assertEquals(503, r.getHttpStatus());
        assertEquals("ID_SPACE_EXHAUSTED", r.getErrorCode());
        assertFalse(r.getErrorRetryable());
    }

    @Test
    public void from_rateLimitedWithRetryAfter() {
        String body = "{\"error\":{\"code\":\"RATE_LIMITED\",\"retryable\":true}}";
        RegistrationResponse r = RegistrationResponse.from(429, body, "trace-5", 6);
        assertEquals(429, r.getHttpStatus());
        assertEquals("RATE_LIMITED", r.getErrorCode());
        assertTrue(r.getErrorRetryable());
        assertEquals(6, r.getRetryAfterSeconds());
    }

    @Test
    public void from_malformedJson_doesNotThrow() {
        // 壊れたJSONでも例外を投げず、errorCode=null のまま返す
        RegistrationResponse r = RegistrationResponse.from(500, "not json", "trace-6", 0);
        assertEquals(500, r.getHttpStatus());
        assertEquals(0, r.getUserId());
        assertNull(r.getErrorCode());
    }

    @Test
    public void from_nullBody_doesNotThrow() {
        RegistrationResponse r = RegistrationResponse.from(401, null, "trace-7", 0);
        assertEquals(401, r.getHttpStatus());
        assertEquals(0, r.getUserId());
        assertNull(r.getErrorCode());
    }
}