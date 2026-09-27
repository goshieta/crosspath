package com.example.crosspath.registration;

/**
 * 登録情報の永続化インターフェース。仕様: crosspath-registration-spec.md §3.6
 *
 * InMemoryRegistrationStore（テスト用）と PrefsRegistrationStore（Android実装）の2実装を持つ。
 */
public interface RegistrationStore {
    /** 保存済み secret（無ければ null）。 */
    String secret();

    /** secret を保存する。 */
    void saveSecret(String s);

    /** 保存済み request_id（無ければ null）。 */
    String requestId();

    /** request_id を保存する。 */
    void saveRequestId(String id);

    /** 登録済み user_id（0 は未登録）。 */
    int userId();

    /** user_id と created_at を保存する。 */
    void saveUser(int userId, String createdAt);

    /** 登録日時（ISO 8601）。無ければ null。 */
    String createdAt();
}