package com.limelight.foghub;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import com.limelight.R;
import com.limelight.foghub.FogHubApi.ApiException;
import com.limelight.foghub.FogHubApi.Endpoint;
import com.limelight.utils.UiHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import okhttp3.Call;

public final class FogHubActivity extends Activity {
    enum Page {
        LOGIN(R.string.foghub_sign_in_title), REGISTER(R.string.foghub_register),
        REGISTERED(R.string.foghub_registered), CHALLENGE(R.string.foghub_code),
        RECOVER(R.string.foghub_recover), LINK_SENT(R.string.foghub_recover),
        RESET(R.string.foghub_set_password), PASSWORD_CHANGED(R.string.foghub_set_password),
        PROFILE(R.string.foghub_account), EDIT(R.string.foghub_edit_profile),
        NOTIFICATIONS(R.string.foghub_notifications);

        final int title;

        Page(int title) {
            this.title = title;
        }
    }

    private interface Work<T> {
        T run() throws IOException;
    }

    interface Result<T> {
        void accept(T value) throws JSONException;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Call inFlight;
    private volatile int generation;
    private Endpoint activeEndpoint;
    private boolean busy;
    private FogHubSession session;
    private FogHubForms forms;
    private String token;
    private JSONObject user;
    private Runnable back;
    private LinearLayout fields;
    private ScrollView scroll;
    private TextView status;
    private View progress;
    private Page page;
    private Bundle pendingDraft;
    private final List<Consumer<Bundle>> draftFields = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiHelper.setLocale(this);
        setContentView(R.layout.activity_foghub);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        fields = findViewById(R.id.foghub_fields);
        scroll = findViewById(R.id.foghub_scroll);
        status = findViewById(R.id.foghub_status);
        progress = findViewById(R.id.foghub_progress);
        View content = findViewById(R.id.foghub_content);
        scroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                int margin = Math.max(dp(20), (right - left - dp(480)) / 2);
                content.setPadding(margin, dp(20), margin, dp(20));
            }
        });
        session = new FogHubSession(this);
        forms = new FogHubForms(this);
        pendingDraft = state == null ? null : state.getBundle("foghub.draft");
        openAccount();
    }

    private void openAccount() {
        page(Page.PROFILE, null);
        run(null, session::read, saved -> {
            token = saved;
            if (token == null) {
                Page target = savedPage();
                switch (target) {
                    case REGISTER -> forms.register();
                    case REGISTERED -> registered();
                    case RECOVER -> forms.recover();
                    case RESET -> forms.reset();
                    case LINK_SENT, PASSWORD_CHANGED -> forms.confirmation(target, null);
                    default -> login();
                }
                pendingDraft = null;
            } else {
                profile();
                refresh();
            }
        });
    }

    void login() {
        page(Page.LOGIN, null);
        EditText username = field(R.string.foghub_login_label,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "");
        rememberText("login", username);
        EditText password = field(R.string.foghub_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
            password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        }
        Runnable submit = () -> {
            String name = username.getText().toString().trim();
            String secret = password.getText().toString();
            if (required(username, name) && required(password, secret)) {
                signIn(Endpoint.LOGIN, pair("login", name, "password", secret));
            }
        };
        button(R.string.foghub_sign_in, submit);
        submitOnDone(password, submit);
        link(R.string.foghub_register, forms::register);
        link(R.string.foghub_recover, forms::recover);
    }

    void signIn(Endpoint endpoint, JSONObject body) {
        request(endpoint, body, result -> {
            if ("2fa_required".equals(result.getString("result"))) {
                challenge(result);
                return;
            }
            // Registration has already succeeded, even if saving the local session fails.
            if (endpoint == Endpoint.REGISTER) {
                registered();
            }
            String bearer = result.getString("token");
            JSONObject account = result.getJSONObject("user");
            validateUser(account);
            run(null, () -> {
                session.save(bearer);
                return bearer;
            }, saved -> {
                token = saved;
                user = account;
                profile();
            });
        });
    }

    private void registered() {
        page(Page.REGISTERED, this::login);
        button(R.string.foghub_return_login, this::login);
    }

    private void challenge(JSONObject response) throws JSONException {
        String temporary = response.getString("two_factor_token");
        long expires = SystemClock.elapsedRealtime() + 1000L * response.optInt("expires_in", 300);
        page(Page.CHALLENGE, this::login);
        note(response.optString("message", getString(R.string.foghub_code_sent)));
        EditText code = field(R.string.foghub_code, InputType.TYPE_CLASS_NUMBER, "");
        Runnable submit = () -> {
            if (SystemClock.elapsedRealtime() >= expires) {
                login();
                error(getString(R.string.foghub_code_expired));
                return;
            }
            String value = code.getText().toString().trim();
            if (required(code, value)) {
                signIn(Endpoint.LOGIN_2FA, pair("two_factor_token", temporary, "code", value));
            }
        };
        button(R.string.foghub_verify, submit);
        submitOnDone(code, submit);
    }

    private static JSONObject pair(String first, String value, String second, String other) {
        Map<String, String> body = new HashMap<>(2);
        body.put(first, value);
        body.put(second, other);
        return new JSONObject(body);
    }

    private boolean required(EditText field, String value) {
        if (!value.isEmpty()) {
            return true;
        }
        field.requestFocus();
        field.setError(getString(R.string.foghub_field_required));
        return false;
    }

    private void refresh() {
        request(Endpoint.ME, null, result -> showUser(result.getJSONObject("user")));
    }

    private static void validateUser(JSONObject value) throws JSONException {
        value.getLong("balance");
        if (!(value.get("login") instanceof String) || value.getString("login").isEmpty()) {
            throw new JSONException("Missing login");
        }
    }

    void showUser(JSONObject value) throws JSONException {
        validateUser(value);
        user = value;
        if (pendingDraft != null && value.getString("login").equals(pendingDraft.getString("owner"))) {
            if (restoring(Page.NOTIFICATIONS)) {
                forms.notifications();
                return;
            }
            if (restoring(Page.EDIT)) {
                forms.editProfile(user);
                pendingDraft = null;
                return;
            }
        }
        pendingDraft = null;
        profile();
    }

    void profile() {
        page(Page.PROFILE, null);
        if (user != null) {
            TextView name = new TextView(this, null, android.R.attr.textAppearanceLarge);
            name.setText(user.optString("login"));
            fields.addView(name);
            NumberFormat money = NumberFormat.getCurrencyInstance(getResources().getConfiguration().locale);
            money.setCurrency(Currency.getInstance("RUB"));
            money.setMinimumFractionDigits(2);
            money.setMaximumFractionDigits(2);
            note(getString(R.string.foghub_balance, money.format(BigDecimal.valueOf(user.optLong("balance"), 2))));
            button(R.string.foghub_edit_profile, () -> forms.editProfile(user));
            button(R.string.foghub_notifications, forms::notifications);
        }
        button(R.string.foghub_refresh, this::refresh);
        link(R.string.foghub_sign_out, () -> signOut(null));
    }

    private void signOut(String message) {
        pendingDraft = null;
        run(null, () -> {
            session.clear();
            return null;
        }, unused -> {
            token = null;
            user = null;
            login();
            if (message != null) {
                error(message);
            }
        });
    }

    void request(Endpoint endpoint, JSONObject body, Result<JSONObject> done) {
        String bearer = endpoint.authenticated ? token : null;
        int started = generation;
        run(endpoint, () -> {
            Call call = FogHubApi.get().call(endpoint, bearer, body);
            inFlight = call;
            try {
                if (started != generation) {
                    throw new IOException("Request cancelled");
                }
                return FogHubApi.parse(call.execute());
            } finally {
                inFlight = null;
            }
        }, done);
    }

    private <T> void run(Endpoint endpoint, Work<T> work, Result<T> done) {
        if (busy || isDestroyed() || isFinishing()) {
            return;
        }
        int started = generation;
        activeEndpoint = endpoint;
        status.setVisibility(View.GONE);
        setBusy(true);
        worker.execute(() -> {
            try {
                T result = work.run();
                runOnUiThread(() -> {
                    if (started != generation || isFinishing()) {
                        return;
                    }
                    setBusy(false);
                    try {
                        done.accept(result);
                    } catch (JSONException invalid) {
                        error(getString(R.string.foghub_invalid_response));
                    }
                });
            } catch (IOException failure) {
                runOnUiThread(() -> {
                    if (started != generation || isFinishing()) {
                        return;
                    }
                    setBusy(false);
                    if (failure instanceof ApiException) {
                        ApiException api = (ApiException) failure;
                        if (api.status == 401 && endpoint.authenticated) {
                            signOut(api.getMessage());
                            return;
                        }
                        if (api.status == 429 && endpoint == Endpoint.LOGIN_2FA) {
                            login();
                        }
                        boolean invalidResponse = api.status >= 200 && api.status < 300;
                        if (invalidResponse && endpoint == Endpoint.UPDATE_NOTIFICATIONS) {
                            // The write may have succeeded. Retry by reading the current settings.
                            page(Page.NOTIFICATIONS, this::profile);
                        }
                        error(invalidResponse ? getString(R.string.foghub_invalid_response) : api.getMessage());
                    } else {
                        error(getString(endpoint == null ? R.string.foghub_storage_error : R.string.foghub_request_failed));
                    }
                    if (page == Page.NOTIFICATIONS && fields.getChildCount() == 0) {
                        button(R.string.foghub_retry, forms::notifications);
                    } else if (endpoint == null && fields.getChildCount() == 0) {
                        button(R.string.foghub_retry, this::openAccount);
                    }
                });
            }
        });
    }

    private void setBusy(boolean active) {
        busy = active;
        progress.setVisibility(active ? View.VISIBLE : View.GONE);
        for (int i = 0; i < fields.getChildCount(); i++) {
            fields.getChildAt(i).setEnabled(!active);
        }
    }

    void page(Page next, Runnable parent) {
        View focused = getCurrentFocus();
        if (focused instanceof EditText) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(
                    focused.getWindowToken(), 0);
        }
        back = parent;
        page = next;
        setTitle(next.title);
        fields.removeAllViews();
        draftFields.clear();
        status.setVisibility(View.GONE);
        scroll.scrollTo(0, 0);
        findViewById(R.id.foghub_content).requestFocus();
    }

    EditText field(int label, int inputType, String initial) {
        TextView caption = new TextView(this);
        caption.setText(label);
        fields.addView(caption);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setSaveEnabled(false);
        input.setText(initial);
        fields.addView(input);
        return input;
    }

    void rememberText(String key, EditText field) {
        String initial = field.getText().toString();
        if (restoring(page)) {
            field.setText(pendingDraft.getString(key, initial));
        }
        draftFields.add(state -> {
            // Bound the activity's saved-state transaction even for an exceptionally large paste.
            if (field.length() <= 16384 && !initial.contentEquals(field.getText())) {
                state.putString(key, field.getText().toString());
            }
        });
    }

    CheckBox check(String key, int label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setChecked(restoring(page) ? pendingDraft.getBoolean(key, checked) : checked);
        draftFields.add(state -> {
            if (box.isChecked() != checked) {
                state.putBoolean(key, box.isChecked());
            }
        });
        box.setMinHeight(dp(48));
        fields.addView(box);
        return box;
    }

    private boolean restoring(Page target) {
        return pendingDraft != null && target.name().equals(pendingDraft.getString("page"));
    }

    private Page savedPage() {
        for (Page target : Page.values()) {
            if (restoring(target)) {
                return target;
            }
        }
        return Page.LOGIN;
    }

    void draftRestored() {
        pendingDraft = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingDraft != null) {
            out.putBundle("foghub.draft", pendingDraft);
            return;
        }
        Bundle draft = new Bundle();
        // A registration awaiting its reply may already have created the account.
        Page target = busy && activeEndpoint == Endpoint.REGISTER ? Page.LOGIN : page;
        draft.putString("page", target.name());
        if (user != null) {
            draft.putString("owner", user.optString("login"));
        }
        if (target == page) {
            for (Consumer<Bundle> save : draftFields) {
                save.accept(draft);
            }
        }
        out.putBundle("foghub.draft", draft);
    }

    void note(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, dp(4), 0, dp(12));
        fields.addView(view);
    }

    void button(int label, Runnable action) {
        addButton(new Button(this), label, action);
    }

    void link(int label, Runnable action) {
        addButton(new Button(this, null, android.R.attr.borderlessButtonStyle), label, action);
    }

    private void addButton(Button button, int label, Runnable action) {
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        fields.addView(button);
    }

    private void submitOnDone(EditText field, Runnable action) {
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, id, event) -> {
            if (id != EditorInfo.IME_ACTION_DONE || busy) {
                return false;
            }
            action.run();
            return true;
        });
    }

    void error(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void navigateBack() {
        pendingDraft = null;
        if (busy && activeEndpoint == Endpoint.NOTIFICATIONS) {
            cancelRequest();
            setBusy(false);
        }
        if (back == null || busy) {
            finish();
        } else {
            back.run();
        }
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            navigateBack();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void cancelRequest() {
        generation++;
        Call call = inFlight;
        if (call != null) {
            call.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        cancelRequest();
        worker.shutdownNow();
        super.onDestroy();
    }
}
