// File: com/infomedia/abacox/telephonypricing/cdr/CdrParserUtil.java
package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CdrParserUtil {

    /**
     * PHP equivalent: explode($cm_config['cdr_separador'], $linea);
     * and csv_limpiar_campos()
     */
    public static List<String> parseCsvLine(String line, String separator) {
        // PHP's explode behavior:
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrParserUtil::cleanCsvField)
                .collect(Collectors.toList());
    }

    /**
     * PHP equivalent: csv_limpiar_campos($cab); // Elimina comillas
     * and str_replace(chr(0),'',$string);
     */
    public static String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        cleaned = cleaned.replace("\u0000", "");

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            cleaned = cleaned.replace("\"\"", "\"");
        }
        return cleaned;
    }

    /**
     * PHP equivalent: dec2ip
     */
    public static String decimalToIp(long dec) {
        if (dec < 0) {
            return String.valueOf(dec);
        }
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
                if (!stripOnlyIfPrefixMatchesAndFound) {
                    return "";
                }
            }
        }

        if (numberAfterPrefixStrip.isEmpty()) {
            return "";
        }

        String numToClean = numberAfterPrefixStrip;
        if (numToClean.startsWith("+")) {
            numToClean = numToClean.substring(1);
        }

        if (numToClean.isEmpty()) return "";

        String firstChar = String.valueOf(numToClean.charAt(0));
        String partial = numToClean.substring(1);

        if (!partial.isEmpty()) {
            int firstNonDigitInPartial = -1;
            for (int i = 0; i < partial.length(); i++) {
                if (!Character.isDigit(partial.charAt(i))) {
                    firstNonDigitInPartial = i;
                    break;
                }
            }

            if (firstNonDigitInPartial != -1) { // A non-digit was found
                if (firstNonDigitInPartial > 0) { // PHP: if ($p > 0)
                    partial = partial.substring(0, firstNonDigitInPartial);
                }
                // If firstNonDigitInPartial is 0 (PHP: $p == 0), PHP's $p > 0 is false, so $parcial is not changed.
                // This means if the first char of 'partial' is non-digit, 'partial' remains as is.
            }
            // If firstNonDigitInPartial is -1 (all digits), 'partial' is not changed.
        }
        return firstChar + partial;
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
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());
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