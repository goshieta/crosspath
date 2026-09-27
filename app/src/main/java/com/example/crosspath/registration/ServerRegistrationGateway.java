package com.example.crosspath.registration;

/**
 * {@link RegistrationGateway} の本番実装。仕様: crosspath-registration-spec.md §3.8
 *
 * 登録プロトコル（secret の生成と暗号化保存・冪等性・リトライ・409 復旧）は
 * {@link RegistrationRepository} に集約されているため、ここは request_id を渡して
 * 採番された user_id を受け取るだけの薄いアダプタにする。
 */
public final class ServerRegistrationGateway implements RegistrationGateway {
    private final RegistrationRepository repository;

    public ServerRegistrationGateway(RegistrationRepository repository) {
        this.repository = repository;
    }

    @Override
    public int register(String requestId, String name) throws RegistrationException {
        return repository.registerWithRequestId(requestId, name);
    }
}
