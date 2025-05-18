package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CdrParserUtil {

    public static List<String> parseCsvLine(String line, String separator) {
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrParserUtil::cleanCsvField)
                .collect(Collectors.toList());
    }

    public static String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            cleaned = cleaned.replace("\"\"", "\"");
        }
        return cleaned;
    }

    public static String decimalToIp(long dec) {
        if (dec < 0) return String.valueOf(dec);
        return String.format("%d.%d.%d.%d",
                (dec >> 24) & 0xff,
                (dec >> 16) & 0xff,
                (dec >> 8) & 0xff,
                dec & 0xff);
    }

    public static String cleanPhoneNumber(String number, List<String> pbxExitPrefixes, boolean stripOnlyIfPrefixMatches) {
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
                if (!stripOnlyIfPrefixMatches) {
                    return "";
                }
                numberAfterPrefixStrip = currentNumber;
            }
        }

        if (numberAfterPrefixStrip.isEmpty()) {
            return "";
        }

        String firstCharOriginal = numberAfterPrefixStrip.substring(0, 1);
        String restOriginal = numberAfterPrefixStrip.length() > 1 ? numberAfterPrefixStrip.substring(1) : "";

        StringBuilder cleanedRest = new StringBuilder();
        for (char c : restOriginal.toCharArray()) {
            if (Character.isDigit(c) || c == '#' || c == '*') {
                cleanedRest.append(c);
            } else {
                break;
            }
        }
        
        // PHP's limpiar_numero removes leading '+' at the very end of its logic.
        // If firstCharOriginal was '+', it's effectively dropped if the rest is empty or becomes the prefix to cleanedRest.
        if ("+".equals(firstCharOriginal)) {
            // If PHP removes it, we should too. PHP: if ($primercar == '+') { $primercar = ''; }
            // This happens AFTER $parcial (cleanedRest) is processed.
            // So, if it was "+123", $primercar becomes "" and $parcial is "123", result "123".
            // If it was "+", $primercar becomes "" and $parcial is "", result "".
            return cleanedRest.toString();
        } else {
            // If not '+', the first char is kept, and the cleaned rest is appended.
            return firstCharOriginal + cleanedRest.toString();
        }
    }
}