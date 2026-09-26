package cn.aqcraft.iusse.github;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * GitHub App 私钥（PEM）解析。
 * <p>
 * GitHub 后台下载到的是 **PKCS#1** 格式（{@code BEGIN RSA PRIVATE KEY}），
 * 而 Java 的 {@link KeyFactory} 只认 **PKCS#8**（{@code BEGIN PRIVATE KEY}）。
 * 这里用纯 JDK 完成两者转换，避免要求用户手动 {@code openssl} 转格式。
 */
public final class Pem {

    /** PKCS#8 里 rsaEncryption 的算法标识：SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }。 */
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private Pem() {
    }

    /**
     * 从 PEM 文本解析 RSA 私钥，自动识别 PKCS#1 / PKCS#8。
     */
    public static PrivateKey readRsaPrivateKey(String pem) throws GeneralSecurityException {
        if (pem == null || pem.trim().isEmpty()) {
            throw new GeneralSecurityException("私钥内容为空");
        }
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        if (base64.isEmpty()) {
            throw new GeneralSecurityException("私钥内容不含 Base64 数据");
        }

        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("私钥不是合法的 Base64 文本", e);
        }

        byte[] pkcs8 = pkcs1 ? wrapPkcs1AsPkcs8(der) : der;
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException("无法解析 RSA 私钥，请确认使用的是 GitHub App 下载的 .pem 文件", e);
        }
    }

    /**
     * 把 PKCS#1 的 RSAPrivateKey 包成 PKCS#8 的 PrivateKeyInfo。
     *
     * <pre>
     * PrivateKeyInfo ::= SEQUENCE {
     *     version             INTEGER (0),
     *     privateKeyAlgorithm AlgorithmIdentifier,
     *     privateKey          OCTET STRING   -- 这里放 PKCS#1 原始内容
     * }
     * </pre>
     */
    private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octetString = concat(encodeTagAndLength(0x04, pkcs1.length), pkcs1);

        byte[] body = concat(version, RSA_ALGORITHM_IDENTIFIER, octetString);
        return concat(encodeTagAndLength(0x30, body.length), body);
    }

    private static byte[] encodeTagAndLength(int tag, int length) {
        if (length < 0x80) {
            return new byte[]{(byte) tag, (byte) length};
        }
        if (length <= 0xFF) {
            return new byte[]{(byte) tag, (byte) 0x81, (byte) length};
        }
        if (length <= 0xFFFF) {
            return new byte[]{(byte) tag, (byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        return new byte[]{(byte) tag, (byte) 0x83,
                (byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
