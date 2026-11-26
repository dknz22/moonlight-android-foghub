package com.limelight.foghub;

import android.net.Uri;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.foghub.FogHubActivity.Page;
import com.limelight.utils.HelpLauncher;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class FogHubForms {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int SECRET = TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private final FogHubActivity activity;

    FogHubForms(FogHubActivity activity) {
        this.activity = activity;
    }

    void register() {
        activity.page(Page.REGISTER, activity::login);
        Form form = new Form(null);
        form.login();
        form.password(R.string.foghub_password);
        activity.link(R.string.foghub_terms, () ->
                HelpLauncher.launchUrl(activity, "https://foghub.ru/site/reg#user-align"));
        CheckBox rules = activity.check("align", R.string.foghub_agree, false);
        rules.setOnCheckedChangeListener((button, checked) -> button.setError(null));
        form.bind("align", rules, () -> {
            if (!rules.isChecked()) {
                throw invalid(R.string.foghub_agree_required);
            }
            return true;
        });
        activity.note(activity.getString(R.string.foghub_optional_details));
        form.text("referral", R.string.foghub_referral, Integer.MAX_VALUE);
        form.text("contact", R.string.foghub_contact, Integer.MAX_VALUE);
        form.region();
        form.check("only_fh", R.string.foghub_only_fh, true);
        form.submit(R.string.foghub_register, body -> activity.signIn(FogHubApi.Endpoint.REGISTER, body));
    }

    void recover() {
        activity.page(Page.RECOVER, activity::login);
        activity.note(activity.getString(R.string.foghub_recovery_help));
        Form form = new Form(null);
        form.login();
        form.submit(R.string.foghub_send_link, body -> activity.request(FogHubApi.Endpoint.RESTORE, body,
                result -> confirmation(Page.LINK_SENT, result)));
        activity.link(R.string.foghub_enter_token, this::reset);
    }

    void reset() {
        activity.page(Page.RESET, this::recover);
        Form form = new Form(null);
        form.input("token", R.string.foghub_token_or_link, SECRET, value -> {
            String token = required(value.trim());
            Uri uri = Uri.parse(token);
            if ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) {
                token = uri.isHierarchical() ? uri.getQueryParameter("token") : null;
                if (token == null || token.trim().isEmpty()) {
                    throw invalid(R.string.foghub_link_missing_token);
                }
            }
            return token;
        });
        form.password(R.string.foghub_new_password);
        form.submit(R.string.foghub_set_password, body -> activity.request(FogHubApi.Endpoint.CHANGE_PASSWORD, body,
                result -> confirmation(Page.PASSWORD_CHANGED, result)));
    }

    void confirmation(Page page, JSONObject result) {
        activity.page(page, activity::login);
        boolean linkSent = page == Page.LINK_SENT;
        int fallback = linkSent ? R.string.foghub_link_sent : R.string.foghub_password_changed;
        Object message = result == null ? null : result.opt("message");
        activity.note(message instanceof String && !((String) message).isEmpty() ?
                (String) message : activity.getString(fallback));
        if (linkSent) {
            activity.button(R.string.foghub_enter_token, this::reset);
        } else {
            activity.button(R.string.foghub_return_login, activity::login);
        }
    }

    void editProfile(JSONObject user) {
        activity.page(Page.EDIT, activity::profile);
        Form form = new Form(user);
        form.text("contact", R.string.foghub_contact, 2000);
        form.region();
        form.check("resume", R.string.foghub_resume);
        form.check("only_fh", R.string.foghub_only_fh);
        form.check("with_2fa", R.string.foghub_with_2fa);
        form.text("background_block1", R.string.foghub_background_left, 120);
        form.text("background_block2", R.string.foghub_background_right, 120);
        form.number("hourback", R.string.foghub_cashback, false, 0, 20);
        form.number("hourback_regular", R.string.foghub_cashback_regular, true, 0, 30);
        form.number("interval", R.string.foghub_interval, false, Long.MIN_VALUE, Long.MAX_VALUE);
        form.password(R.string.foghub_optional_password);
        form.submit(R.string.foghub_save_changes, body -> {
            if (body.length() == 0) {
                activity.profile();
                return;
            }
            activity.request(FogHubApi.Endpoint.UPDATE, body, result -> activity.showUser(result.getJSONObject("user")));
        });
    }

    void notifications() {
        activity.page(Page.NOTIFICATIONS, activity::profile);
        activity.request(FogHubApi.Endpoint.NOTIFICATIONS, null, result -> {
            Form form = new Form(result.getJSONObject("notifications"));
            for (FogHubApi.NotificationFlag flag : FogHubApi.NotificationFlag.values()) {
                form.check(flag.key, flag.label);
            }
            form.submit(R.string.foghub_save_changes, body -> {
                if (body.length() == 0) {
                    activity.profile();
                    return;
                }
                activity.request(FogHubApi.Endpoint.UPDATE_NOTIFICATIONS, body, updated -> activity.profile());
            });
            activity.draftRestored();
        });
    }

    private IllegalArgumentException invalid(int message, Object... args) {
        return new IllegalArgumentException(activity.getString(message, args));
    }

    private String required(String value) {
        if (value.isEmpty()) {
            throw invalid(R.string.foghub_field_required);
        }
        return value;
    }

    private interface Field {
        boolean collect(Map<String, Object> body);
    }

    // Java null omits a field. JSON null clears a previously populated field.
    static Object submittedValue(String previous, String text, Function<String, Object> parse) {
        if (text.equals(previous)) {
            return null;
        }
        Object value = parse.apply(text);
        if (value == JSONObject.NULL) {
            return previous == null || previous.isEmpty() ? null : value;
        }
        return value.toString().equals(previous) ? null : value;
    }

    private final class Form {
        private final JSONObject original;
        private final List<Field> fields = new ArrayList<>();

        Form(JSONObject original) {
            this.original = original;
        }

        void bind(String key, TextView view, Supplier<Object> read) {
            fields.add(body -> {
                try {
                    Object value = read.get();
                    if (value != null) {
                        body.put(key, value);
                    }
                    return true;
                } catch (IllegalArgumentException invalid) {
                    view.setError(invalid.getMessage());
                    if (!view.requestFocusFromTouch()) {
                        activity.error(invalid.getMessage());
                    }
                    return false;
                }
            });
        }

        EditText input(String key, int label, int type, Function<String, Object> parse) {
            String initial = original == null || type == SECRET || original.isNull(key) ? "" : original.optString(key);
            EditText field = activity.field(label, type, initial);
            if (type != SECRET) {
                activity.rememberText(key, field);
            }
            String previous = original == null ? null : initial;
            bind(key, field, () -> submittedValue(previous, field.getText().toString(), parse));
            return field;
        }

        void login() {
            input("login", R.string.foghub_login_label, TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    text -> required(text.trim()));
        }

        void password(int label) {
            input("password", label, SECRET, text -> {
                int length = text.codePointCount(0, text.length());
                if (length < 8 || length > 20) {
                    throw invalid(R.string.foghub_password_length);
                }
                return text;
            });
        }

        void text(String key, int label, int limit) {
            input(key, label, TEXT, text -> {
                if (text.codePointCount(0, text.length()) > limit) {
                    throw invalid(R.string.foghub_text_limit, limit);
                }
                return text.isEmpty() ? JSONObject.NULL : text;
            });
        }

        void region() {
            number("region", R.string.foghub_region, true, Long.MIN_VALUE, Long.MAX_VALUE);
        }

        void number(String key, int label, boolean nullable, long min, long max) {
            EditText field = input(key, label, TEXT, text -> {
                String digits = text.trim();
                if (nullable && digits.isEmpty()) {
                    return JSONObject.NULL;
                }
                long number;
                try {
                    number = Long.parseLong(required(digits));
                } catch (NumberFormatException e) {
                    throw invalid(R.string.foghub_integer_required);
                }
                if (number < min || number > max) {
                    throw invalid(R.string.foghub_number_range, min, max);
                }
                return number;
            });
            // A numeric key listener silently strips characters from pasted values (e.g. "1.5" becomes "15").
            field.setRawInputType(InputType.TYPE_CLASS_NUMBER | (min < 0 ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        }

        void check(String key, int label) {
            check(key, label, original.optBoolean(key));
        }

        void check(String key, int label, boolean initial) {
            CheckBox field = activity.check(key, label, initial);
            bind(key, field, () -> original != null && initial == field.isChecked() ? null : field.isChecked());
        }

        void submit(int label, Consumer<JSONObject> send) {
            activity.button(label, () -> {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Field field : fields) {
                    if (!field.collect(values)) {
                        return;
                    }
                }
                send.accept(new JSONObject(values));
            });
        }
    }
}
