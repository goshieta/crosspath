package com.example.crosspath.registration;

/**
 * 登録処理のエラー種別。仕様: crosspath-registration-spec.md §3.8
 *
 * HTTP API のエラーコードやネットワーク障害を、UI 表示用に分類する。
 */
public enum RegistrationError {
    /** ネットワーク接続障害（IO例外、タイムアウトなど）。リトライ可能。 */
    NETWORK(true),
    /** 同一端末の二重登録・復旧不能。リトライ不可。 */
    CONFLICT(false),
    /** 登録が終了している（410 REGISTRATION_RETIRED）。リトライ不可。 */
    RETIRED(false),
    /** ID採番上限（503 ID_SPACE_EXHAUSTED）。リトライ不可。 */
    ID_SPACE_EXHAUSTED(false),
    /** リクエスト不正（400 INVALID_REQUEST）。リトライ不可。 */
    INVALID_REQUEST(false),
    /** 認証情報不正（401 INVALID_CREDENTIAL）。リトライ不可。 */
    UNAUTHORIZED(false),
    /** レート制限。リトライ可能。 */
    RATE_LIMITED(true),
    /** サーバー内部エラー。リトライ可能。 */
    SERVER(true),
    /** 予期しないエラー（分類不能）。リトライ可否は状況依存。 */
    UNKNOWN(false);

    private final boolean retryable;

    RegistrationError(boolean retryable) {
        this.retryable = retryable;
    }

    /**
     * このエラーがリトライ可能かどうか。
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * HTTP ステータスコードとエラーコード文字列から RegistrationError を判別する。
     *
     * @param httpStatus HTTP ステータスコード
     * @param errorCode  エラーコード文字列（null 可）
     * @return 対応する RegistrationError
     */
    public static RegistrationError fromHttp(int httpStatus, String errorCode) {
        if (errorCode != null) {
            switch (errorCode) {
                case "RATE_LIMITED":
                    return RATE_LIMITED;
                case "INTERNAL_ERROR":
                    return SERVER;
                case "REGISTRATION_BUSY":
                    return SERVER;
                case "REGISTRATION_CONFLICT":
                    return CONFLICT;
                case "REGISTRATION_RETIRED":
                    return RETIRED;
                case "ID_SPACE_EXHAUSTED":
                    return ID_SPACE_EXHAUSTED;
                case "INVALID_REQUEST":
                    return INVALID_REQUEST;
                case "INVALID_CREDENTIAL":
                    return UNAUTHORIZED;
            }
        }
        // HTTP ステータスによるフォールバック
        switch (httpStatus) {
            case 400: return INVALID_REQUEST;
            case 401: return UNAUTHORIZED;
            case 404:
            case 405: return INVALID_REQUEST;
            case 409: return CONFLICT;
            case 410: return RETIRED;
            case 429: return RATE_LIMITED;
            case 500:
            case 503: return httpStatus == 503 && "ID_SPACE_EXHAUSTED".equals(errorCode)
                    ? ID_SPACE_EXHAUSTED : SERVER;
            default: return UNKNOWN;
        }
    }
}