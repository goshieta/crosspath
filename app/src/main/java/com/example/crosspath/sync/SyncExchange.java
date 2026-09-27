package com.example.crosspath.sync;
import com.example.crosspath.protocol.MessageAssembler;
public interface SyncExchange {
    void ready(boolean client, long token);
    void receive(MessageAssembler.Message message);
    void pauseAckAfterCommitForDebug(boolean enabled);
    void stop();
}
