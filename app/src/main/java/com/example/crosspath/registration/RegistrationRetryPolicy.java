package com.example.crosspath.registration;

import java.util.Random;

/**
 * 登録APIのリトライポリシー。仕様: crosspath-registration-spec.md §3.7
 *
 * 純粋ロジック（JVMでテスト可能）。
 */
public final class RegistrationRetryPolicy {
    /** 最大リトライ回数（初回を含めず、追加試行回数）。 */
    public static final int MAX_ATTEMPTS = 4;

    /** 指数バックオフの基本遅延（ミリ秒）。 */
    public static final long BASE_DELAY_MS = 1000L;

    /** ジッターの最大比率（±20% = 0.2）。 */
    public static final double JITTER_FACTOR = 0.2;

    private static final Random RANDOM = new Random();

    /** リトライ不可のエラーコード一覧。 */
    private static final String[] NON_RETRYABLE_CODES = {
            "ID_SPACE_EXHAUSTED",
            "REGISTRATION_CONFLICT",
            "REGISTRATION_RETIRED",
            "INVALID_REQUEST",
            "INVALID_CREDENTIAL"
    };

    private RegistrationRetryPolicy() {}

    /**
     * 応答と試行回数からリトライ可能か判定する。
     *
     * @param response サーバー応答
     * @param attempt  現在の試行回数（0始まり）
     * @return リトライ可能なら true
     */
    public static boolean isRetryable(RegistrationResponse response, int attempt) {
        if (attempt >= MAX_ATTEMPTS) return false;

        String errorCode = response.getErrorCode();
        if (errorCode != null) {
            for (String code : NON_RETRYABLE_CODES) {
                if (code.equals(errorCode)) return false;
            }
            // 明示的にリトライ可のエラーコード
            if ("RATE_LIMITED".equals(errorCode)
                    || "INTERNAL_ERROR".equals(errorCode)
                    || "REGISTRATION_BUSY".equals(errorCode)) {
                return true;
            }
        }
        // エラー応答の retryable フィールド
        if (response.getErrorRetryable()) return true;

        // ステータスコードによる判断
        // 429（レート制限）と 5xx（サーバー／ゲートウェイ側の一時障害。Cloud Run は 502/504 も返し得る）
        // はリトライ対象。ID_SPACE_EXHAUSTED(503) は上の NON_RETRYABLE_CODES で既に除外済み。
        int status = response.getHttpStatus();
        return status == 429 || status == 408 || status >= 500;
    }

    /**
     * リトライ前の待機時間を返す（ミリ秒）。ジッターを含む。
     *
     * @param response サーバー応答
     * @param attempt  現在の試行回数（0始まり）
     * @return 待機時間（ミリ秒）
     */
    public static long delayMillis(RegistrationResponse response, int attempt) {
        int retryAfter = response.getRetryAfterSeconds();
        if (retryAfter > 0) {
            return retryAfter * 1000L;
        }
        long baseDelay = BASE_DELAY_MS * (1L << attempt); // 1000, 2000, 4000, ...
        return applyJitter(baseDelay, JITTER_FACTOR);
    }

    /**
     * 指定されたジッター率で遅延に乱数を加える。
     * テスト用に公開。
     *
     * @param delay        基本遅延（ミリ秒）
     * @param jitterFactor ジッター率（0.2 で ±20%）
     * @return ジッター適用後の遅延
     */
    public static long delayMillisWithJitter(long delay, double jitterFactor) {
        return applyJitter(delay, jitterFactor);
    }

    private static long applyJitter(long delay, double jitterFactor) {
        if (jitterFactor <= 0) return delay;
        double jitter = (RANDOM.nextDouble() * 2.0 - 1.0) * jitterFactor;
        return Math.max(1L, Math.round(delay * (1.0 + jitter)));
    }
}