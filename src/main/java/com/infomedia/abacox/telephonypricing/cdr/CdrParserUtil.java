package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CdrParserUtil {

    public static List<String> parseCsvLine(String line, String separator) {
        // Basic split, doesn't handle quoted separators well.
        // For robust CSV, a library like Apache Commons CSV or OpenCSV is recommended.
        // PHP's explode also doesn't handle CSV quoting perfectly.
        // This matches PHP's explode behavior.
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrParserUtil::cleanCsvField) // PHP doesn't auto-clean fields from explode
                .collect(Collectors.toList());
    }

    public static String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        // PHP's default CSV parsing doesn't auto-remove quotes unless specific functions are used.
        // This mimics a common manual cleaning step.
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            cleaned = cleaned.replace("\"\"", "\""); // Handle escaped quotes
        }
        return cleaned;
    }

    public static String decimalToIp(long dec) {
        if (dec < 0) { // Cisco sometimes uses -1 for unknown IPs
            return String.valueOf(dec);
        }
        // Corrected logic from PHP's dec2ip
        return String.format("%d.%d.%d.%d",
                (dec & 0xFF),
                (dec >> 8) & 0xFF,
                (dec >> 16) & 0xFF,
                (dec >> 24) & 0xFF);
    }

    public static String cleanPhoneNumber(String number, List<String> pbxExitPrefixes, boolean stripOnlyIfPrefixMatchesAndFound) {
        // Mimics PHP's limpiar_numero
        if (number == null) return "";
        String currentNumber = number.trim();
        String numberAfterPrefixStrip = currentNumber;
        boolean prefixFoundAndStripped = false;

        boolean pbxPrefixDefined = pbxExitPrefixes != null && !pbxExitPrefixes.isEmpty();

        if (pbxPrefixDefined) {
            String longestMatchingPrefix = "";
            for (String prefix : pbxExitPrefixes) {
                String trimmedPrefix = prefix.trim();
                if (!trimmedPrefix.isEmpty() && currentNumber.startsWith(trimmedPrefix)) {
                    if (trimmedPrefix.length() > longestMatchingPrefix.length()) {
                        longestMatchingPrefix = trimmedPrefix;
                    }
                }
            }
            if (!longestMatchingPrefix.isEmpty()) {
                numberAfterPrefixStrip = currentNumber.substring(longestMatchingPrefix.length());
                prefixFoundAndStripped = true;
            } else {
                // If stripOnlyIfPrefixMatchesAndFound is true, and no prefix was found, we don't strip,
                // but we also don't return "" like the PHP's $maxCaracterAExtraer == 0 case.
                // PHP: elseif ($maxCaracterAExtraer == 0) { $nuevo = ''; }
                // PHP: if ($modo_seguro && $nuevo == '') { $nuevo = trim($numero); }
                // This means if mode_seguro (stripOnlyIfPrefixMatchesAndFound) is true, and no prefix matched,
                // it uses the original number. If mode_seguro is false, and no prefix matched, it returns empty.
                if (!stripOnlyIfPrefixMatchesAndFound) { // This is PHP's !$modo_seguro
                    return ""; // No prefix found, and not in "safe mode" (must have prefix)
                }
                // If in "safe mode" and no prefix found, continue with original number.
                numberAfterPrefixStrip = currentNumber;
            }
        }
        // If pbxPrefixes is null/empty, numberAfterPrefixStrip remains currentNumber.

        if (numberAfterPrefixStrip.isEmpty() && prefixFoundAndStripped && !stripOnlyIfPrefixMatchesAndFound) {
            // This case handles when a prefix was stripped, resulting in an empty number,
            // and we are NOT in "safe mode" (meaning prefix was mandatory).
            return "";
        }


        String firstCharOriginal = numberAfterPrefixStrip.length() > 0 ? numberAfterPrefixStrip.substring(0, 1) : "";
        String restOriginal = numberAfterPrefixStrip.length() > 1 ? numberAfterPrefixStrip.substring(1) : "";

        StringBuilder cleanedRest = new StringBuilder();
        boolean nonNumericEncountered = false;
        for (char c : restOriginal.toCharArray()) {
            if (Character.isDigit(c)) {
                if (nonNumericEncountered) break; // Stop if we hit a digit after a non-digit
                cleanedRest.append(c);
            } else if (c == '#' || c == '*') {
                 if (nonNumericEncountered) break;
                cleanedRest.append(c); // Allow # and *
            }
            else {
                nonNumericEncountered = true; // Mark non-numeric found
                // PHP: $p = strpos($parcial2, '?'); if ($p > 0) { $parcial = substr($parcial2, 0, $p); }
                // This means it stops at the first non-alphanumeric (excluding #,*)
                // The current loop structure achieves this by breaking.
                break;
            }
        }
        
        if ("+".equals(firstCharOriginal)) {
            return cleanedRest.toString();
        } else {
            return firstCharOriginal + cleanedRest.toString();
        }
    }

    public static void swapPartyInfo(CdrData cdrData) {
        String tempExt = cdrData.getCallingPartyNumber();
        String tempExtPart = cdrData.getCallingPartyNumberPartition();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setFinalCalledPartyNumber(tempExt);
        cdrData.setFinalCalledPartyNumberPartition(tempExtPart);
    }

    public static void swapTrunks(CdrData cdrData) {
        String tempTrunk = cdrData.getOrigDeviceName();
        cdrData.setOrigDeviceName(cdrData.getDestDeviceName());
        cdrData.setDestDeviceName(tempTrunk);
    }
}