package com.example.crosspath.registration;

/** Worker-thread adapter. The server must return the same ID for retries of requestId. */
public interface RegistrationGateway {
    int register(String requestId, String name, String debugId) throws Exception;
}
