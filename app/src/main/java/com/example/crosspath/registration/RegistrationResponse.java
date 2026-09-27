package com.example.crosspath.registration;

/**
 * サーバー登録APIの応答を表す値オブジェクト。仕様: crosspath-registration-spec.md §3.3
 *
 * HTTP ステータスコードとレスポンスボディから生成する。
 * JSON パース失敗時も例外を投げず、フィールドは null/0 のまま残る。
 */
public class RegistrationResponse {
    private final int httpStatus;
    private final int userId;
    private final String createdAt;
    private final String requestId;
    private final String errorCode;
    private final boolean errorRetryable;
    private final int retryAfterSeconds;
    private final String traceId;

    private RegistrationResponse(int httpStatus, int userId, String createdAt,
                                 String requestId, String errorCode,
                                 boolean errorRetryable, int retryAfterSeconds,
                                 String traceId) {
        this.httpStatus = httpStatus;
        this.userId = userId;
        this.createdAt = createdAt;
        this.requestId = requestId;
        this.errorCode = errorCode;
        this.errorRetryable = errorRetryable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.traceId = traceId;
    }

    /**
     * 成功応答（200/201）かどうか。
     */
    public boolean isSuccess() {
        return httpStatus == 200 || httpStatus == 201;
    }

    public int getHttpStatus() { return httpStatus; }
    public int getUserId() { return userId; }
    public String getCreatedAt() { return createdAt; }
    public String getRequestId() { return requestId; }
    public String getErrorCode() { return errorCode; }
    public boolean getErrorRetryable() { return errorRetryable; }
    public int getRetryAfterSeconds() { return retryAfterSeconds; }
    public String getTraceId() { return traceId; }

    /**
     * HTTP 応答から RegistrationResponse を生成するファクトリメソッド。
     * JSON パースに失敗しても例外を投げず、errorCode=null のまま返す。
     *
     * @param httpStatus       HTTP ステータスコード
     * @param body            レスポンスボディ（null 可）
     * @param traceId         X-Trace-Id ヘッダ値（null 可）
     * @param retryAfterSeconds Retry-After ヘッダ値（無ければ 0）
     * @return 生成された RegistrationResponse
     */
    public static RegistrationResponse from(int httpStatus, String body,
                                             String traceId, int retryAfterSeconds) {
        if (body == null) {
            return new RegistrationResponse(httpStatus, 0, null, null,
                    null, false, retryAfterSeconds, traceId);
        }

        Integer userId = RegistrationJson.integer(body, "user_id");
        String createdAt = RegistrationJson.string(body, "created_at");
        String requestId = RegistrationJson.string(body, "request_id");
        String errorCode = RegistrationJson.string(body, "error_code");
        Boolean errorRetryable = RegistrationJson.bool(body, "error_retryable");

        // エラー応答のネスト構造: {"error":{"code":"...","retryable":false}}
        if (errorCode == null) {
            errorCode = RegistrationJson.nestedString(body, "error", "code");
        }
        if (errorRetryable == null) {
            Boolean nestedRetryable = RegistrationJson.bool(extractNestedErrorObject(body), "retryable");
            if (nestedRetryable != null) {
                errorRetryable = nestedRetryable;
            }
        }

        return new RegistrationResponse(
                httpStatus,
                userId != null ? userId : 0,
                createdAt,
                requestId,
                errorCode,
                errorRetryable != null ? errorRetryable : false,
                retryAfterSeconds,
                traceId
        );
    }

    /**
     * ネストエラーオブジェクトを抽出する簡易ヘルパー。
     * {"error":{"code":"X","retryable":false}} から {"code":"X","retryable":false} を取り出す。
     */
    private static String extractNestedErrorObject(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"error\"");
        if (idx < 0) return null;
        int braceStart = json.indexOf('{', idx);
        if (braceStart < 0) return null;
        // 外側の error の値のブレース開始位置
        int valueBrace = braceStart;
        // さらにその内側
        int depth = 1;
        int pos = valueBrace + 1;
        while (depth > 0 && pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            pos++;
        }
        if (depth != 0) return null;
        return json.substring(valueBrace, pos);
    }
}