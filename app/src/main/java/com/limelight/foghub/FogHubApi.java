package com.limelight.foghub;

import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

public final class FogHubApi {
    private static final String BASE_URL = "https://foghub.ru/gamer/user/";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    enum NotificationFlag {
        FAVORITE_PC("notify_favorite_pc", R.string.foghub_notify_favorite_pc),
        FAVORITE_GAME("notify_favorite_game", R.string.foghub_notify_favorite_game),
        SAVE("notify_save", R.string.foghub_notify_save),
        CLOUD_SAVE("notify_cloud_save", R.string.foghub_notify_cloud_save),
        SESSION("notify_session", R.string.foghub_notify_session),
        PC_OFFLINE("notify_pc_offline", R.string.foghub_notify_pc_offline);

        final String key;
        final int label;

        NotificationFlag(String key, int label) {
            this.key = key;
            this.label = label;
        }

        private static boolean accepts(JSONObject flags) {
            if (flags == null) {
                return false;
            }
            for (NotificationFlag flag : values()) {
                if (!(flags.opt(flag.key) instanceof Boolean)) {
                    return false;
                }
            }
            return true;
        }
    }

    public enum Endpoint {
        LOGIN("POST", "login", false),
        LOGIN_2FA("POST", "login-2fa", false),
        REGISTER("POST", "register", false),
        RESTORE("POST", "restore", false),
        CHANGE_PASSWORD("POST", "change-password", false),
        ME("GET", "me", true),
        UPDATE("PATCH", "update", true),
        NOTIFICATIONS("GET", "notifications", true),
        UPDATE_NOTIFICATIONS("PATCH", "notifications", true);

        private final String method;
        private final String path;
        final boolean authenticated;

        Endpoint(String method, String path, boolean authenticated) {
            this.method = method;
            this.path = path;
            this.authenticated = authenticated;
        }

        private boolean accepts(JSONObject data) {
            Object result = data.opt("result");
            if ("2fa_required".equals(result)) {
                return this == LOGIN && Boolean.TRUE.equals(data.opt("requires_2fa"))
                        && validToken(data.opt("two_factor_token"));
            }
            if (!"success".equals(result)) {
                return false;
            }
            switch (this) {
                case LOGIN:
                case LOGIN_2FA:
                case REGISTER:
                    return validToken(data.opt("token")) && data.optJSONObject("user") != null
                            && "Bearer".equalsIgnoreCase(data.optString("token_type", "Bearer"));
                case ME:
                case UPDATE:
                    return data.optJSONObject("user") != null;
                case NOTIFICATIONS:
                case UPDATE_NOTIFICATIONS:
                    return NotificationFlag.accepts(data.optJSONObject("notifications"));
                case RESTORE:
                case CHANGE_PASSWORD:
                    return true;
                default:
                    throw new AssertionError(this);
            }
        }
    }

    public static final class ApiException extends IOException {
        public final int status;

        private ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class Holder {
        private static final FogHubApi INSTANCE = new FogHubApi(new OkHttpClient.Builder());
    }

    private final OkHttpClient client;

    public static FogHubApi get() {
        return Holder.INSTANCE;
    }

    FogHubApi(OkHttpClient.Builder builder) {
        client = builder.followRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public Call call(Endpoint endpoint, String bearer, JSONObject body) {
        if (endpoint.authenticated ? !validToken(bearer) : bearer != null) {
            throw new IllegalArgumentException("Unexpected or missing FogHub authorization");
        }
        if ("GET".equals(endpoint.method) != (body == null)) {
            throw new IllegalArgumentException("Unexpected or missing FogHub request body");
        }
        Request.Builder request = new Request.Builder()
                .url(BASE_URL + endpoint.path)
                .tag(Endpoint.class, endpoint)
                .header("Accept", JSON.toString())
                .method(endpoint.method, body == null ? null : new JsonBody(body));
        if (endpoint.authenticated) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.newCall(request.build());
    }

    private static boolean validToken(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            return false;
        }
        String token = (String) value;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) < '!' || token.charAt(i) > '~') {
                return false;
            }
        }
        return true;
    }

    private static final class JsonBody extends RequestBody {
        private final byte[] bytes;

        JsonBody(JSONObject body) {
            bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override public MediaType contentType() {
            return JSON;
        }

        @Override public long contentLength() {
            return bytes.length;
        }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            sink.write(bytes);
        }

        @Override public boolean isOneShot() {
            // OkHttp may resend a replayable body after HTTP 503 with Retry-After: 0.
            return true;
        }
    }

    /** Reads and closes the response from an account call. */
    public static JSONObject parse(Response response) throws IOException {
        try (Response ignored = response) {
            int status = response.code();
            Object data = null;
            try {
                data = readJson(response.body());
            } catch (IOException e) {
                // An HTTP error, especially a 401, is authoritative even if its body was truncated.
                if (response.isSuccessful()) {
                    throw e;
                }
            }
            if (!response.isSuccessful()) {
                String message = errorMessage(data);
                throw new ApiException(status, message.isEmpty() ? "FogHub (HTTP " + status + ")" : message);
            }
            Endpoint endpoint = response.request().tag(Endpoint.class);
            if (endpoint != null && data instanceof JSONObject && endpoint.accepts((JSONObject) data)) {
                return (JSONObject) data;
            }
            throw new ApiException(status, "Invalid response from FogHub (HTTP " + status + ")");
        }
    }

    private static Object readJson(ResponseBody body) throws IOException {
        if (body.contentLength() > MAX_RESPONSE_BYTES || body.source().request(MAX_RESPONSE_BYTES + 1L)) {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(body.string());
            Object data = tokener.nextValue();
            return tokener.nextClean() == 0 ? data : null;
        } catch (JSONException ignored) {
            // Parser exception messages may contain the response, including an account token.
            return null;
        }
    }

    private static String errorMessage(Object data) {
        if (data instanceof JSONObject) {
            return text(((JSONObject) data).opt("message"));
        }
        StringBuilder errors = new StringBuilder();
        if (data instanceof JSONArray) {
            JSONArray fields = (JSONArray) data;
            for (int i = 0; i < fields.length(); i++) {
                JSONObject field = fields.optJSONObject(i);
                String message = field == null ? "" : text(field.opt("message"));
                if (message.isEmpty()) {
                    continue;
                }
                if (errors.length() > 0) {
                    errors.append('\n');
                }
                String name = text(field.opt("field"));
                if (!name.isEmpty()) {
                    errors.append(name).append(": ");
                }
                errors.append(message);
            }
        }
        return errors.toString();
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }
}
