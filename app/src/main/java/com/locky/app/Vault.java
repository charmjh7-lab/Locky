package com.locky.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import android.util.AtomicFile;

final class Vault {
    private final AtomicFile file;
    Vault(Context c) { file = new AtomicFile(new File(c.getNoBackupFilesDir(), "credentials.enc")); }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("locky.oauth")) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("locky.oauth", KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); g.generateKey();
        }
        return (SecretKey)ks.getKey("locky.oauth", null);
    }
    synchronized JSONObject read() throws Exception {
        if (!file.getBaseFile().exists()) return new JSONObject().put("profiles", new JSONArray());
        JSONObject envelope = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        return new JSONObject(new String(c.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }
    synchronized void write(JSONObject value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        JSONObject envelope = new JSONObject().put("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP)).put("data", Base64.encodeToString(c.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        FileOutputStream stream = file.startWrite();
        try { stream.write(envelope.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception e) { file.failWrite(stream); throw e; }
    }
}
