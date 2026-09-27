package com.example.crosspath.registration;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * 登録処理の組み立て。仕様: crosspath-registration-spec.md §3.8
 *
 * デモ用のID手入力（debug ビルドの `RegistrationProvider.HAS_DEBUG_INPUT`）は廃止し、
 * debug / release とも本番IDサーバーへ接続する。端末間の重複はサーバーの採番で防ぐ。
 */
public final class RegistrationProvider {

    /** 計測テスト（androidTest）でネットワークを避けるための差し替え先。 */
    @Nullable
    private static volatile RegistrationGateway override;

    /** 本番実装（IDサーバー接続）。呼び出しはワーカースレッドから行う。 */
    public static RegistrationGateway gateway(Context context) {
        RegistrationGateway replaced = override;
        if (replaced != null) return replaced;
        Context app = context.getApplicationContext();
        return new ServerRegistrationGateway(new RegistrationRepository(
                new HttpRegistrationApi(),
                new PrefsRegistrationStore(app)));
    }

    /**
     * 計測テスト専用の差し替え。本番コードからは呼ばない。
     * null を渡すと本番実装に戻る。
     */
    @VisibleForTesting
    public static void overrideGatewayForTests(@Nullable RegistrationGateway gateway) {
        override = gateway;
    }

    private RegistrationProvider() {}
}
