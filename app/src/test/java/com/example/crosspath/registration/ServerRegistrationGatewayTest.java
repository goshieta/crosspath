package com.example.crosspath.registration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * ServerRegistrationGateway のユニットテスト。仕様: crosspath-registration-spec.md §3.8
 *
 * 呼び出し側（UserProfile）が通信前に永続化した request_id をそのまま登録APIへ渡し、
 * 採番結果を store に保存することを検証する。request_id の所有はアプリ側にあるため、
 * gateway（＝RegistrationRepository）は request_id を保存しない。
 */
public class ServerRegistrationGatewayTest {

    /** テスト用の偽 HTTP 実装（採番値と受け取った requestId を記録する）。 */
    private static class FakeHttp implements RegistrationHttp {
        String capturedSecret;
        String capturedRequestId;
        int registerCallCount;

        @Override
        public RegistrationResponse register(String secret, String requestId) {
            registerCallCount++;
            this.capturedSecret = secret;
            this.capturedRequestId = requestId;
            return RegistrationResponse.from(201,
                    "{\"request_id\":\"" + requestId
                            + "\",\"user_id\":42,\"created_at\":\"2026-09-27T01:00:00Z\"}",
                    "trace-reg", 0);
        }

        @Override
        public RegistrationResponse fetchMe(String secret) {
            this.capturedSecret = secret;
            return RegistrationResponse.from(200,
                    "{\"request_id\":\"me-uuid\",\"user_id\":42,\"created_at\":\"2026-09-27T01:00:00Z\"}",
                    "trace-me", 0);
        }
    }

    @Test
    public void passesCallerRequestIdToServerAndStoresAssignedUser() throws Exception {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();
        RegistrationGateway gateway = new ServerRegistrationGateway(
                new RegistrationRepository(http, store));

        int userId = gateway.register("caller-request-id", "TestUser");

        assertEquals(42, userId);
        assertEquals("caller-request-id", http.capturedRequestId);
        assertNotNull(http.capturedSecret);
        assertEquals(42, store.userId());
        assertEquals("2026-09-27T01:00:00Z", store.createdAt());
        assertNotNull("secret は通信前に保存される", store.secret());
        assertNull("request_id の所有はアプリ側（gateway は保存しない）", store.requestId());
    }

    @Test
    public void registeredDeviceReturnsStoredIdWithoutCallingServer() throws Exception {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();
        store.saveUser(7, "2026-09-27T00:00:00Z");
        RegistrationGateway gateway = new ServerRegistrationGateway(
                new RegistrationRepository(http, store));

        assertEquals(7, gateway.register("another-request-id", "TestUser"));
        assertEquals("再登録ではサーバーを呼ばない", 0, http.registerCallCount);
    }
}
