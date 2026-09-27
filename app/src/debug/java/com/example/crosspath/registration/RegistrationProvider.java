package com.example.crosspath.registration;

import com.example.crosspath.data.WireRecord;

/** Demo only: allocate distinct IDs to the demo devices. */
public final class RegistrationProvider {
    public static final boolean HAS_DEBUG_INPUT = true;
    public static RegistrationGateway gateway() {
        return (requestId, name, debugId) -> {
            try {
                int id = Integer.parseInt(debugId);
                WireRecord.requireUserId(id);
                return id;
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("デモ用IDは1〜16777215の整数で指定してください。端末間で重複させないでください。");
            }
        };
    }
    private RegistrationProvider() {}
}
