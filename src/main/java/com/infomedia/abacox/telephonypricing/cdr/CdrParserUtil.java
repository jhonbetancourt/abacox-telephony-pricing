// File: com/infomedia/abacox/telephonypricing/cdr/CdrParserUtil.java
package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CdrParserUtil {

    public static List<String> parseCsvLine(String line, String separator) {
        // PHP's explode behavior:
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrParserUtil::cleanCsvField)
                .collect(Collectors.toList());
    }

    public static String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        // Mimic PHP's str_replace(chr(0),'',$string);
        cleaned = cleaned.replace("\u0000", ""); // Remove NULL characters

        // PHP's default CSV parsing doesn't auto-remove quotes unless specific functions are used.
        // This mimics a common manual cleaning step if quotes are present.
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            cleaned = cleaned.replace("\"\"", "\""); // Handle escaped quotes like ""
        }
        return cleaned;
    }

    /**
     * PHP equivalent: dec2ip
     */
    public static String decimalToIp(long dec) {
        if (dec < 0) { // Cisco sometimes uses -1 for unknown IPs
            return String.valueOf(dec); // Or handle as "UNKNOWN_IP"
        }
        // Corrected logic from PHP's dec2ip
        // (dec >> 24) & 0xFF, (dec >> 16) & 0xFF, (dec >> 8) & 0xFF, dec & 0xFF for big-endian
        // PHP's array_reverse implies little-endian interpretation of the hex bytes
        return String.format("%d.%d.%d.%d",
                (dec & 0xFF),
                (dec >> 8) & 0xFF,
                (dec >> 16) & 0xFF,
                (dec >> 24) & 0xFF);
    }

    /**
     * PHP equivalent: limpiar_numero
     */
    public static String cleanPhoneNumber(String number, List<String> pbxExitPrefixes, boolean stripOnlyIfPrefixMatchesAndFound) {
        if (number == null) return "";
        String currentNumber = number.trim();
        String numberAfterPrefixStrip = currentNumber;

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
            } else {
                // No PBX prefix matched
                if (!stripOnlyIfPrefixMatchesAndFound) { // PHP: $maxCaracterAExtraer == 0 (means prefix defined but not found)
                    return ""; // Must have prefix, but none found
                }
                // If stripOnlyIfPrefixMatchesAndFound (PHP: $modo_seguro) is true, and no prefix matched,
                // it continues with the original number (numberAfterPrefixStrip is still currentNumber).
            }
        }
        // If pbxPrefixes is null/empty, or if stripOnlyIfPrefixMatchesAndFound is true and no prefix matched,
        // numberAfterPrefixStrip is effectively currentNumber.

        // PHP: $primercar = substr($nuevo, 0, 1); $parcial = substr($nuevo, 1);
        // PHP: if ($parcial != '' && !is_numeric($parcial)) ...
        // PHP: $parcial2 = preg_replace('/[^0-9]/','?', $parcial);
        // PHP: $p = strpos($parcial2, '?'); if ($p > 0) { $parcial = substr($parcial2, 0, $p); }
        // PHP: if ($primercar == '+') { $primercar = ''; }
        // PHP: $nuevo = $primercar.$parcial;

        if (numberAfterPrefixStrip.isEmpty()) {
            return ""; // If stripping prefix resulted in empty, return empty.
        }

        StringBuilder result = new StringBuilder();
        boolean firstCharProcessed = false;

        for (int i = 0; i < numberAfterPrefixStrip.length(); i++) {
            char c = numberAfterPrefixStrip.charAt(i);
            if (!firstCharProcessed) {
                firstCharProcessed = true;
                if (c == '+') {
                    continue; // Skip leading '+'
                }
                result.append(c);
            } else {
                // For subsequent characters, stop at the first non-digit (excluding allowed symbols if any)
                // PHP logic stops at first non-digit in the "parcial" part.
                if (Character.isDigit(c) || c == '#' || c == '*') { // Allow digits, #, *
                    result.append(c);
                } else {
                    break; // Stop at the first invalid character after the first char
                }
            }
        }
        return result.toString();
    }

    /**
     * PHP equivalent: _invertir (for party info)
     */
    public static void swapPartyInfo(CdrData cdrData) {
        String tempExt = cdrData.getCallingPartyNumber();
        String tempExtPart = cdrData.getCallingPartyNumberPartition();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setFinalCalledPartyNumber(tempExt);
        cdrData.setFinalCalledPartyNumberPartition(tempExtPart);

        // PHP: if (isset($info['destino'])) { $info['destino'] = ''; }
        // This implies effectiveDestinationNumber might need reset if it was based on the old finalCalledPartyNumber
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // Reset based on new final
    }

    /**
     * PHP equivalent: _invertir (for trunks)
     */
    public static void swapTrunks(CdrData cdrData) {
        String tempTrunk = cdrData.getOrigDeviceName();
        cdrData.setOrigDeviceName(cdrData.getDestDeviceName());
        cdrData.setDestDeviceName(tempTrunk);
    }
}