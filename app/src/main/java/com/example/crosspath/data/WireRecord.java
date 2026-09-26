package com.example.crosspath.data;

/** The complete transport-facing value: no local timestamps or session metadata. */
public final class WireRecord {
    public final int userId;
    public final int municipalityCode;

    public WireRecord(int userId, int municipalityCode) {
        requireUserId(userId);
        if (municipalityCode < 0 || municipalityCode > 255) {
            throw new IllegalArgumentException("municipalityCode must be 0..255");
        }
        this.userId = userId;
        this.municipalityCode = municipalityCode;
    }

    public static void requireUserId(int userId) {
        if (userId < 1 || userId > 0xFFFFFF) {
            throw new IllegalArgumentException("userId must be 1..16777215 (0 is reserved)");
        }
    }
}
