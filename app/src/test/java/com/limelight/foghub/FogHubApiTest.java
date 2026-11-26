package com.limelight.foghub;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Okio;

import static com.limelight.foghub.FogHubApi.Endpoint.*;
import static org.junit.Assert.*;

public class FogHubApiTest {
    private static final String NOTIFICATION_FLAGS = "{\"notify_favorite_pc\":true,\"notify_favorite_game\":false,"
            + "\"notify_save\":true,\"notify_cloud_save\":false,\"notify_session\":false,\"notify_pc_offline\":true}";

    private final FogHubApi api = FogHubApi.get();

    @Test
    public void requestsUseDocumentedRoutesAndIsolateBearerCredentials() {
        assertRequest(LOGIN, "POST", "login", null);
        assertRequest(LOGIN_2FA, "POST", "login-2fa", null);
        assertRequest(REGISTER, "POST", "register", null);
        assertRequest(RESTORE, "POST", "restore", null);
        assertRequest(CHANGE_PASSWORD, "POST", "change-password", null);
        assertRequest(ME, "GET", "me", "account-one");
        assertRequest(UPDATE, "PATCH", "update", "account-one");
        assertRequest(NOTIFICATIONS, "GET", "notifications", "account-two");
        assertRequest(UPDATE_NOTIFICATIONS, "PATCH", "notifications", "account-two");
        assertRequest(LOGIN, "POST", "login", null);
    }

    private void assertRequest(FogHubApi.Endpoint endpoint, String method, String path, String bearer) {
        JSONObject body = "GET".equals(method) ? null : new JSONObject();
        Request request = api.call(endpoint, bearer, body).request();
        assertEquals("https://foghub.ru/gamer/user/" + path, request.url().toString());
        assertEquals(method, request.method());
        assertEquals("application/json", request.header("Accept"));
        if (bearer == null) {
            assertNull(request.header("Authorization"));
            assertThrows(IllegalArgumentException.class, () -> api.call(endpoint, "unrelated-token", body));
        } else {
            assertEquals("Bearer " + bearer, request.header("Authorization"));
            assertThrows(IllegalArgumentException.class, () -> api.call(endpoint, null, body));
        }
        if (body == null) {
            assertNull(request.body());
        } else {
            assertEquals("application", request.body().contentType().type());
            assertEquals("json", request.body().contentType().subtype());
        }
    }

