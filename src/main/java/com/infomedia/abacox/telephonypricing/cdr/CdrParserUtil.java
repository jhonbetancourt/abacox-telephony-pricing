package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CdrParserUtil {

    // Basic CSV split, does not handle commas within quoted fields robustly.
    // Cisco CDRs are typically simple comma-separated without complex quoting.
    public static List<String> parseCsvLine(String line, String separator) {
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrParserUtil::cleanCsvField)
                .collect(Collectors.toList());
    }

    public static String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        // Remove surrounding quotes if present (simple case)
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            // Replace double double-quotes with a single double-quote
            cleaned = cleaned.replace("\"\"", "\"");
        }
        return cleaned;
    }

    public static String decimalToIp(long dec) {
        if (dec < 0) return String.valueOf(dec); // Or handle error
        return String.format("%d.%d.%d.%d",
                (dec >> 24) & 0xff,
                (dec >> 16) & 0xff,
                (dec >> 8) & 0xff,
                dec & 0xff);
    }

    // Simplified check, real systems might have complex rules
    public static boolean isExtension(String number) {
        if (number == null || number.isEmpty()) return false;
        // PHP's ExtensionPosible checks against configured min/max lengths and special lists.
        // For now, a simple length check.
        // This should ideally use AppConfigurationService or a dedicated ExtensionRuleService.
        return number.matches("\\d{3,7}"); // Example: 3 to 7 digits
    }

    public static String cleanPhoneNumber(String number, List<String> pbxExitPrefixes, boolean stripOnlyIfPrefixMatches) {
        if (number == null) return "";
        String cleanedNumber = number.trim();

        if (pbxExitPrefixes != null && !pbxExitPrefixes.isEmpty()) {
            boolean prefixMatched = false;
            for (String prefix : pbxExitPrefixes) {
                if (!prefix.isEmpty() && cleanedNumber.startsWith(prefix)) {
                    cleanedNumber = cleanedNumber.substring(prefix.length());
                    prefixMatched = true;
                    break;
                }
            }
            if (stripOnlyIfPrefixMatches && !prefixMatched) {
                // If mode is to strip only if prefix matches, and none matched, return original (or empty if it was only prefix)
                // The PHP logic for `modo_seguro = true` is: if (maxCaracterAExtraer == 0) nuevo = ''; if (modo_seguro && nuevo == '') nuevo = trim(numero);
                // This means if a prefix was defined but not found, it would return empty unless modo_seguro, then original.
                // If no prefix matched, and modo_seguro is true, we should return the original number before attempting prefix removal.
                // However, the current loop structure already returns the number if no prefix matches.
                // The PHP `limpiar_numero` with `modo_seguro = true` and `maxCaracterAExtraer == 0` (prefix defined but not found)
                // would effectively return the original number after non-numeric stripping.
                // If `maxCaracterAExtraer > 0` (prefix found and stripped), it proceeds.
                // If `maxCaracterAExtraer == -1` (no PBX prefix defined), it proceeds with original.
                // The current Java logic: if prefix matched, it's stripped. If not, `cleanedNumber` is original.
                // This seems to align with `modo_seguro = true` for the prefix part.
            }
        }

        // Remove non-numeric characters except for leading + (for E.164)
        // PHP: $parcial = preg_replace('/[^0-9]/','?', $parcial); ... $p = strpos($parcial2, '?');
        // This means it keeps digits until the first non-digit.
        StringBuilder numericPart = new StringBuilder();
        boolean firstChar = true;
        for (char c : cleanedNumber.toCharArray()) {
            if (Character.isDigit(c)) {
                numericPart.append(c);
            } else if (c == '+' && firstChar && numericPart.length() == 0) {
                numericPart.append(c); // Allow leading +
            } else if (c == '#' || c == '*') {
                numericPart.append(c); // Allow # and * as per PHP
            } else {
                // PHP logic: if (strpos($parcial, '?') > 0) $parcial = substr($parcial2, 0, $p);
                // This means it stops at the first non-digit (after potential leading +).
                // The PHP code is a bit more complex with $primercar and $parcial.
                // Let's simplify: keep only digits, leading +, #, *
                // The PHP logic `preg_replace('/[^0-9]/','?', $parcial)` and then `strpos` effectively
                // truncates at the first non-digit *after the first character*.
                // If we want to be very precise:
                if (numericPart.length() > 0 && !firstChar) break; // Stop if not first char and non-digit
            }
            if (numericPart.length() > 0) firstChar = false;
        }

        // More precise PHP logic for stripping non-numerics after first char:
        if (cleanedNumber.length() > 0) {
            String firstCharStr = cleanedNumber.substring(0, 1);
            String restOfNumber = cleanedNumber.length() > 1 ? cleanedNumber.substring(1) : "";

            Pattern nonNumericPattern = Pattern.compile("[^0-9]");
            Matcher matcher = nonNumericPattern.matcher(restOfNumber);
            if (matcher.find()) {
                restOfNumber = restOfNumber.substring(0, matcher.start());
            }
            cleanedNumber = firstCharStr + restOfNumber;
            if ("+".equals(firstCharStr) && restOfNumber.isEmpty()) { // if it was just "+"
                // cleanedNumber = ""; // or handle as invalid based on rules
            } else if ("+".equals(firstCharStr)) {
                // keep it as is
            } else if (!Character.isDigit(firstCharStr.charAt(0)) && firstCharStr.charAt(0) != '#' && firstCharStr.charAt(0) != '*') {
                // If first char is not digit, #, or *, and not +, then it's likely invalid or needs special handling
                // For now, the restOfNumber logic would have cleaned it.
                // The PHP code `if ($primercar == '+') { $primercar = ''; }` then ` $nuevo = $primercar.$parcial;`
                // This means '+' is removed if it's the very first char before non-numeric stripping.
                // Our current logic keeps leading '+'.
            }
        }


        return numericPart.toString();
    }
}
