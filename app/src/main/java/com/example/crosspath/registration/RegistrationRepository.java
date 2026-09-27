package com.example.crosspath.registration;

import java.util.UUID;

/**
 * 登録処理を統括するリポジトリ。仕様: crosspath-registration-spec.md §3.8
 *
 * 初期登録・冪等性・リトライ・競合復旧を含めて1メソッドで提供する。
 * IOスレッドで呼ばれる前提。
 */
public final class RegistrationRepository {
    private final RegistrationHttp http;
    private final RegistrationStore store;

    public RegistrationRepository(RegistrationHttp http, RegistrationStore store) {
        this.http = http;
        this.store = store;
    }

    /**
     * 初回登録を実行する。登録済みの場合は保存済みの user_id を返す。
     *
     * @param name 表示名（null 不可）
     * @return サーバー採番の user_id（1〜16,777,215）
     * @throws RegistrationException 登録失敗時
     */
    public int register(String name) throws RegistrationException {
        // 1. 既に登録済みなら保存値を返す
        int existingUserId = store.userId();
        if (existingUserId > 0) {
            return existingUserId;
        }

        // 2. secret が無ければ生成して事前保存
        String secret = store.secret();
        if (secret == null) {
            secret = RegistrationSecret.generate();
            store.saveSecret(secret);
            // 保存できていないと、次回は別 secret を生成して 409（別secret扱い）に陥るため、ここで止める
            if (store.secret() == null) {
                throw new RegistrationException(RegistrationError.UNKNOWN,
                        "registration_secret を保存できませんでした", null);
            }
        }

        // 3. request_id が無ければ生成して事前保存（リトライで使い回す）
        String requestId = store.requestId();
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            store.saveRequestId(requestId);
        }

        // 4-7. リトライループ
        RegistrationError lastError = RegistrationError.NETWORK;
        String lastTraceId = null;

        for (int attempt = 0; attempt < RegistrationRetryPolicy.MAX_ATTEMPTS; attempt++) {
            try {
                RegistrationResponse response = http.register(secret, requestId);

                // 成功（200/201）
                if (response.getHttpStatus() == 200 || response.getHttpStatus() == 201) {
                    store.saveUser(response.getUserId(), response.getCreatedAt());
                    return response.getUserId();
                }

                // 409 — 競合: fetchMe で復旧を試みる
                if (response.getHttpStatus() == 409) {
                    RegistrationResponse meResponse = http.fetchMe(secret);
                    if (meResponse.getHttpStatus() == 200) {
                        store.saveUser(meResponse.getUserId(), meResponse.getCreatedAt());
                        return meResponse.getUserId();
                    }
                    // fetchMe も失敗
                    RegistrationError error = RegistrationError.fromHttp(
                            meResponse.getHttpStatus(), meResponse.getErrorCode());
                    throw new RegistrationException(error,
                            "fetchMe failed after conflict: " + meResponse.getHttpStatus(),
                            meResponse.getTraceId());
                }

                // リトライ判定
                if (RegistrationRetryPolicy.isRetryable(response, attempt)) {
                    long delay = RegistrationRetryPolicy.delayMillis(response, attempt);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RegistrationException(RegistrationError.NETWORK,
                                "リトライ待機中に割り込まれました", null, e);
                    }
                    lastError = RegistrationError.fromHttp(
                            response.getHttpStatus(), response.getErrorCode());
                    lastTraceId = response.getTraceId();
                    continue; // 次のリトライ
                }

                // リトライ不可
                lastError = RegistrationError.fromHttp(
                        response.getHttpStatus(), response.getErrorCode());
                lastTraceId = response.getTraceId();
                throw new RegistrationException(lastError,
                        "登録失敗: HTTP " + response.getHttpStatus()
                                + " code=" + response.getErrorCode(),
                        lastTraceId);
            } catch (RegistrationException e) {
                throw e;
            } catch (Exception e) {
                // ネットワーク例外など — リトライ
                if (attempt < RegistrationRetryPolicy.MAX_ATTEMPTS - 1) {
                    long delay = RegistrationRetryPolicy.delayMillisWithJitter(
                            RegistrationRetryPolicy.BASE_DELAY_MS * (1L << attempt),
                            RegistrationRetryPolicy.JITTER_FACTOR);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RegistrationException(RegistrationError.NETWORK,
                                "リトライ待機中に割り込まれました", null, ie);
                    }
                    lastError = RegistrationError.NETWORK;
                    continue;
                }
                throw new RegistrationException(RegistrationError.NETWORK,
                        "登録に失敗しました（ネットワーク）: " + e.getMessage(),
                        null, e);
            }
        }

        // 全リトライ失敗
        throw new RegistrationException(lastError,
                "全リトライが失敗しました", lastTraceId);
    }
}