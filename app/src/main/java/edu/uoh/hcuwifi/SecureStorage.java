package edu.uoh.hcuwifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Ultra-lightweight credential encryption backed by Android Keystore.
 * Uses AES-GCM (256-bit) with zero external dependencies to keep the app sub-50 KB.
 */
public final class SecureStorage {

    private static final String TAG = "SecureStorage";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "hcu_wifi_keystore_alias";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private static final String PREF_ENC_PASS = "enc_password";
    private static final String PREF_ENC_IV = "enc_iv";
    private static final String PREF_LEGACY_MIGRATED = "legacy_migrated";
    private static final String SECRETS_PREFS = "hcu_secrets";

    private SecureStorage() {}

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            if (entry != null) return entry.getSecretKey();
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true);

        keyGenerator.init(builder.build());
        return keyGenerator.generateKey();
    }

    public static synchronized void savePassword(Context context, String password) {
        if (context == null) return;
        SharedPreferences prefs = HcuAuthHelper.getPrefs(context);

        if (password == null || password.isEmpty()) {
            prefs.edit().remove(PREF_ENC_PASS).remove(PREF_ENC_IV).apply();
            return;
        }

        try {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));

            String encPassStr = Base64.encodeToString(cipherText, Base64.NO_WRAP);
            String encIvStr = Base64.encodeToString(iv, Base64.NO_WRAP);

            prefs.edit()
                    .putString(PREF_ENC_PASS, encPassStr)
                    .putString(PREF_ENC_IV, encIvStr)
                    .remove(HcuAuthHelper.KEY_PASSWORD) // purge any plaintext legacy password
                    .apply();

        } catch (Exception e) {
            // Never leave an older password in place after a failed save.
            prefs.edit().remove(PREF_ENC_PASS).remove(PREF_ENC_IV)
                    .remove(HcuAuthHelper.KEY_PASSWORD).apply();
            Log.e(TAG, "Failed to encrypt password; cleared stale stored value", e);
        }
    }

    public static synchronized String getPassword(Context context) {
        if (context == null) return "";
        SharedPreferences prefs = HcuAuthHelper.getPrefs(context);

        // One-time purge of unencrypted legacy password from early builds
        if (!prefs.getBoolean(PREF_LEGACY_MIGRATED, false)) {
            prefs.edit().remove(HcuAuthHelper.KEY_PASSWORD).putBoolean(PREF_LEGACY_MIGRATED, true).apply();
        }

        String encPass = prefs.getString(PREF_ENC_PASS, null);
        String encIv = prefs.getString(PREF_ENC_IV, null);

        if (encPass == null || encIv == null) {
            return "";
        }

        try {
            SecretKey secretKey = getOrCreateKey();
            byte[] iv = Base64.decode(encIv, Base64.NO_WRAP);
            byte[] cipherText = Base64.decode(encPass, Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            Log.e(TAG, "Failed to decrypt password: " + e.getMessage());
            return "";
        }
    }

    /**
     * Stores an arbitrary small string (used for the licence record) AES-GCM encrypted under the same
     * Keystore key, in its own preferences file so it never interferes with the credential entries.
     * Returns false when the Keystore is unavailable; the caller decides on a fallback.
     */
    public static synchronized boolean putSecret(Context context, String name, String value) {
        if (context == null || name == null || value == null) return false;
        try {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            context.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(name + "_c", Base64.encodeToString(cipherText, Base64.NO_WRAP))
                    .putString(name + "_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not encrypt secret '" + name + "': " + e.getMessage());
            return false;
        }
    }

    /** Returns null when nothing is stored or it cannot be decrypted (for example the key was lost). */
    public static synchronized String getSecret(Context context, String name) {
        if (context == null || name == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE);
        String c = prefs.getString(name + "_c", null);
        String iv = prefs.getString(name + "_iv", null);
        if (c == null || iv == null) return null;
        try {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(c, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Could not decrypt secret '" + name + "': " + e.getMessage());
            return null;
        }
    }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        HcuAuthHelper.getPrefs(context).edit()
                .remove(PREF_ENC_PASS)
                .remove(PREF_ENC_IV)
                .remove(HcuAuthHelper.KEY_PASSWORD)
                .apply();
    }
}
