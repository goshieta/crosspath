package com.example.crosspath.data;

/** Short, synchronous local OS call only; never network or a wait on the repository executor. */
public interface NotificationSink {
    enum Delivery { POSTED, BLOCKED_PERMISSION }
    Delivery post(SafetyRepository.NotificationRequest request);
}
