package com.shzu.schedule.util;

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
 * 敏感信息本地加密（学号/密码等）
 *
 * 使用 Android Keystore 里的 AES-256-GCM 密钥加解密：
 * 密钥由系统硬件级密钥库保管、无法导出，落盘内容即使被读取也无法还原明文，
 * 避免明文密码直接写在 SharedPreferences 里被泄露。
 */
public class CryptoHelper {

    private static final String TAG = "CryptoHelper";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "shzu_schedule_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry entry = ks.getEntry(ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return kg.generateKey();
    }

    /** 加密并 Base64 编码；失败返回空串（调用方按未保存处理） */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, getKey());
            byte[] iv = c.getIV();
            byte[] enc = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed");
            return "";
        }
    }

    /** 解密；失败返回空串 */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            if (all.length <= IV_LEN) return "";
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            byte[] enc = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, enc, 0, enc.length);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(enc), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed");
            return "";
        }
    }
}
