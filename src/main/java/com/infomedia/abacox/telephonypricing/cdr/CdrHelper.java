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
            .withZone(ZoneId.of("UTC")); // Assuming Cisco logs in UTC


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

    public static LocalDateTime ciscoDateToLocalDateTime(String ciscoDateString) {
        if (ciscoDateString == null || ciscoDateString.trim().isEmpty()) {
            return null;
        }
        try {
            String[] parts = ciscoDateString.split(" ");
            if (parts.length >= 6) {
                String month = parts[3];
                String day = parts[4];
                String timeWithMillis = parts[0];
                String time = timeWithMillis.contains(".") ? timeWithMillis.substring(0, timeWithMillis.indexOf('.')) : timeWithMillis;
                String year = parts[5];
                String tz = "UTC";

                String parsableDateString = String.format("%s %s %s %s %s", month, day, time, year, tz);
                return LocalDateTime.parse(parsableDateString, CISCO_DATE_FORMATTER);
            }
        } catch (Exception e) {
            System.err.println("Failed to parse Cisco date: " + ciscoDateString + " - " + e.getMessage());
        }
        return null;
    }

    public static String decimalToIp(long ipDecimal) {
        if (ipDecimal < 0) {
            return null;
        }
        return ((ipDecimal >> 24) & 0xFF) + "." +
               ((ipDecimal >> 16) & 0xFF) + "." +
               ((ipDecimal >> 8) & 0xFF) + "." +
               (ipDecimal & 0xFF);
    }

    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return NUMERIC_PATTERN.matcher(str).matches();
    }

    public static String cleanPhoneNumber(String number) {
        if (number == null) return ""; // Return empty string for null input to avoid NPE
        return number.replaceAll("[^0-9#*+]", "");
    }
    
    public static String cleanString(String input) {
        if (input == null) return "";
        return input.trim().replace("\"", "");
    }


    public static String stripPbxPrefix(String number, List<String> pbxPrefixes) {
        if (number == null || pbxPrefixes == null || pbxPrefixes.isEmpty()) {
            return number;
        }
        for (String prefix : pbxPrefixes) {
            if (prefix != null && !prefix.isEmpty() && number.startsWith(prefix)) {
                return number.substring(prefix.length());
            }
        }
        return number;
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

    /**
     * Checks if the given string represents a potential internal extension.
     * Based on PHP's ExtensionPosible logic.
     *
     * @param extension The extension string to check.
     * @param limits    The configured limits for internal extensions.
     * @return true if it's a potential internal extension, false otherwise.
     */
    public static boolean isPotentialExtension(String extension, InternalExtensionLimitsDto limits) {
        if (extension == null || extension.isEmpty() || limits == null) {
            return false;
        }

        String cleanedExtension = cleanPhoneNumber(extension); // Keep #*+ for special extensions
        
        // Check against special extensions list first
        if (limits.getSpecialExtensions() != null && limits.getSpecialExtensions().contains(cleanedExtension)) {
            return true;
        }

        // For numeric checks, ensure it's purely numeric and doesn't start with '0' (PHP logic)
        if (isNumeric(cleanedExtension) && !cleanedExtension.startsWith("0")) {
            try {
                long numericExt = Long.parseLong(cleanedExtension);
                if (numericExt >= limits.getMinNumericValue() && numericExt <= limits.getMaxNumericValue()) {
                    // Check length constraints as well, as numeric value alone might not be sufficient
                    // if min/max length define a tighter range than min/max numeric value.
                    // Example: minLength=4 (minNumeric=1000), maxLength=4 (maxNumeric=9999)
                    // A number like 500 would be between min/max numeric but not length.
                    // However, PHP's $_LIM_INTERNAS['min'] and ['max'] are numeric values derived from lengths.
                    return cleanedExtension.length() >= limits.getMinLength() && cleanedExtension.length() <= limits.getMaxLength();
                }
            } catch (NumberFormatException e) {
                // Not a valid long, might be too long, or caught by specialExtensions if alphanumeric
                return false;
            }
        }
        return false;
    }
}