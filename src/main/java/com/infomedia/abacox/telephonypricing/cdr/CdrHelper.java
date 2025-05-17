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
        return input.trim().replace("\"", "");
    }

    /**
     * Cleans and potentially strips PBX prefixes from a phone number, mimicking PHP's limpiar_numero.
     *
     * @param number The raw number string.
     * @param pbxPrefixes List of PBX prefixes to check for stripping. Can be null or empty.
     * @param safeMode If true and pbxPrefixes are provided but none match, the original number (after basic cleaning) is returned.
     *                 If false and pbxPrefixes are provided but none match, an empty string is returned.
     * @return The cleaned and potentially stripped number.
     */
    public static String cleanAndStripPhoneNumber(String number, List<String> pbxPrefixes, boolean safeMode) {
        if (number == null) return "";
        String currentNumber = number.trim();
        String originalNumberForSafeMode = currentNumber; // Keep original for safe mode fallback

        boolean pbxPrefixesAvailable = pbxPrefixes != null && !pbxPrefixes.isEmpty();

        if (pbxPrefixesAvailable) {
            boolean prefixFoundAndStripped = false;
            for (String pbxPrefix : pbxPrefixes) {
                if (pbxPrefix != null && !pbxPrefix.isEmpty() && currentNumber.startsWith(pbxPrefix)) {
                    currentNumber = currentNumber.substring(pbxPrefix.length());
                    prefixFoundAndStripped = true;
                    break;
                }
            }
            if (!prefixFoundAndStripped) { // No PBX prefix matched
                if (!safeMode) {
                    return ""; // PHP: $nuevo = '';
                }
                // In safeMode, if no PBX prefix matched, we continue with the original number (currentNumber)
            }
        }

        // PHP logic:
        // $primercar = substr($nuevo, 0, 1);
        // $parcial = substr($nuevo, 1);
        // if ($parcial != '' && !is_numeric($parcial)) {
        //   $parcial2 = preg_replace('/[^0-9]/','?', $parcial);
        //   $p = strpos($parcial2, '?');
        //   if ($p > 0) $parcial = substr($parcial2, 0, $p);
        // }
        // if ($primercar == '+') { $primercar = ''; }
        // $nuevo = $primercar.$parcial;

        if (currentNumber.isEmpty()) return "";

        String firstChar = currentNumber.substring(0, 1);
        String partial = currentNumber.length() > 1 ? currentNumber.substring(1) : "";

        if (!partial.isEmpty()) {
            StringBuilder digitsOnlyPartial = new StringBuilder();
            for (char c : partial.toCharArray()) {
                if (Character.isDigit(c)) {
                    digitsOnlyPartial.append(c);
                } else {
                    break; // Stop at first non-digit after the first character
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
        // Keeps digits, #, *, +
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
        String cleanedExtension = cleanPhoneNumber(extension); // Use basic clean for this check

        if (limits.getSpecialExtensions() != null && limits.getSpecialExtensions().contains(cleanedExtension)) {
            return true;
        }

        if (isNumeric(cleanedExtension) && !cleanedExtension.startsWith("0")) {
            try {
                long numericExt = Long.parseLong(cleanedExtension);
                return numericExt >= limits.getMinNumericValue() && numericExt <= limits.getMaxNumericValue() &&
                       cleanedExtension.length() >= limits.getMinLength() && cleanedExtension.length() <= limits.getMaxLength();
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
            if (diffLenTelefonoSeries < 0) {
                 return new PaddedSeriesDto((ndc != null ? ndc : "") + initial, (ndc != null ? ndc : "") + finalNum);
            }
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