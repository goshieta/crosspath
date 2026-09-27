package com.example.crosspath.registration;

public final class RegistrationProvider {
    public static final boolean HAS_DEBUG_INPUT = false;
    public static RegistrationGateway gateway() {
        return (requestId, name, unused) -> {
            throw new IllegalStateException("登録APIが未接続です。接続先の確定後に利用できます。");
        };
    }
    private RegistrationProvider() {}
}
