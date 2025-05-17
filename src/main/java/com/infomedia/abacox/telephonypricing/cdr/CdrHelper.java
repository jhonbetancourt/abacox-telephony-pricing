// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrHelper.java
package com.infomedia.abacox.telephonypricing.cdr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class CdrHelper {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]+$");
    private static final DateTimeFormatter CISCO_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss yyyy z", Locale.ENGLISH)
            .withZone(ZoneId.of("UTC")); // Assuming Cisco CDR dates are UTC


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
    
    public static String cleanAndStripPhoneNumber(String number, List<String> pbxPrefixes, boolean safeMode) {
        if (number == null) return "";
        String currentNumber = number.trim();
        boolean pbxPrefixesAvailable = pbxPrefixes != null && !pbxPrefixes.isEmpty();

        if (pbxPrefixesAvailable) {
            boolean prefixFound = false;
            for (String pbxPrefix : pbxPrefixes) {
                if (pbxPrefix != null && !pbxPrefix.isEmpty() && currentNumber.startsWith(pbxPrefix)) {
                    currentNumber = currentNumber.substring(pbxPrefix.length());
                    prefixFound = true;
                    break;
                }
            }
            if (!prefixFound && !safeMode) {
                return ""; 
            }
        }
        
        if (currentNumber.length() > 1) {
            String firstChar = currentNumber.substring(0, 1);
            String rest = currentNumber.substring(1);
            
            StringBuilder cleanedRest = new StringBuilder();
            for (char c : rest.toCharArray()) {
                if (Character.isDigit(c) || c == '#' || c == '*') {
                    cleanedRest.append(c);
                } else {
                    break; 
                }
            }
            currentNumber = firstChar + cleanedRest.toString();
        }
        
        if (currentNumber.startsWith("+")) {
            currentNumber = currentNumber.substring(1);
        }

        return currentNumber;
    }
    
    public static String cleanPhoneNumber(String number) {
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
            if (parts.length == 3) { 
                seconds = Integer.parseInt(parts[0].trim()) * 3600 +
                          Integer.parseInt(parts[1].trim()) * 60 +
                          Integer.parseInt(parts[2].trim());
            } else if (parts.length == 2) { 
                seconds = Integer.parseInt(parts[0].trim()) * 60 +
                          Integer.parseInt(parts[1].trim());
            } else if (parts.length == 1 && isNumeric(parts[0].trim())) { 
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

        if (isNumeric(cleanedExtension) && !cleanedExtension.startsWith("0")) { // PHP: not(FUNCIONARIO_EXTENSION LIKE '0%')
            try {
                // PHP logic: $extension >= $_LIM_INTERNAS['min'] && $extension <= $_LIM_INTERNAS['max']
                // Where min/max are numeric values derived from lengths (e.g. minLength 4 -> 1000)
                // The PHP logic for min/max numeric values based on length is:
                // min: '1' + '0'.repeat(length-1)
                // max: '9'.repeat(length)
                // This was simplified to just length check in previous Java. Reinstating numeric value check.
                long numericExt = Long.parseLong(cleanedExtension);
                return numericExt >= limits.getMinNumericValue() && numericExt <= limits.getMaxNumericValue() &&
                       cleanedExtension.length() >= limits.getMinLength() && cleanedExtension.length() <= limits.getMaxLength();
            } catch (NumberFormatException e) {
                return false; // Not a valid long, so not a typical numeric extension
            }
        }
        return false;
    }

    public static PaddedSeriesDto rellenaSerie(String telefono, String ndc, String serieInicialStr, String serieFinalStr) {
        // Convert series parts to string, handle nulls
        String initial = (serieInicialStr == null) ? "" : String.valueOf(serieInicialStr);
        String finalNum = (serieFinalStr == null) ? "" : String.valueOf(serieFinalStr);

        int lenInitial = initial.length();
        int lenFinal = finalNum.length();
        int diff = lenFinal - lenInitial;

        if (diff > 0) { // Initial < Final, pad initial with leading zeros
            initial = String.join("", Collections.nCopies(diff, "0")) + initial;
        } else if (diff < 0) { // Initial > Final, pad final with trailing nines
            finalNum = finalNum + String.join("", Collections.nCopies(-diff, "9"));
        }
        // Now initial and finalNum have the same conceptual length for comparison base

        int lenSeries = initial.length(); // Length of the series part
        int lenNdc = (ndc == null) ? 0 : ndc.length();
        int lenTelefono = (telefono == null) ? 0 : telefono.length();

        int diffLenTelefonoSeries = lenTelefono - lenNdc; // This is the length of the part of 'telefono' that should match the series

        if (diffLenTelefonoSeries != lenSeries) {
            if (diffLenTelefonoSeries < 0) { // Should not happen if NDC is shorter than phone
                 return new PaddedSeriesDto(ndc + initial, ndc + finalNum); // Or handle error
            }
            // Pad series to match the length of the comparable part of 'telefono'
            initial = initial + String.join("", Collections.nCopies(diffLenTelefonoSeries - lenSeries, "0"));
            finalNum = finalNum + String.join("", Collections.nCopies(diffLenTelefonoSeries - lenSeries, "9"));
        }
        return new PaddedSeriesDto(ndc + initial, ndc + finalNum);
    }

    public static int duracionMinuto(int durationSeconds, boolean billedInSeconds) {
        if (billedInSeconds) {
            return durationSeconds; // Each second is a unit
        }
        if (durationSeconds <= 0) {
            return 0; // Or 1 if minimum billing is 1 minute even for 0 second calls (PHP implies 0 if duration <=0)
        }
        return (int) Math.ceil((double) durationSeconds / 60.0);
    }
}