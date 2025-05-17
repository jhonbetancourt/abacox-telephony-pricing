package com.infomedia.abacox.telephonypricing.cdr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
            // Should not happen with SHA-256
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    public static LocalDateTime epochToLocalDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds == 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault());
         // Consider UTC if Cisco CDRs are always UTC: ZoneId.of("UTC")
    }
    
    public static LocalDateTime ciscoDateToLocalDateTime(String ciscoDateString) {
        // Example: "Sep 11 11:30:00.043 CO Tue Sep 11 2007" - need to parse carefully
        // The PHP MKTimeFecha seems to handle a specific format.
        // This is a simplified example, real Cisco date parsing can be complex.
        // "11:43:53.825 CO Tue Sep 11 2007"
        if (ciscoDateString == null || ciscoDateString.trim().isEmpty()) {
            return null;
        }
        try {
            // Attempt to parse a common Cisco format, might need adjustment
            // Example: "11:30:00.043 CO Tue Sep 11 2007"
            // We need to extract "Sep 11 11:30:00 2007 UTC" (assuming CO is a timezone like abbreviation for UTC)
            String[] parts = ciscoDateString.split(" ");
            if (parts.length >= 6) {
                // Assuming format like: HH:mm:ss.SSS TZ DOW MMM DD YYYY
                // We need MMM DD HH:mm:ss YYYY TZ
                // parts[3]=MMM, parts[4]=DD, parts[0]=HH:mm:ss.SSS, parts[5]=YYYY
                String month = parts[3];
                String day = parts[4];
                String time = parts[0].substring(0, parts[0].indexOf('.')); // Remove millis
                String year = parts[5];
                String tz = "UTC"; // Assuming 'CO' or similar can be mapped or defaulted to UTC

                String parsableDateString = String.format("%s %s %s %s %s", month, day, time, year, tz);
                return LocalDateTime.parse(parsableDateString, CISCO_DATE_FORMATTER);
            }
        } catch (Exception e) {
            // Log parsing error
            System.err.println("Failed to parse Cisco date: " + ciscoDateString + " - " + e.getMessage());
        }
        return null;
    }


    public static String decimalToIp(long ipDecimal) {
        if (ipDecimal < 0) { // Cisco sometimes uses -1 for unknown
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
        if (number == null) return null;
        return number.replaceAll("[^0-9#*+]", ""); // Keep digits, #, *, +
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
        // PHP _duracion_seg handles "HH:MM:SS" or "MM'SS" or just seconds
        if (durationString == null || durationString.trim().isEmpty()) {
            return 0;
        }
        String cleanDuration = durationString.replace("'", ":").replace("\"", ":");
        String[] parts = cleanDuration.split(":");
        int seconds = 0;
        if (parts.length == 3) { // HH:MM:SS
            seconds = Integer.parseInt(parts[0].trim()) * 3600 +
                      Integer.parseInt(parts[1].trim()) * 60 +
                      Integer.parseInt(parts[2].trim());
        } else if (parts.length == 2) { // MM:SS
            seconds = Integer.parseInt(parts[0].trim()) * 60 +
                      Integer.parseInt(parts[1].trim());
        } else if (parts.length == 1) { // Seconds
            try {
                seconds = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException e) {
                return 0; // Or log error
            }
        }
        return seconds;
    }
}