package com.example.crosspath.registration;

/**
 * 登録処理の継ぎ目（ワーカースレッドから呼ばれる）。仕様: crosspath-registration-spec.md §3.8
 *
 * 実装は本番IDサーバー接続（{@link ServerRegistrationGateway}）で、secret の生成・暗号化保存、
 * リトライ、409 の競合復旧までを含む。呼び出し側は「同じ requestId で再試行すれば同じ user_id が返る」
 * ことだけを前提にしてよい。
 */
public interface RegistrationGateway {
    /**
     * @param requestId 冪等キー（呼び出し側が通信前に永続化した UUID v4）
     * @param name      表示名（null 不可）
     * @return サーバー採番の user_id（1〜16,777,215）
     * @throws RegistrationException 登録失敗時
     */
    int register(String requestId, String name) throws RegistrationException;
}
