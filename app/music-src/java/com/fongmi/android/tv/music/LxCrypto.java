package com.fongmi.android.tv.music;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 同步和音源都要用到的加解密，全部用 Java 标准库，不引第三方。
 */
public class LxCrypto {

    public static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String b64(byte[] data) {
        return new String(Base64.encode(data, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    public static byte[] unb64(String text) {
        try {
            return Base64.decode(text, Base64.DEFAULT);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 同步密钥：密码 md5 的前 16 位十六进制字符串当作 16 字节密钥 */
    public static byte[] key(String authCode) {
        return md5(authCode).substring(0, 16).getBytes(StandardCharsets.UTF_8);
    }

    public static String aesEncrypt(String plain, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return b64(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    public static String aesDecrypt(String text, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return new String(cipher.doFinal(unb64(text)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 音源脚本用的 AES：buffer、key、iv 都是 base64 字符串 */
    public static String aesEncryptRaw(String data, String key, String iv, String mode) {
        try {
            Cipher cipher = Cipher.getInstance(mode);
            SecretKeySpec spec = new SecretKeySpec(unb64(key), "AES");
            if (iv == null || iv.isEmpty()) {
                cipher.init(Cipher.ENCRYPT_MODE, spec);
            } else {
                byte[] finalIv = new byte[16];
                byte[] src = unb64(iv);
                System.arraycopy(src, 0, finalIv, 0, Math.min(src.length, 16));
                cipher.init(Cipher.ENCRYPT_MODE, spec, new IvParameterSpec(finalIv));
            }
            return b64(cipher.doFinal(unb64(data)));
        } catch (Exception e) {
            return "";
        }
    }

    public static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            return null;
        }
    }

    /** 公钥裸 base64（去掉 PEM 头尾），同步握手时要发给服务器 */
    public static String rsaPublic(KeyPair pair) {
        return b64(pair.getPublic().getEncoded());
    }

    /** RSA-OAEP-SHA1 解密，同步握手用它读服务器回包 */
    public static String rsaDecrypt(String text, KeyPair pair) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec spec = new OAEPParameterSpec("SHA-1", "MGF1", new java.security.spec.MGF1ParameterSpec("SHA-1"), javax.crypto.spec.PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate(), spec);
            return new String(cipher.doFinal(unb64(text)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** RSA 加密，音源脚本可能用到 */
    public static String rsaEncrypt(String data, String publicKey, String mode) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            java.security.Key key = factory.generatePublic(new X509EncodedKeySpec(unb64(publicKey.trim())));
            Cipher cipher = Cipher.getInstance(mode);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return b64(cipher.doFinal(unb64(data)));
        } catch (Exception e) {
            return "";
        }
    }

    /** RSA 私钥解密，音源脚本可能用到 */
    public static String rsaDecryptRaw(String data, String privateKey, String mode) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            java.security.Key key = factory.generatePrivate(new PKCS8EncodedKeySpec(unb64(privateKey.trim())));
            Cipher cipher = Cipher.getInstance(mode);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(unb64(data)));
        } catch (Exception e) {
            return "";
        }
    }
}
