package com.limelight.foghub;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

public final class FogHubSession {
    private static final Object LOCK = new Object();
    private static final String STORE = "AndroidKeyStore";
    private static final String AES_ALIAS = "foghub_session_aes";
    private static final String RSA_ALIAS = "foghub_session_rsa";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final Context context;

    public FogHubSession(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("foghub_session", Context.MODE_PRIVATE);
    }

    public String read() throws IOException {
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = preferences();
                String token = prefs.getString("token", null);
                if (token == null) {
                    return null;
                }

                // The stored format survives an upgrade from Lollipop to a newer OS.
                String wrappedKey = prefs.getString("wrapped_key", null);
                Key key = keyStore().getKey(wrappedKey == null ? AES_ALIAS : RSA_ALIAS, null);
                if (key == null) {
                    throw new UnrecoverableKeyException();
                }
                if (wrappedKey != null) {
                    key = new SecretKeySpec(rsa(Cipher.DECRYPT_MODE, key, decode(wrappedKey)), "AES");
                }

                byte[] iv = decode(prefs.getString("iv", ""));
                byte[] ciphertext = decode(token);
                if (iv.length == 0 || ciphertext.length <= TAG_BITS / 8) {
                    throw new IllegalArgumentException("Incomplete FogHub session");
                }
                Cipher cipher = Cipher.getInstance(AES_GCM);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (UnrecoverableKeyException | InvalidKeyException | InvalidAlgorithmParameterException |
                     BadPaddingException | IllegalBlockSizeException | IllegalArgumentException | ClassCastException e) {
                clear();
                return null;
            } catch (GeneralSecurityException | ProviderException | IllegalStateException e) {
                throw new IOException("Unable to read FogHub session", e);
            }
        }
    }

    public void save(String token) throws IOException {
        if (token == null || token.isEmpty()) {
            throw new IOException("Empty FogHub session token");
        }
        synchronized (LOCK) {
            try {
                KeyStore keys = keyStore();
                try {
                    encryptAndSave(keys, token);
                } catch (UnrecoverableKeyException | InvalidKeyException e) {
                    // A restored or invalidated key may still have an entry in the keystore.
                    removeKey(keys, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? AES_ALIAS : RSA_ALIAS);
                    encryptAndSave(keys, token);
                }
            } catch (GeneralSecurityException | ProviderException | IllegalStateException e) {
                throw new IOException("Unable to save FogHub session", e);
            }
        }
    }

    private void encryptAndSave(KeyStore keys, String token) throws GeneralSecurityException, IOException {
        Key key;
        String wrappedKey = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            key = aesKey(keys);
        } else {
            // Lollipop's keystore supports RSA but cannot hold AES keys.
            byte[] rawKey = new byte[32];
            new SecureRandom().nextBytes(rawKey);
            key = new SecretKeySpec(rawKey, "AES");
            wrappedKey = encode(rsa(Cipher.ENCRYPT_MODE, wrappingKey(keys), rawKey));
        }

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        SharedPreferences prefs = preferences();
        commit(prefs, prefs.edit().putString("token", encode(ciphertext))
                .putString("iv", encode(iv)).putString("wrapped_key", wrappedKey));
    }

    public void clear() throws IOException {
        synchronized (LOCK) {
            SharedPreferences prefs = preferences();
            commit(prefs, prefs.edit().clear());
            try {
                KeyStore keys = keyStore();
                removeKey(keys, AES_ALIAS);
                removeKey(keys, RSA_ALIAS);
            } catch (GeneralSecurityException | IOException | ProviderException | IllegalStateException ignored) {
                // Removing the ciphertext signs out even if the keystore is temporarily unavailable.
            }
        }
    }

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore keys = KeyStore.getInstance(STORE);
        keys.load(null);
        return keys;
    }

    private static void removeKey(KeyStore keys, String alias) throws KeyStoreException {
        if (keys.containsAlias(alias)) {
            keys.deleteEntry(alias);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static Key aesKey(KeyStore keys) throws GeneralSecurityException {
        Key existing = keys.getKey(AES_ALIAS, null);
        if (existing != null) {
            return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    @SuppressWarnings("deprecation")
    private PublicKey wrappingKey(KeyStore keys) throws GeneralSecurityException {
        Certificate certificate = keys.getCertificate(RSA_ALIAS);
        if (certificate != null && keys.getKey(RSA_ALIAS, null) != null) {
            return certificate.getPublicKey();
        }
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.YEAR, 30);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", STORE);
        generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(RSA_ALIAS)
                .setSubject(new X500Principal("CN=FogHub")).setSerialNumber(BigInteger.ONE)
                .setStartDate(new Date()).setEndDate(expires.getTime()).setKeySize(2048).build());
        return generator.generateKeyPair().getPublic();
    }

    private static byte[] rsa(int mode, Key key, byte[] value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(mode, key);
        return cipher.doFinal(value);
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }

    private static void commit(SharedPreferences prefs, SharedPreferences.Editor editor) throws IOException {
        Map<String, ?> previous = prefs.getAll();
        if (!editor.commit()) {
            // SharedPreferences updates its in-memory values even when the disk write fails.
            SharedPreferences.Editor rollback = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : previous.entrySet()) {
                if (entry.getValue() instanceof String value) {
                    rollback.putString(entry.getKey(), value);
                }
            }
            rollback.commit();
            throw new IOException("Unable to write FogHub session preferences");
        }
    }
}
