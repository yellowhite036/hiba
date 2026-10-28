package org.gslab.tpm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.gslab.util.Util;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * TPM Attack Simulation Test
 *
 * This test class simulates various attack scenarios against TPM signature
 * verification.
 * It uses real TPM-signed data from a captured getFile service response.
 *
 * NOTE: These tests do NOT require real TPM hardware.
 * TPMService.verifyWithJavaCrypto() uses standard Java Crypto APIs to verify
 * signatures.
 */
class TPMAttackSimulationTest {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM_RAW = "NONEwithRSA";

    private JSONObject verificationData;
    private JSONObject tpmSignatureInfo;

    private String originalSignature;
    private String originalPublicKey;
    private String originalMessageData;

    @BeforeEach
    void setUp() throws Exception {
        InputStream is = getClass().getResourceAsStream("/tpm_verification_sample.json");
        assertThat(is).as("Test resource file should exist").isNotNull();

        String jsonContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        verificationData = new JSONObject(jsonContent);
        tpmSignatureInfo = verificationData.getJSONObject("tpmSignatureInfo");

        originalSignature = tpmSignatureInfo.getString("signature");
        originalPublicKey = tpmSignatureInfo.getString("publicKey");
        originalMessageData = tpmSignatureInfo.getString("messageData");

        System.out.println();
        System.out.println("=== TPM Attack Simulation Test Setup ===");
        System.out.println("Device ID: " + Util.truncateFirstAndLast(tpmSignatureInfo.getString("deviceId"), 8));
        System.out.println("TPM Version: " + tpmSignatureInfo.getString("tpmVersion"));
        System.out.println("Signature Algorithm: " + tpmSignatureInfo.getString("signatureAlgorithm"));
        System.out.println("Message Data Length: " + originalMessageData.length() + " chars (Base64)");
        System.out.println();
    }

