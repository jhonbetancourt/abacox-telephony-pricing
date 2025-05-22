// File: com/infomedia/abacox/telephonypricing/cdr/CdrParserUtil.java
package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class CdrUtil {

    public static List<String> parseCsvLine(String line, String separator) {
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrUtil::cleanCsvField)
                .collect(Collectors.toList());
    }

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

    public static String decimalToIp(long dec) {
        if (dec < 0) {
            log.warn("Received negative decimal for IP conversion: {}, returning as string.", dec);
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
     * @param number The number string to clean.
     * @param pbxExitPrefixes List of PBX exit prefixes to attempt to remove.
     * @param stripOnlyIfPrefixMatchesAndFound If true, and pbxExitPrefixes are provided but none match,
     *                                         the original number (after basic cleaning) is returned.
     *                                         If false, and pbxExitPrefixes are provided but none match,
     *                                         an empty string is returned (PHP's $maxCaracterAExtraer == 0 case).
     *                                         If pbxExitPrefixes is null/empty, this flag has less impact on prefix stripping.
     * @return The cleaned number.
     */
    public static String cleanPhoneNumber(String number, List<String> pbxExitPrefixes, boolean stripOnlyIfPrefixMatchesAndFound) {
        if (number == null) return "";
        String currentNumber = number.trim();
        log.trace("Cleaning phone number: '{}', PBX Prefixes: {}, StripOnlyIfFound: {}", number, pbxExitPrefixes, stripOnlyIfPrefixMatchesAndFound);

        String numberAfterPrefixStrip = currentNumber;
        boolean pbxPrefixDefined = pbxExitPrefixes != null && !pbxExitPrefixes.isEmpty();
        boolean pbxPrefixFoundAndStripped = false;

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
                pbxPrefixFoundAndStripped = true;
                log.trace("PBX prefix '{}' stripped. Number is now: '{}'", longestMatchingPrefix, numberAfterPrefixStrip);
            } else {
                // PHP: elseif ($maxCaracterAExtraer == 0) { $nuevo = ''; }
                // This means if prefixes were defined but none matched, PHP returns empty.
                // This corresponds to stripOnlyIfPrefixMatchesAndFound = false.
                if (!stripOnlyIfPrefixMatchesAndFound) {
                    log.trace("PBX prefixes defined but none matched, and stripOnlyIfPrefixMatchesAndFound is false. Returning empty.");
                    return "";
                }
                // If stripOnlyIfPrefixMatchesAndFound is true, we continue with currentNumber (or numberAfterPrefixStrip which is same).
                log.trace("PBX prefixes defined but none matched, stripOnlyIfPrefixMatchesAndFound is true. Continuing with: '{}'", numberAfterPrefixStrip);
            }
        }

        // PHP: if ($modo_seguro && $nuevo == '') { $nuevo = trim($numero); }
        // This PHP logic is a bit confusing. If modo_seguro (stripOnlyIfPrefixMatchesAndFound) is true
        // AND the number became empty after trying to strip (meaning a prefix was defined but didn't match,
        // and stripOnlyIfPrefixMatchesAndFound was false, leading to empty), then it reverts to original.
        // Our logic above handles this: if stripOnlyIfPrefixMatchesAndFound is true and no prefix matched,
        // numberAfterPrefixStrip remains the original (trimmed) number.
        // If stripOnlyIfPrefixMatchesAndFound is false and no prefix matched, it already returned "".

        if (numberAfterPrefixStrip.isEmpty() && pbxPrefixFoundAndStripped) {
            // If stripping the prefix resulted in an empty string, it's considered empty.
            log.trace("Number became empty after stripping prefix. Returning empty.");
            return "";
        }
        if (numberAfterPrefixStrip.isEmpty() && !pbxPrefixFoundAndStripped && pbxPrefixDefined && !stripOnlyIfPrefixMatchesAndFound) {
            // This case should have been caught by `return ""` above if no prefix matched and !stripOnlyIfPrefixMatchesAndFound
            log.trace("Number is empty, no prefix was stripped but prefixes were defined and !stripOnlyIfPrefixMatchesAndFound. Returning empty.");
            return "";
        }


        String numToClean = numberAfterPrefixStrip;
        if (numToClean.startsWith("+")) {
            numToClean = numToClean.substring(1);
            log.trace("Stripped leading '+'. Number is now: '{}'", numToClean);
        }

        if (numToClean.isEmpty()) {
            log.trace("Number is empty after stripping '+'. Returning empty.");
            return "";
        }

        // PHP: $primercar = substr($nuevo, 0, 1); $parcial = substr($nuevo, 1);
        // PHP: if ($parcial != '' && !is_numeric($parcial)) { $parcial2 = preg_replace('/[^0-9]/','?', $parcial); ... }
        // This means it only cleans non-digits *after the first character*.
        String firstChar = String.valueOf(numToClean.charAt(0));
        String restOfNumber = numToClean.substring(1);
        StringBuilder cleanedRest = new StringBuilder();
        for (char c : restOfNumber.toCharArray()) {
            if (Character.isDigit(c)) {
                cleanedRest.append(c);
            } else {
                // PHP: $p = strpos($parcial2, '?'); if ($p > 0) { $parcial = substr($parcial2, 0, $p); }
                // This means it stops at the first non-digit *after the first character*.
                log.trace("Non-digit '{}' found after first char. Stopping cleaning of rest.", c);
                break;
            }
        }
        String finalCleanedNumber = firstChar + cleanedRest.toString();
        log.debug("Cleaned phone number result: '{}'", finalCleanedNumber);
        return finalCleanedNumber;
    }


    public static void swapPartyInfo(CdrData cdrData) {
        log.debug("Swapping party info. Before: Calling='{}'({}), FinalCalled='{}'({})",
                cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());
        String tempExt = cdrData.getCallingPartyNumber();
        String tempExtPart = cdrData.getCallingPartyNumberPartition();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setFinalCalledPartyNumber(tempExt);
        cdrData.setFinalCalledPartyNumberPartition(tempExtPart);
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber()); // Update effective destination
        log.debug("Swapped party info. After: Calling='{}'({}), FinalCalled='{}'({})",
                cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());
    }

    public static void swapTrunks(CdrData cdrData) {
        log.debug("Swapping trunks. Before: Orig='{}', Dest='{}'", cdrData.getOrigDeviceName(), cdrData.getDestDeviceName());
        String tempTrunk = cdrData.getOrigDeviceName();
        cdrData.setOrigDeviceName(cdrData.getDestDeviceName());
        cdrData.setDestDeviceName(tempTrunk);
        log.debug("Swapped trunks. After: Orig='{}', Dest='{}'", cdrData.getOrigDeviceName(), cdrData.getDestDeviceName());
    }

    public static String generateCtlHash(String cdrString, Long commLocationId) {
        String ctlHashContent = (commLocationId != null ? commLocationId : "0") + "@" + (cdrString != null ? cdrString : "");
        return HashUtil.sha256(ctlHashContent);
    }
}