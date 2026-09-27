package com.example.crosspath.registration;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RegistrationRepository のユニットテスト。仕様: crosspath-registration-spec.md §5.5
 */
public class RegistrationRepositoryTest {

    /**
     * テスト用の偽 HTTP 実装。呼び出し履歴を記録し、設定された応答を返す。
     */
    private static class FakeHttp implements RegistrationHttp {
        RegistrationResponse nextRegisterResponse;
        RegistrationResponse nextFetchMeResponse;
        /** 複数回呼ばれたとき、次に使用する応答のリスト（null で次の応答を使う） */
        java.util.Queue<RegistrationResponse> registerResponseQueue;
        String capturedSecret;
        String capturedRequestId;
        int registerCallCount;
        boolean throwOnRegister;

        @Override
        public RegistrationResponse register(String secret, String requestId) throws Exception {
            registerCallCount++;
            if (throwOnRegister) {
                throw new java.io.IOException("simulated network error");
            }
            this.capturedSecret = secret;
            this.capturedRequestId = requestId;
            // キューがあればキューから
            if (registerResponseQueue != null && !registerResponseQueue.isEmpty()) {
                return registerResponseQueue.poll();
            }
            // 単発応答があればそれを返す（2回目以降は次へ）
            if (nextRegisterResponse != null && registerCallCount == 1) {
                return nextRegisterResponse;
            }
            // デフォルト: 201 成功
            return RegistrationResponse.from(201,
                    "{\"request_id\":\"" + requestId
                            + "\",\"user_id\":42,\"created_at\":\"2026-09-26T03:00:00Z\"}",
                    "trace-reg", 0);
        }

        @Override
        public RegistrationResponse fetchMe(String secret) throws Exception {
            this.capturedSecret = secret;
            if (nextFetchMeResponse == null) {
                return RegistrationResponse.from(200,
                        "{\"request_id\":\"me-uuid\",\"user_id\":42,\"created_at\":\"2026-09-26T03:00:00Z\"}",
                        "trace-me", 0);
            }
            return nextFetchMeResponse;
        }
    }

    @Test
    public void register_newUser201_savesUserId() throws Exception {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();
        RegistrationRepository repo = new RegistrationRepository(http, store);

        int userId = repo.register("TestUser");

        assertEquals(42, userId);
        assertEquals(42, store.userId());
        assertNotNull(store.secret());
        assertNotNull(store.requestId());
        assertEquals("2026-09-26T03:00:00Z", store.createdAt());
    }

    @Test
    public void register_429then201_retriesWithSameRequestId() throws Exception {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();

        // キュー: 1回目 = 429, 2回目 = 201
        http.registerResponseQueue = new java.util.LinkedList<>();
        http.registerResponseQueue.add(RegistrationResponse.from(429,
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"retryable\":true}}",
                "trace-429", 0));
        // 2回目はデフォルト（201）を使う

        RegistrationRepository repo = new RegistrationRepository(http, store);
        repo.register("TestUser");

        // 同じ request_id が使われたことを検証
        String savedRequestId = store.requestId();
        assertEquals(savedRequestId, http.capturedRequestId);
        assertEquals(42, store.userId());
    }

    @Test
    public void register_failure_storesSecretAndRequestId() {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();

        http.throwOnRegister = true;

        RegistrationRepository repo = new RegistrationRepository(http, store);
        try {
            repo.register("TestUser");
            fail("Expected RegistrationException");
        } catch (RegistrationException e) {
            assertEquals(RegistrationError.NETWORK, e.getRegistrationError());
        }

        // secret と request_id は保存されている（再試行で同じ値を使える）
        assertNotNull(store.secret());
        assertNotNull(store.requestId());
        // user_id は保存されていない
        assertEquals(0, store.userId());
    }

    @Test
    public void register_409thenMe200_recovers() throws Exception {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();

        // register → 409 (conflict)
        http.nextRegisterResponse = RegistrationResponse.from(409,
                "{\"error\":{\"code\":\"REGISTRATION_CONFLICT\",\"retryable\":false}}",
                "trace-409", 0);
        // fetchMe → 200
        http.nextFetchMeResponse = RegistrationResponse.from(200,
                "{\"request_id\":\"me-uuid\",\"user_id\":99,\"created_at\":\"2026-09-26T04:00:00Z\"}",
                "trace-me", 0);

        RegistrationRepository repo = new RegistrationRepository(http, store);
        int userId = repo.register("TestUser");

        assertEquals(99, userId);
        assertEquals(99, store.userId());
        assertEquals("2026-09-26T04:00:00Z", store.createdAt());
    }

    @Test
    public void register_409thenMe401_throwsException() {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();

        http.nextRegisterResponse = RegistrationResponse.from(409,
                "{\"error\":{\"code\":\"REGISTRATION_CONFLICT\",\"retryable\":false}}",
                "trace-409", 0);
        http.nextFetchMeResponse = RegistrationResponse.from(401,
                "{\"error\":{\"code\":\"INVALID_CREDENTIAL\",\"retryable\":false}}",
                "trace-401", 0);

        RegistrationRepository repo = new RegistrationRepository(http, store);
        try {
            repo.register("TestUser");
            fail("Expected RegistrationException");
        } catch (RegistrationException e) {
            assertEquals(RegistrationError.UNAUTHORIZED, e.getRegistrationError());
        }
    }

    @Test
    public void register_alreadyRegistered_returnsImmediately() throws Exception {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();
        store.saveUser(100, "2026-09-26T05:00:00Z");

        RegistrationRepository repo = new RegistrationRepository(http, store);
        int userId = repo.register("TestUser");

        assertEquals(100, userId);
        // HTTP を呼ばなかったことを確認（capturedSecret が null）
        assertNull(http.capturedSecret);
    }

    @Test
    public void register_networkError_throwsNetworkError() {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();
        http.throwOnRegister = true;

        RegistrationRepository repo = new RegistrationRepository(http, store);
        try {
            repo.register("TestUser");
            fail("Expected RegistrationException");
        } catch (RegistrationException e) {
            assertEquals(RegistrationError.NETWORK, e.getRegistrationError());
        }
    }

    @Test
    public void register_secretNotPersisted_throwsWithoutCallingServer() {
        FakeHttp http = new FakeHttp();
        // Keystore 異常などで secret が保存できない状態を再現する
        InMemoryRegistrationStore store = new InMemoryRegistrationStore() {
            @Override
            public void saveSecret(String s) {
                // 保存しない
            }
        };

        RegistrationRepository repo = new RegistrationRepository(http, store);
        try {
            repo.register("TestUser");
            fail("Expected RegistrationException");
        } catch (RegistrationException e) {
            assertNotEquals(RegistrationError.NETWORK, e.getRegistrationError());
        }
        assertEquals(0, http.registerCallCount);
    }

    @Test
    public void register_exceptionMessageDoesNotContainSecret() {
        FakeHttp http = new FakeHttp();
        InMemoryRegistrationStore store = new InMemoryRegistrationStore();
        http.throwOnRegister = true;

        RegistrationRepository repo = new RegistrationRepository(http, store);
        try {
            repo.register("TestUser");
            fail("Expected RegistrationException");
        } catch (RegistrationException e) {
            String msg = e.getMessage();
            String secret = store.secret();
            if (secret != null) {
                assertFalse("Exception message must not contain secret",
                        msg != null && msg.contains(secret));
            }
            assertFalse("toString must not contain secret",
                    e.toString().contains(secret != null ? secret : ""));
        }
    }
}