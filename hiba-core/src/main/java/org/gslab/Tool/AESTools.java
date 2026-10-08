package org.gslab.Tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

public class AESTools {

    private static final String key = "hj7x89H$yuBI0456";
    /*
     * @author miracle.qu
     * @Description AES算法加密明文
     * @param data 明文
     * @param key 密鑰，長度16
     * @param iv 偏移量，長度16
     * @return密文
     */

    public static String encryptAES(String data, String KeyContext) {
        try {
            // 偏移量, 取輸入的KeyContext (時間) -> SHA-256 -> 0~16字節
            String initVector = getSHA256(KeyContext).substring(0, 16); // Initialization Vector

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            int blockSize = cipher.getBlockSize();
            byte[] dataBytes = data.getBytes();
            int plaintextLength = dataBytes.length;
            if (plaintextLength % blockSize != 0) {
                plaintextLength = plaintextLength + (blockSize - (plaintextLength % blockSize));
            }
            byte[] plaintext = new byte[plaintextLength];
            System.arraycopy(dataBytes, 0, plaintext, 0, dataBytes.length);
            SecretKeySpec keyspec = new SecretKeySpec(key.getBytes(), "AES");
            IvParameterSpec ivspec = new IvParameterSpec(initVector.getBytes()); // CBC模式，需要一個向量iv，可增加加密算法的強度
            cipher.init(Cipher.ENCRYPT_MODE, keyspec, ivspec);
            byte[] encrypted = cipher.doFinal(plaintext);

            return AESTools.encode(encrypted).trim(); // BASE64做轉碼。
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String decryptAES(String data, String KeyContext) {
        try {
            // 偏移量, 取輸入的KeyContext (時間) -> SHA-256 -> 0~16字節
            String offset = getSHA256(KeyContext).substring(0, 16);

            byte[] encrypted1 = AESTools.decode(data); // 先用base64解密
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            SecretKeySpec keyspec = new SecretKeySpec(key.getBytes(), "AES");
            IvParameterSpec ivspec = new IvParameterSpec(offset.getBytes());
            cipher.init(Cipher.DECRYPT_MODE, keyspec, ivspec);
            byte[] original = cipher.doFinal(encrypted1);
            String originalString = new String(original);

            return originalString.trim();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String encode(byte[] byteArray) {
        Base64.Encoder encoder = Base64.getEncoder();
        return encoder.encodeToString(byteArray);
    }

    public static byte[] decode(String base64EncodedString) {
        Base64.Decoder decoder = Base64.getDecoder();
        return decoder.decode(base64EncodedString);
    }

    public static String getSHA256(String password) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return DatatypeConverter.printHexBinary(digest).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        try {
            String KeyContext = "2023-03-28_17-22";
            System.out.println("明文:");
            String plaintext =
                    "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDKECyex4vBbmvOgNEmjxxEne0eexae5i6VY8YQAgS7OMO1Xy66z2JJtHcEVG5lOXqVQhjHNskFGk5RkHRJJFLf0KqdffiBRzkP5C7tGY9e7e9pT/2ZoNgclKRacwt2JImx1XahSDQljS3E6rpflqlvYMYXDl2Rl0wccFZp43n4T/r8RKaQFVgLGruh71e4vD4PHt4VIkWTP8v1LGbGxXfuskHgffeGOC+CnWIMN5WBXlkWasvsR1r8iM6RTOCldb/QOcbDGBWr2PZOI8EJImoANil72kncpXaHZX8qtDPTZg8ifH+KxedV2Mxv74BE+E9oT/S0VRKMlEpq7o6YH5xJ ubuntu@ubuntu";
            System.out.println(plaintext);
            System.out.println("-------------");
            String ciphertext = encryptAES(plaintext, KeyContext);
            System.out.println("密文:");
            System.out.println(ciphertext);
            System.out.println("-------------");
            System.out.println("明文:");
            String decrypt = decryptAES(ciphertext, KeyContext);
            System.out.println(decrypt);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
