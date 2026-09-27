package com.example.crosspath.registration;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * SharedPreferences + AndroidKeyStore を使った RegistrationStore の実装。
 * 仕様: crosspath-registration-spec.md §3.6 PrefsRegistrationStore
 *
 * secret は AndroidKeyStore の AES/GCM 鍵で暗号化して保存する。
 * 暗号化形式: Base64(iv || ciphertext)。復号失敗時は null を返し Log.w する。
 * 平文保存へのフォールバックは行わない。
 */
public class PrefsRegistrationStore implements RegistrationStore {
    private static final String TAG = "PrefsRegistrationStore";
    private static final String PREFS_NAME = "user_profile";
    private static final String KEY_ALIAS = "crosspath_registration_secret";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12;   // bytes

    private static final String KEY_SECRET = "registration_secret_encrypted";
    private static final String KEY_REQUEST_ID = "registration_request_id";
    private static final String KEY_USER_ID = "personal_id";
    private static final String KEY_CREATED_AT = "registration_created_at";

    private final SharedPreferences prefs;

    public PrefsRegistrationStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureKey();
    }

    @Override
    public String secret() {
        String encrypted = prefs.getString(KEY_SECRET, null);
        if (encrypted == null) return null;
        try {
            return decrypt(encrypted);
        } catch (Exception e) {
            Log.w(TAG, "secret 復号に失敗しました（再登録が必要です）", e);
            return null;
        }
    }

    @Override
    public void saveSecret(String s) {
        try {
            String encrypted = encrypt(s);
            prefs.edit().putString(KEY_SECRET, encrypted).apply();
        } catch (Exception e) {
            Log.e(TAG, "secret 暗号化に失敗しました", e);
            // 暗号化失敗時は保存しない（安全のため）
        }
    }

    @Override
    public String requestId() {
        return prefs.getString(KEY_REQUEST_ID, null);
    }

    @Override
    public void saveRequestId(String id) {
        prefs.edit().putString(KEY_REQUEST_ID, id).apply();
    }

    @Override
    public int userId() {
        return prefs.getInt(KEY_USER_ID, 0);
    }

    @Override
    public void saveUser(int userId, String createdAt) {
        prefs.edit()
                .putInt(KEY_USER_ID, userId)
                .putString(KEY_CREATED_AT, createdAt)
                .apply();
    }

    @Override
    public String createdAt() {
        return prefs.getString(KEY_CREATED_AT, null);
    }

    /**
     * AndroidKeyStore に AES/GCM 鍵が存在することを確認する。
     * 無ければ新規生成する。
     */
    private void ensureKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return;
            }
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            keyGenerator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            keyGenerator.generateKey();
        } catch (Exception e) {
            Log.w(TAG, "AndroidKeyStore 鍵の生成に失敗しました", e);
        }
    }

    /**
     * AndroidKeyStore の AES/GCM 鍵を取得する。
     */
    private SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    /**
     * 平文を AES/GCM で暗号化し、Base64(iv || ciphertext) を返す。
     */
    private String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP);
    }

    /**
     * Base64(iv || ciphertext) を復号して平文を返す。
     */
    private String decrypt(String encrypted) throws Exception {
        byte[] combined = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP);
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    }
}