package gg.projects.mobile.afk.store;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * PHONE-AFK: login commands sealed with AES-256-GCM under a key in the Android Keystore (never leaves it; no user
 * authentication, so the {@code :afk} process can open them with the screen locked). Sealed text: {@code k1:} + Base64
 * of the 12-byte IV and the ciphertext with its tag, over a JSON array of the commands.
 */
public final class KeystoreSealer implements AfkSealer {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "projects_afk_login_v1";
    private static final String PREFIX = "k1:";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    @Override
    public String seal(List<String> commands) throws GeneralSecurityException, java.io.IOException {
        if (commands == null || commands.isEmpty()) return "";
        JsonArray array = new JsonArray();
        for (String c : commands) array.add(c);
        byte[] plain = array.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(plain);
        java.util.Arrays.fill(plain, (byte) 0);
        byte[] out = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(sealed, 0, out, iv.length, sealed.length);
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
    }

    @Override
    public List<String> unseal(String sealed) throws GeneralSecurityException, java.io.IOException {
        List<String> out = new ArrayList<>();
        if (sealed == null || sealed.isEmpty()) return out;
        if (!sealed.startsWith(PREFIX)) throw new GeneralSecurityException("unknown seal");
        byte[] all = Base64.decode(sealed.substring(PREFIX.length()), Base64.NO_WRAP);
        if (all.length <= IV_BYTES) throw new GeneralSecurityException("short seal");
        SecretKey key = key(false);
        if (key == null) throw new GeneralSecurityException("no key");
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
        byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        JsonElement root = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8));
        java.util.Arrays.fill(plain, (byte) 0);
        if (root.isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray()) {
                if (e.isJsonPrimitive()) out.add(e.getAsString());
            }
        }
        return out;
    }

    @Override
    public void forget() {
        try {
            KeyStore store = KeyStore.getInstance(STORE);
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        } catch (Exception ignored) {
            // Nothing sealed can be read without it anyway.
        }
    }

    private static synchronized SecretKey key(boolean create) throws GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        if (!create) return null;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
