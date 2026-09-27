package com.example.crosspath.registration;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RegistrationJson のユニットテスト。仕様: crosspath-registration-spec.md §5.2
 */
public class RegistrationJsonTest {

    @Test
    public void requestBody_returnsCorrectFormat() {
        String body = RegistrationJson.requestBody("test-uuid");
        assertEquals("{\"request_id\":\"test-uuid\"}", body);
    }

    @Test
    public void requestBody_hasOnlyOneKey() {
        String body = RegistrationJson.requestBody("uuid");
        // キーが1つだけであることを確認（カンマが無い）
        assertFalse(body.contains(","));
    }

    @Test
    public void string_extractsValue() {
        String json = "{\"request_id\":\"abc-123\",\"user_id\":42}";
        assertEquals("abc-123", RegistrationJson.string(json, "request_id"));
    }

    @Test
    public void string_returnsNullForMissingKey() {
        String json = "{\"request_id\":\"abc\"}";
        assertNull(RegistrationJson.string(json, "nonexistent"));
    }

    @Test
    public void string_returnsNullForNullInput() {
        assertNull(RegistrationJson.string(null, "key"));
    }

    @Test
    public void integer_extractsValue() {
        String json = "{\"user_id\":123456}";
        assertEquals(Integer.valueOf(123456), RegistrationJson.integer(json, "user_id"));
    }

    @Test
    public void integer_returnsNullForMissingKey() {
        String json = "{\"user_id\":123}";
        assertNull(RegistrationJson.integer(json, "nonexistent"));
    }

    @Test
    public void integer_returnsNullForNullInput() {
        assertNull(RegistrationJson.integer(null, "key"));
    }

    @Test
    public void bool_extractsTrue() {
        String json = "{\"retryable\":true}";
        assertTrue(RegistrationJson.bool(json, "retryable"));
    }

    @Test
    public void bool_extractsFalse() {
        String json = "{\"retryable\":false}";
        assertFalse(RegistrationJson.bool(json, "retryable"));
    }

    @Test
    public void bool_returnsNullForMissingKey() {
        String json = "{\"retryable\":true}";
        assertNull(RegistrationJson.bool(json, "nonexistent"));
    }

    @Test
    public void bool_returnsNullForNullInput() {
        assertNull(RegistrationJson.bool(null, "key"));
    }

    @Test
    public void nestedString_extractsNestedValue() {
        String json = "{\"error\":{\"code\":\"REGISTRATION_CONFLICT\",\"retryable\":false}}";
        assertEquals("REGISTRATION_CONFLICT",
                RegistrationJson.nestedString(json, "error", "code"));
    }

    @Test
    public void nestedString_returnsNullForMissingOuter() {
        String json = "{\"error\":{\"code\":\"X\"}}";
        assertNull(RegistrationJson.nestedString(json, "nonexistent", "code"));
    }

    @Test
    public void nestedString_returnsNullForMissingInner() {
        String json = "{\"error\":{\"code\":\"X\"}}";
        assertNull(RegistrationJson.nestedString(json, "error", "nonexistent"));
    }

    @Test
    public void string_handlesMalformedJsonGracefully() {
        // 壊れたJSONでも例外を投げず null を返す
        assertNull(RegistrationJson.string("not json at all", "key"));
        assertNull(RegistrationJson.string("{\"incomplete\": true", "key"));
        assertNull(RegistrationJson.string("", "key"));
    }

    @Test
    public void integer_handlesMalformedJsonGracefully() {
        assertNull(RegistrationJson.integer("not json", "key"));
        assertNull(RegistrationJson.integer("{\"user_id\":abc}", "user_id"));
    }

    @Test
    public void bool_handlesMalformedJsonGracefully() {
        assertNull(RegistrationJson.bool("not json", "key"));
        assertNull(RegistrationJson.bool("{\"retryable\":maybe}", "retryable"));
    }

    @Test
    public void nestedString_handlesMalformedJsonGracefully() {
        assertNull(RegistrationJson.nestedString("not json", "error", "code"));
        // 外側の括弧が欠けていても内側オブジェクトがパースできれば値を返す（クラッシュしない）
        assertEquals("X",
                RegistrationJson.nestedString("{\"error\":{\"code\":\"X\"}", "error", "code"));
    }

    @Test
    public void integer_extractsNegativeValue() {
        String json = "{\"offset\":-1}";
        assertEquals(Integer.valueOf(-1), RegistrationJson.integer(json, "offset"));
    }
}