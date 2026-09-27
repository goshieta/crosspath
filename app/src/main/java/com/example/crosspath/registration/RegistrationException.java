package com.example.crosspath.registration;

/**
 * 登録処理でスローされる検査なし例外。
 * 仕様: crosspath-registration-spec.md §3.8
 *
 * エラー種別とトレースIDを保持する。secret は含めない。
 */
public class RegistrationException extends Exception {
    private final RegistrationError error;
    private final String traceId;
    private final boolean retryable;

    public RegistrationException(RegistrationError error, String message, String traceId) {
        super(message);
        this.error = error;
        this.traceId = traceId != null ? traceId : "";
        this.retryable = error.isRetryable();
    }

    public RegistrationException(RegistrationError error, String message, String traceId, Throwable cause) {
        super(message, cause);
        this.error = error;
        this.traceId = traceId != null ? traceId : "";
        this.retryable = error.isRetryable();
    }

    /**
     * エラー種別。
     */
    public RegistrationError getRegistrationError() {
        return error;
    }

    /**
     * サーバー応答の X-Trace-Id（無ければ空文字）。
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * リトライ可能かどうか。
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * UI 表示用の文字列リソースキーを返す。
     */
    public String getUserMessageKey() {
        switch (error) {
            case NETWORK:
                return "sc01_error_network";
            case CONFLICT:
                return "sc01_error_conflict";
            case RETIRED:
                return "sc01_error_retired";
            case ID_SPACE_EXHAUSTED:
                return "sc01_error_exhausted";
            case INVALID_REQUEST:
            case UNAUTHORIZED:
            case RATE_LIMITED:
            case SERVER:
            default:
                return "sc01_error_unexpected";
        }
    }

    @Override
    public String toString() {
        return "RegistrationException{error=" + error + ", traceId='" + traceId + "'}";
    }
}