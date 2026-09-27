package com.example.crosspath.registration;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HttpURLConnection を使った RegistrationHttp の実装。仕様: crosspath-registration-spec.md §3.5
 *
 * コンストラクタでベースURLを受け取り、POST /v1/registrations および GET /v1/registrations/me を実行する。
 * secret はログ・例外メッセージ・URLに含めない。
 */
public class HttpRegistrationApi implements RegistrationHttp {
    public static final String DEFAULT_BASE_URL =
            "https://id-server-1084526017972.asia-northeast1.run.app";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final String baseUrl;

    public HttpRegistrationApi() {
        this(DEFAULT_BASE_URL);
    }

    public HttpRegistrationApi(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public RegistrationResponse register(String secret, String requestId) throws Exception {
        URL url = new URL(baseUrl + "/v1/registrations");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + secret);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String body = RegistrationJson.requestBody(requestId);
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = body.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public RegistrationResponse fetchMe(String secret) throws Exception {
        URL url = new URL(baseUrl + "/v1/registrations/me");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + secret);
            conn.setDoOutput(false);

            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * HttpURLConnection からステータスコード・ボディ・ヘッダを読み取り RegistrationResponse を生成する。
     * エラー応答のボディは getErrorStream() から読む。
     */
    private RegistrationResponse readResponse(HttpURLConnection conn) throws Exception {
        int httpStatus = conn.getResponseCode();
        String traceId = conn.getHeaderField("X-Trace-Id");
        String retryAfterHeader = conn.getHeaderField("Retry-After");
        int retryAfterSeconds = 0;
        if (retryAfterHeader != null) {
            try {
                retryAfterSeconds = Integer.parseInt(retryAfterHeader.trim());
            } catch (NumberFormatException ignored) {
                // パース不能なら無視
            }
        }

        String body;
        if (httpStatus >= 400) {
            body = readStream(conn.getErrorStream());
        } else {
            body = readStream(conn.getInputStream());
        }

        return RegistrationResponse.from(httpStatus, body, traceId, retryAfterSeconds);
    }

    /**
     * InputStream から文字列を読み取る。null の場合は null を返す。
     */
    private String readStream(InputStream stream) throws Exception {
        if (stream == null) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}