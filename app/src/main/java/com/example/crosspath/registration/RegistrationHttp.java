package com.example.crosspath.registration;

/**
 * 登録APIへのHTTP通信インターフェース。仕様: crosspath-registration-spec.md §3.4
 *
 * テスト用の偽実装に差し替え可能にする。
 */
public interface RegistrationHttp {
    /**
     * 新規登録（POST /v1/registrations）を実行する。
     *
     * @param secret    Authorization Bearer トークン
     * @param requestId リクエストID（UUID v4）
     * @return サーバー応答
     * @throws Exception 通信エラー（IO例外など）
     */
    RegistrationResponse register(String secret, String requestId) throws Exception;

    /**
     * 登録済み情報の再取得（GET /v1/registrations/me）を実行する。
     *
     * @param secret Authorization Bearer トークン
     * @return サーバー応答
     * @throws Exception 通信エラー（IO例外など）
     */
    RegistrationResponse fetchMe(String secret) throws Exception;
}