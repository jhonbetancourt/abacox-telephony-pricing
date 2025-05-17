package com.infomedia.abacox.telephonypricing.cdr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class CdrHelper {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[0-9]+$");
    private static final Pattern NON_DIGIT_EXCEPT_FIRST_PATTERN = Pattern.compile("(?<=.)[^0-9#*+]");


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
        String trimmed = input.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static String cleanAndStripPhoneNumber(String number, List<String> pbxPrefixes, boolean safeMode) {
        if (number == null) return "";
        String currentNumber = number.trim();

        boolean pbxPrefixesAvailable = pbxPrefixes != null && !pbxPrefixes.isEmpty();
        boolean prefixFoundAndStripped = false;

        if (pbxPrefixesAvailable) {
            for (String pbxPrefix : pbxPrefixes) {
                if (pbxPrefix != null && !pbxPrefix.isEmpty() && currentNumber.startsWith(pbxPrefix)) {
                    currentNumber = currentNumber.substring(pbxPrefix.length());
                    prefixFoundAndStripped = true;
                    break;
                }
            }
        }

        if (pbxPrefixesAvailable && !prefixFoundAndStripped && !safeMode) {
            return "";
        }
        // If safeMode or no PBX prefixes, or prefix was stripped, continue with currentNumber

        if (currentNumber.isEmpty()) return "";

        String firstChar = currentNumber.substring(0, 1);
        String partial = currentNumber.length() > 1 ? currentNumber.substring(1) : "";

        if (!partial.isEmpty()) {
            StringBuilder digitsOnlyPartial = new StringBuilder();
            boolean nonDigitFound = false;
            for (char c : partial.toCharArray()) {
                if (Character.isDigit(c)) {
                    digitsOnlyPartial.append(c);
                } else {
                    nonDigitFound = true; // Mark that a non-digit was encountered
                    break;
                }
            }
            partial = digitsOnlyPartial.toString();
        }

        if ("+".equals(firstChar)) {
            firstChar = "";
        }

        return firstChar + partial;
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

        if (isNumeric(cleanedExtension) && !cleanedExtension.startsWith("0")) {
            try {
                // Length check first
                if (cleanedExtension.length() < limits.getMinLength() || cleanedExtension.length() > limits.getMaxLength()) {
                    return false;
                }
                // Then numeric value check (important for very long numbers that might fit length but not long type)
                if (cleanedExtension.length() < 19) { // Max length for Long
                    long numericExt = Long.parseLong(cleanedExtension);
                    return numericExt >= limits.getMinNumericValue() && numericExt <= limits.getMaxNumericValue();
                }
                // If longer than 18 digits, it's unlikely to fit in a long, but could still be a valid "extension" string
                // if min/max NumericValue are not restrictive (e.g. 0 to Long.MAX_VALUE).
                // For very long numeric strings that are valid extensions by length but exceed Long.MAX_VALUE,
                // this check might be too simple. PHP's string comparison for numbers handles this differently.
                // However, typical extensions are not this long.
                return true; // Passed length check, and is numeric.
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    public static PaddedSeriesDto rellenaSerie(String telefono, String ndc, String serieInicialStr, String serieFinalStr) {
        String initial = (serieInicialStr == null) ? "" : String.valueOf(serieInicialStr);
        String finalNum = (serieFinalStr == null) ? "" : String.valueOf(serieFinalStr);

        int lenInitial = initial.length();
        int lenFinal = finalNum.length();
        int diff = lenFinal - lenInitial;

        if (diff > 0) {
            initial = String.join("", Collections.nCopies(diff, "0")) + initial;
        } else if (diff < 0) {
            finalNum = finalNum + String.join("", Collections.nCopies(-diff, "9"));
        }

        int lenSeries = initial.length();
        int lenNdc = (ndc == null) ? 0 : ndc.length();
        int lenTelefono = (telefono == null) ? 0 : telefono.length();

        int diffLenTelefonoSeries = lenTelefono - lenNdc;

        if (diffLenTelefonoSeries != lenSeries) {
            if (diffLenTelefonoSeries < 0) { // Number part is shorter than series definition
                return new PaddedSeriesDto((ndc != null ? ndc : "") + initial, (ndc != null ? ndc : "") + finalNum);
            }
            // Number part is longer than series definition, pad series to match number part length
            int paddingLength = diffLenTelefonoSeries - lenSeries;
            initial = initial + String.join("", Collections.nCopies(paddingLength, "0"));
            finalNum = finalNum + String.join("", Collections.nCopies(paddingLength, "9"));
        }
        return new PaddedSeriesDto((ndc != null ? ndc : "") + initial, (ndc != null ? ndc : "") + finalNum);
    }

    public static long duracionMinuto(int durationSeconds, boolean billedInSeconds) {
        if (billedInSeconds) {
            return durationSeconds;
        }
        if (durationSeconds <= 0) {
            return 0;
        }
        return (long) Math.ceil((double) durationSeconds / 60.0);
    }
}
