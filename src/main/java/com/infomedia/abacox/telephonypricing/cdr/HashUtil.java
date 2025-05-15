package com.infomedia.abacox.telephonypricing.cdr;

import lombok.extern.log4j.Log4j2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Log4j2
public class HashUtil {

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    /**
     * Calculates the SHA-256 hash of a given string.
     *
     * @param input The string to hash.
     * @return The SHA-256 hash as a lowercase hex string, or null if hashing fails.
     */
    public static String sha256(String input) {
        if (input == null) {
            // Decide how to handle null input - return null, empty string, or hash of empty string?
            // Returning null seems reasonable to indicate invalid input for hashing.
            log.info("Input string for SHA-256 hashing is null.");
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.info("SHA-256 algorithm not found!", e);
            // This is a critical environment error. Re-throwing as RuntimeException.
            throw new RuntimeException("SHA-256 hashing algorithm unavailable", e);
        }
    }

    /**
     * Converts a byte array to its hexadecimal string representation (lowercase).
     *
     * @param bytes The byte array.
     * @return The hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return ""; // Or null, depending on desired behavior for null byte array
        }
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF; // Ensure positive value
            hexChars[j * 2] = HEX_ARRAY[v >>> 4]; // High nibble
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F]; // Low nibble
        }
        return new String(hexChars);
    }
}