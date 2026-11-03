package com.itzprovenom.lanlinkagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStore {
    private static final String PREFS = "lanlink_secure";
    private static final String VALUE = "agent_token";
    private static final String CONTROLLER_VALUE = "controller_token";
    private static final String ALIAS = "lanlink_agent_aes";

    private SecureStore() {}

    static void write(Context context, String token) {
        writeValue(context, VALUE, token);
    }

    static String read(Context context) {
        return readValue(context, VALUE);
    }

    static void writeControllerToken(Context context, String token) {
        writeValue(context, CONTROLLER_VALUE, token);
    }

    static String readControllerToken(Context context) {
        return readValue(context, CONTROLLER_VALUE);
    }

    private static void writeValue(Context context, String name, String token) {
        try {
            SecretKey key = getKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
            buffer.putInt(iv.length);
            buffer.put(iv);
            buffer.put(encrypted);
            prefs(context).edit().putString(name, Base64.encodeToString(buffer.array(), Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt bridge token", e);
        }
    }

    private static String readValue(Context context, String name) {
        try {
            String encoded = prefs(context).getString(name, "");
            if (encoded == null || encoded.isEmpty()) return "";
            ByteBuffer buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            int ivLength = buffer.getInt();
            if (ivLength < 12 || ivLength > 16 || buffer.remaining() <= ivLength) return "";
            byte[] iv = new byte[ivLength];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
