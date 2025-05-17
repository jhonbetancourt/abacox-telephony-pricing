// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrHelper.java
package com.infomedia.abacox.telephonypricing.cdr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class CdrHelper {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]+$");
    private static final DateTimeFormatter CISCO_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss yyyy z", Locale.ENGLISH)
            .withZone(ZoneId.of("UTC"));


    public static String calculateSha256(String input) {
        if (input == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    public static LocalDateTime epochToLocalDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds == 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.of("UTC"));
    }
    
    public static String cleanString(String input) {
        if (input == null) return "";
        return input.trim().replace("\"", "");
    }

    /**
     * Cleans a phone number by removing non-essential characters and optionally stripping PBX prefixes.
     * Mimics PHP's limpiar_numero.
     * @param number The raw number string.
     * @param pbxPrefixes List of PBX prefixes to strip.
     * @param safeMode If true and a PBX prefix is defined but not found, returns original number (for further processing).
     *                 If false and PBX prefix defined but not found, returns empty string.
     * @return Cleaned number.
     */
    public static String cleanAndStripPhoneNumber(String number, List<String> pbxPrefixes, boolean safeMode) {
        if (number == null) return "";
        String currentNumber = number.trim();

        if (pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            boolean prefixFound = false;
            for (String pbxPrefix : pbxPrefixes) {
                if (pbxPrefix != null && !pbxPrefix.isEmpty() && currentNumber.startsWith(pbxPrefix)) {
                    currentNumber = currentNumber.substring(pbxPrefix.length());
                    prefixFound = true;
                    break;
                }
            }
            if (!prefixFound && !safeMode) { // If prefix was expected (not safe mode) but not found
                return ""; // PHP logic implies returning empty if prefix mandatory and not found
            }
        }
        
        // PHP: Elimina si y solo si encuentra "#" o "*" en una posicion cualquiera posterior al primer caracter
        if (currentNumber.length() > 1) {
            String firstChar = currentNumber.substring(0, 1);
            String rest = currentNumber.substring(1);
            
            StringBuilder cleanedRest = new StringBuilder();
            boolean nonNumericFound = false;
            for (char c : rest.toCharArray()) {
                if (Character.isDigit(c)) {
                    cleanedRest.append(c);
                } else if (c == '#' || c == '*') { // Allow these specific chars
                    cleanedRest.append(c);
                } else {
                    nonNumericFound = true; // Mark that a non-allowed char was found
                    break; // Stop at the first non-allowed, non-numeric char
                }
            }
            currentNumber = firstChar + cleanedRest.toString();
        }
        
        // Remove leading '+' if present (PHP: if ($primercar == '+') { $primercar = ''; })
        if (currentNumber.startsWith("+")) {
            currentNumber = currentNumber.substring(1);
        }

        return currentNumber;
    }
    
    public static String cleanPhoneNumber(String number) { // Simpler version if no PBX stripping needed
        if (number == null) return "";
        return number.replaceAll("[^0-9#*+]", "");
    }


    public static int durationToSeconds(String durationString) {
        if (durationString == null || durationString.trim().isEmpty()) {
            return 0;
        }
        String cleanDuration = durationString.replace("'", ":").replace("\"", "");
        String[] parts = cleanDuration.split(":");
        int seconds = 0;
        try {
            if (parts.length == 3) { // HH:MM:SS
                seconds = Integer.parseInt(parts[0].trim()) * 3600 +
                          Integer.parseInt(parts[1].trim()) * 60 +
                          Integer.parseInt(parts[2].trim());
            } else if (parts.length == 2) { // MM:SS
                seconds = Integer.parseInt(parts[0].trim()) * 60 +
                          Integer.parseInt(parts[1].trim());
            } else if (parts.length == 1 && isNumeric(parts[0].trim())) { // Seconds
                seconds = Integer.parseInt(parts[0].trim());
            }
        } catch (NumberFormatException e) {
             System.err.println("Could not parse duration string: " + durationString);
            return 0;
        }
        return seconds;
    }
    
    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return NUMERIC_PATTERN.matcher(str).matches();
    }

    public static boolean isPotentialExtension(String extension, InternalExtensionLimitsDto limits) {
        if (extension == null || extension.isEmpty() || limits == null) {
            return false;
        }
        String cleanedExtension = cleanPhoneNumber(extension);
        
        if (limits.getSpecialExtensions() != null && limits.getSpecialExtensions().contains(cleanedExtension)) {
            return true;
        }

        if (isNumeric(cleanedExtension) && !cleanedExtension.startsWith("0")) {
            try {
                long numericExt = Long.parseLong(cleanedExtension);
                // PHP logic: $extension >= $_LIM_INTERNAS['min'] && $extension <= $_LIM_INTERNAS['max']
                // Where min/max are numeric values derived from lengths (e.g. minLength 4 -> 1000)
                return numericExt >= limits.getMinNumericValue() && numericExt <= limits.getMaxNumericValue() &&
                       cleanedExtension.length() >= limits.getMinLength() && cleanedExtension.length() <= limits.getMaxLength();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    public static String padSeries(String seriesPart, int targetLength, boolean padWithZerosAtEnd) {
        if (seriesPart == null) seriesPart = "";
        if (seriesPart.length() >= targetLength) {
            return seriesPart.substring(0, targetLength);
        }
        char padChar = padWithZerosAtEnd ? '0' : '9'; // PHP uses 0 for initial_number padding, 9 for final_number
        StringBuilder sb = new StringBuilder(seriesPart);
        while (sb.length() < targetLength) {
            sb.append(padChar);
        }
        return sb.toString();
    }
}