    @Nested
    @DisplayName("Baseline Tests - Valid Signature Verification")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BaselineTests {

        @Test
        @Order(1)
        @DisplayName("Original TPM signature should be verified successfully")
        void testValidSignatureVerification() throws Exception {
            PublicKey publicKey = extractPublicKey(originalPublicKey);
            byte[] messageData = Base64.getDecoder().decode(originalMessageData);
            byte[] signature = Base64.getDecoder().decode(originalSignature);

            boolean verified = TPMService.verifyWithJavaCrypto(publicKey, messageData, signature);

            System.out.println("=== Valid Signature Test ===");
            System.out.println("Result: " + (verified ? "PASSED - Signature is valid" : "FAILED - Signature invalid"));
            System.out.println();

            assertThat(verified)
                    .as("Original TPM signature should verify successfully")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Attack Simulation Tests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AttackSimulationTests {

        @Test
        @Order(1)
        @DisplayName("Tampered message data should fail verification (Data Integrity Attack)")
        void testTamperedMessageData() throws Exception {
            PublicKey publicKey = extractPublicKey(originalPublicKey);
            byte[] signature = Base64.getDecoder().decode(originalSignature);

            // Tamper with message data - modify first byte
            byte[] originalData = Base64.getDecoder().decode(originalMessageData);
            byte[] tamperedData = originalData.clone();
            tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);

            boolean verified = TPMService.verifyWithJavaCrypto(publicKey, tamperedData, signature);

            System.out.println("=== Tampered Message Data Attack ===");
            System.out.println("Attack: Modified first byte of message data");
            System.out.println("Original bytes: " + Util.bytesToHexFirstAndLast(originalData, 16));
            System.out.println("Tampered bytes: " + Util.bytesToHexFirstAndLast(tamperedData, 16));
            System.out.println(
                    "Result: " + (verified ? "ATTACK SUCCEEDED (BAD)" : "ATTACK BLOCKED - Verification failed"));
            System.out.println();

            assertThat(verified)
                    .as("Tampered message data should fail verification")
                    .isFalse();
        }

        @Test
        @Order(2)
        @DisplayName("Tampered signature should fail verification (Signature Forgery Attack)")
        void testTamperedSignature() throws Exception {
            PublicKey publicKey = extractPublicKey(originalPublicKey);
            byte[] messageData = Base64.getDecoder().decode(originalMessageData);

            // Tamper with signature - modify last byte
            byte[] originalSig = Base64.getDecoder().decode(originalSignature);
            byte[] tamperedSig = originalSig.clone();
            tamperedSig[tamperedSig.length - 1] = (byte) (tamperedSig[tamperedSig.length - 1] ^ 0xFF);

            boolean verified = TPMService.verifyWithJavaCrypto(publicKey, messageData, tamperedSig);

            System.out.println("=== Tampered Signature Attack ===");
            System.out.println("Attack: Modified last byte of signature");
            System.out.println("Original bytes: " + Util.bytesToHexFirstAndLast(originalSig, 16));
            System.out.println("Tampered bytes: " + Util.bytesToHexFirstAndLast(tamperedSig, 16));
            System.out.println(
                    "Result: " + (verified ? "ATTACK SUCCEEDED (BAD)" : "ATTACK BLOCKED - Verification failed"));
            System.out.println();

            assertThat(verified)
                    .as("Tampered signature should fail verification")
                    .isFalse();
        }

        @Test
        @Order(3)
        @DisplayName("Wrong public key should fail verification (Key Substitution Attack)")
        void testWrongPublicKey() throws Exception {
            // Generate a different key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair attackerKeyPair = keyGen.generateKeyPair();

            byte[] messageData = Base64.getDecoder().decode(originalMessageData);
            byte[] signature = Base64.getDecoder().decode(originalSignature);

            boolean verified = TPMService.verifyWithJavaCrypto(attackerKeyPair.getPublic(), messageData, signature);

            System.out.println("=== Wrong Public Key Attack ===");
            System.out.println("Attack: Used attacker's RSA key instead of TPM's public key");
            System.out.println("Original (TPM) public key: " + Util.truncateFirstAndLast(originalPublicKey, 16));
            System.out.println("Attacker's public key:     "
                    + Util.truncateFirstAndLast(
                            Base64.getEncoder()
                                    .encodeToString(attackerKeyPair.getPublic().getEncoded()),
                            16));
            System.out.println(
                    "Result: " + (verified ? "ATTACK SUCCEEDED (BAD)" : "ATTACK BLOCKED - Verification failed"));
            System.out.println();

            assertThat(verified).as("Wrong public key should fail verification").isFalse();
        }

        @Test
        @Order(4)
        @DisplayName("Empty message data should fail verification")
        void testEmptyMessageData() throws Exception {
            PublicKey publicKey = extractPublicKey(originalPublicKey);
            byte[] signature = Base64.getDecoder().decode(originalSignature);
            byte[] emptyData = new byte[0];

            boolean verified = TPMService.verifyWithJavaCrypto(publicKey, emptyData, signature);

            byte[] originalData = Base64.getDecoder().decode(originalMessageData);

            System.out.println("=== Empty Message Data Attack ===");
            System.out.println("Attack: Replaced message data with empty bytes");
            System.out.println("Original data length: " + originalData.length + " bytes");
            System.out.println("Tampered data length: " + emptyData.length + " bytes (empty)");
            System.out.println(
                    "Result: " + (verified ? "ATTACK SUCCEEDED (BAD)" : "ATTACK BLOCKED - Verification failed"));
            System.out.println();

            assertThat(verified)
                    .as("Empty message data should fail verification")
                    .isFalse();
        }

        @Test
        @Order(5)
        @DisplayName("Random garbage signature should fail verification")
        void testGarbageSignature() throws Exception {
            PublicKey publicKey = extractPublicKey(originalPublicKey);
            byte[] messageData = Base64.getDecoder().decode(originalMessageData);

            // Create random garbage signature
            byte[] garbageSignature = new byte[256];
            for (int i = 0; i < garbageSignature.length; i++) {
                garbageSignature[i] = (byte) (Math.random() * 256);
            }

            boolean verified = TPMService.verifyWithJavaCrypto(publicKey, messageData, garbageSignature);

            byte[] originalSig = Base64.getDecoder().decode(originalSignature);

            System.out.println("=== Garbage Signature Attack ===");
            System.out.println("Attack: Used random garbage bytes as signature");
            System.out.println("Original signature: " + Util.bytesToHexFirstAndLast(originalSig, 16));
            System.out.println("Garbage signature: " + Util.bytesToHexFirstAndLast(garbageSignature, 16));
            System.out.println(
                    "Result: " + (verified ? "ATTACK SUCCEEDED (BAD)" : "ATTACK BLOCKED - Verification failed"));
            System.out.println();

            assertThat(verified)
                    .as("Garbage signature should fail verification")
                    .isFalse();
        }

        @Test
        @Order(6)
        @DisplayName("Signature from different message should fail (Cross-Message Attack)")
        void testCrossMessageAttack() throws Exception {
            // Generate our own key pair for this test
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            // Sign message A
            byte[] messageA = "Original message A".getBytes(StandardCharsets.UTF_8);
            byte[] signatureA = signData(keyPair.getPrivate(), messageA);

            // Try to use signature A for message B
            byte[] messageB = "Different message B".getBytes(StandardCharsets.UTF_8);

            boolean verified = TPMService.verifyWithJavaCrypto(keyPair.getPublic(), messageB, signatureA);

            System.out.println("=== Cross-Message Attack ===");
            System.out.println("Attack: Used signature from message A to verify message B");
            System.out.println("Original message A: '" + new String(messageA, StandardCharsets.UTF_8) + "'");
            System.out.println("Tampered message B: '" + new String(messageB, StandardCharsets.UTF_8) + "'");
            System.out.println(
                    "Result: " + (verified ? "ATTACK SUCCEEDED (BAD)" : "ATTACK BLOCKED - Verification failed"));
            System.out.println();

            assertThat(verified)
                    .as("Signature from different message should fail verification")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Self-Generated Signature Tests (For Attack Comparison)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SelfSignedTests {

        @Test
        @Order(1)
        @DisplayName("Self-signed valid signature should pass verification")
        void testSelfSignedValid() throws Exception {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            byte[] testData = "Test message for self-signing".getBytes(StandardCharsets.UTF_8);
            byte[] signature = signData(keyPair.getPrivate(), testData);

            boolean verified = TPMService.verifyWithJavaCrypto(keyPair.getPublic(), testData, signature);

            System.out.println("=== Self-Signed Valid Test ===");
            System.out.println("Created our own key pair and signed test data");
            System.out.println("Result: " + (verified ? "PASSED" : "FAILED"));
            System.out.println();

            assertThat(verified).isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("Self-signed with tampered data should fail verification")
        void testSelfSignedTampered() throws Exception {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            byte[] originalData = "Original test message".getBytes(StandardCharsets.UTF_8);
            byte[] signature = signData(keyPair.getPrivate(), originalData);

            byte[] tamperedData = "Tampered test message".getBytes(StandardCharsets.UTF_8);

            boolean verified = TPMService.verifyWithJavaCrypto(keyPair.getPublic(), tamperedData, signature);

            System.out.println("=== Self-Signed Tampered Test ===");
            System.out.println("Signed 'Original test message', verified with 'Tampered test message'");
            System.out.println("Result: " + (verified ? "ATTACK SUCCEEDED (BAD)" : "ATTACK BLOCKED"));
            System.out.println();

            assertThat(verified).isFalse();
        }
    }

    /**
     * Extract PublicKey from Base64-encoded string
     */
    private PublicKey extractPublicKey(String base64PublicKey) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(base64PublicKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        return keyFactory.generatePublic(keySpec);
    }

    /**
     * Sign data using private key (mimics TPM signing for test purposes)
     */
    private byte[] signData(PrivateKey privateKey, byte[] data) throws Exception {
        // Calculate SHA-256 hash first (same as TPMService)
        byte[] digest = Util.calculateHash(data);

        // PKCS#1 v1.5 SHA-256 DigestInfo prefix
        byte[] sha256DigestInfo = new byte[] {
            0x30,
            0x31,
            0x30,
            0x0d,
            0x06,
            0x09,
            0x60,
            (byte) 0x86,
            0x48,
            0x01,
            0x65,
            0x03,
            0x04,
            0x02,
            0x01,
            0x05,
            0x00,
            0x04,
            0x20
        };

        // Combine DigestInfo prefix with the digest
        byte[] digestInfo = new byte[sha256DigestInfo.length + digest.length];
        System.arraycopy(sha256DigestInfo, 0, digestInfo, 0, sha256DigestInfo.length);
        System.arraycopy(digest, 0, digestInfo, sha256DigestInfo.length, digest.length);

        // Sign with NONEwithRSA
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM_RAW);
        sig.initSign(privateKey);
        sig.update(digestInfo);
        return sig.sign();
    }
}