    @Test
    public void rejectsInvalidBearerAndUnexpectedBodiesBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> api.call(ME, " \t", null));
        assertThrows(IllegalArgumentException.class, () -> api.call(ME, "bad\nvalue", null));
        assertThrows(IllegalArgumentException.class, () -> api.call(ME, "token", new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> api.call(CHANGE_PASSWORD, null, null));
    }

    @Test
    public void patchPreservesExplicitNullFalseAndUtf8InSnapshotOfChangedFields() throws Exception {
        JSONObject changes = new JSONObject()
                .put("contact", "Привет")
                .put("region", JSONObject.NULL)
                .put("resume", false);
        Request request = api.call(UPDATE, "account", changes).request();
        changes.put("hourback", 12);
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        JSONObject sent = new JSONObject(buffer.readUtf8());
        assertEquals(3, sent.length());
        assertEquals("Привет", sent.getString("contact"));
        assertSame(JSONObject.NULL, sent.get("region"));
        assertEquals(Boolean.FALSE, sent.get("resume"));
    }

    private Request request(FogHubApi.Endpoint endpoint) {
        switch (endpoint) {
            case ME:
            case NOTIFICATIONS:
                return api.call(endpoint, "account", null).request();
            case UPDATE:
            case UPDATE_NOTIFICATIONS:
                return api.call(endpoint, "account", new JSONObject()).request();
            default:
                return api.call(endpoint, null, new JSONObject()).request();
        }
    }

    private JSONObject parse(FogHubApi.Endpoint endpoint, int status, String body) throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(bytes) {
            @Override public void close() {
                closed.set(true);
            }
        };
        try {
            return FogHubApi.parse(response(endpoint, status, input, bytes.length));
        } finally {
            assertTrue("Response body must close on success and error", closed.get());
        }
    }

    private Response response(FogHubApi.Endpoint endpoint, int status, InputStream input, int length) {
        return new Response.Builder()
                .request(request(endpoint))
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("")
                .body(ResponseBody.create(Okio.buffer(Okio.source(input)), MediaType.get("application/json"), length))
                .build();
    }

    @Test
    public void returnsDocumentedSuccessAndTwoFactorBodies() throws Exception {
        String auth = "{\"result\":\"success\",\"token\":\"account\",\"token_type\":\"Bearer\",\"user\":{\"login\":\"player\"}}";
        for (FogHubApi.Endpoint endpoint : new FogHubApi.Endpoint[]{LOGIN, LOGIN_2FA}) {
            assertEquals("account", parse(endpoint, 200, auth).getString("token"));
        }
        assertTrue(parse(REGISTER, 201, new JSONObject(auth).put("registered", true).toString()).getBoolean("registered"));
        String challenge = "{\"result\":\"2fa_required\",\"requires_2fa\":true,\"two_factor_token\":\"temporary\",\"expires_in\":300}";
        assertEquals("temporary", parse(LOGIN, 200, challenge).getString("two_factor_token"));
        assertEquals("player", parse(ME, 200, "{\"result\":\"success\",\"user\":{\"login\":\"player\"}}")
                .getJSONObject("user").getString("login"));
        assertEquals("OK", parse(RESTORE, 200, "{\"result\":\"success\",\"message\":\"OK\"}").getString("message"));
    }

    @Test
    public void notificationEndpointsAcceptCompleteBooleanFlagsWithExtraFields() throws Exception {
        JSONObject flags = new JSONObject(NOTIFICATION_FLAGS).put("future_setting", "daily");
        for (FogHubApi.Endpoint endpoint : new FogHubApi.Endpoint[]{NOTIFICATIONS, UPDATE_NOTIFICATIONS}) {
            JSONObject settings = parse(endpoint, 200, notificationResponse(flags)).getJSONObject("notifications");
            assertEquals(Boolean.TRUE, settings.get("notify_favorite_pc"));
            assertEquals(Boolean.FALSE, settings.get("notify_session"));
            assertEquals("daily", settings.getString("future_setting"));
        }
    }

    @Test
    public void notificationEndpointsRejectEmptyAndIncompleteFlags() throws Exception {
        assertInvalidNotificationResponse(new JSONObject());
        Iterator<?> keys = new JSONObject(NOTIFICATION_FLAGS).keys();
        while (keys.hasNext()) {
            JSONObject incomplete = new JSONObject(NOTIFICATION_FLAGS);
            incomplete.remove((String) keys.next());
            assertInvalidNotificationResponse(incomplete);
        }
    }

    @Test
    public void notificationEndpointsRejectNonBooleanFlags() throws Exception {
        for (Object value : new Object[]{"false", 0, JSONObject.NULL}) {
            assertInvalidNotificationResponse(new JSONObject(NOTIFICATION_FLAGS).put("notify_session", value));
        }
    }

    private void assertInvalidNotificationResponse(JSONObject flags) throws Exception {
        String body = notificationResponse(flags);
        for (FogHubApi.Endpoint endpoint : new FogHubApi.Endpoint[]{NOTIFICATIONS, UPDATE_NOTIFICATIONS}) {
            FogHubApi.ApiException error = assertThrows(endpoint + " accepted " + flags, FogHubApi.ApiException.class,
                    () -> parse(endpoint, 200, body));
            assertEquals(200, error.status);
        }
    }

    private String notificationResponse(JSONObject flags) throws Exception {
        return new JSONObject().put("result", "success").put("notifications", flags).toString();
    }

    @Test
    public void malformedSuccessCannotAuthorizeOrOverwriteAnAccount() {
        assertThrows(FogHubApi.ApiException.class, () -> parse(LOGIN, 200, "{\"result\":\"success\",\"user\":{}}"));
        assertThrows(FogHubApi.ApiException.class, () -> parse(LOGIN_2FA, 200, "{\"result\":\"success\",\"token\":null,\"user\":{}}"));
        assertThrows(FogHubApi.ApiException.class, () -> parse(LOGIN, 200, "{\"result\":\"2fa_required\"}"));
        assertThrows(FogHubApi.ApiException.class, () -> parse(ME, 200, "{\"result\":\"2fa_required\",\"two_factor_token\":\"x\"}"));
        assertThrows(FogHubApi.ApiException.class, () -> parse(UPDATE, 200, "{\"result\":\"success\"}"));
        assertThrows(FogHubApi.ApiException.class, () -> parse(NOTIFICATIONS, 200, "{\"result\":\"success\",\"notifications\":[]}"));
        assertThrows(FogHubApi.ApiException.class, () -> parse(CHANGE_PASSWORD, 200, "{\"message\":\"OK\"}"));
        assertThrows(FogHubApi.ApiException.class, () -> parse(RESTORE, 200, "{\"result\":\"success\"} broken"));
    }

    @Test
    public void usesHttpStatusAndExtractsYiiMessagesWithoutShowingRawBodies() {
        FogHubApi.ApiException error = assertThrows(FogHubApi.ApiException.class,
                () -> parse(ME, 401, "{\"name\":\"Unauthorized\",\"message\":\"Токен недействителен.\",\"status\":400}"));
        assertEquals(401, error.status);
        assertEquals("Токен недействителен.", error.getMessage());

        error = assertThrows(FogHubApi.ApiException.class, () -> parse(REGISTER, 422,
                "[{\"field\":\"login\",\"message\":\"Уже занят.\"},{\"field\":\"password\",\"message\":\"Слишком короткий.\"}]"));
        assertEquals(422, error.status);
        assertTrue(error.getMessage().contains("login"));
        assertTrue(error.getMessage().contains("Уже занят."));
        assertTrue(error.getMessage().contains("Слишком короткий."));

        for (String badBody : new String[]{"", "<html>internal detail</html>", "{\"token\":\"internal detail\"}"}) {
            error = assertThrows(FogHubApi.ApiException.class, () -> parse(LOGIN_2FA, 429, badBody));
            assertEquals(429, error.status);
            assertTrue(error.getMessage().contains("429"));
            assertFalse(error.getMessage().contains("internal detail"));
        }
    }

    @Test
    public void truncatedErrorBodyPreservesUnauthorizedStatus() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("Disconnected");
            }
        };
        FogHubApi.ApiException error = assertThrows(FogHubApi.ApiException.class,
                () -> FogHubApi.parse(response(ME, 401, broken, -1)));
        assertEquals(401, error.status);
    }

    @Test
    public void loginIsSentOnlyOnceEvenOnRetryAfterZero() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(503).addHeader("Retry-After", "0").build());
            server.enqueue(new MockResponse.Builder().code(200).build());
            try (Response response = loopbackApi(server).call(LOGIN, null, new JSONObject().put("login", "player")).execute()) {
                assertEquals(503, response.code());
            }
            assertEquals(1, server.getRequestCount());
            assertEquals("application/json", server.takeRequest(1, TimeUnit.SECONDS).getHeaders().get("Content-Type"));
        }
    }

    @Test
    public void redirectsCannotMoveAccountRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere")).build());
            server.enqueue(new MockResponse.Builder().code(200).build());
            try (Response response = loopbackApi(server).call(ME, "account", null).execute()) {
                assertEquals(302, response.code());
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    private FogHubApi loopbackApi(MockWebServer server) {
        return new FogHubApi(new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
        }));
    }
}
