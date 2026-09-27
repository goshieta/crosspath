package com.example.crosspath.registration;

/**
 * メモリ上の RegistrationStore 実装（テスト用）。仕様: crosspath-registration-spec.md §3.6
 *
 * JVM で動作し、状態はインスタンス変数に保持する。
 */
public class InMemoryRegistrationStore implements RegistrationStore {
    private String secret;
    private String requestId;
    private int userId;
    private String createdAt;

    @Override
    public String secret() {
        return secret;
    }

    @Override
    public void saveSecret(String s) {
        this.secret = s;
    }

    @Override
    public String requestId() {
        return requestId;
    }

    @Override
    public void saveRequestId(String id) {
        this.requestId = id;
    }

    @Override
    public int userId() {
        return userId;
    }

    @Override
    public void saveUser(int userId, String createdAt) {
        this.userId = userId;
        this.createdAt = createdAt;
    }

    @Override
    public String createdAt() {
        return createdAt;
    }
}