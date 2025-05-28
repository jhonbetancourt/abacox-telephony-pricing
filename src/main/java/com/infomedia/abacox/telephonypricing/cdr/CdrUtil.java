// File: com/infomedia/abacox/telephonypricing/cdr/CdrUtil.java
package com.infomedia.abacox.telephonypricing.cdr;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class CdrUtil {

    public static List<String> parseCsvLine(String line, String separator) {
        // PHP's csv_datos is more robust than a simple split, handling quoted fields.
        // A proper CSV parsing library would be better, but for now, mimic simple split and clean.
        // The provided PHP doesn't show a complex csv_datos, so simple split + clean is assumed.
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrUtil::cleanCsvField)
                .collect(Collectors.toList());
    }

    public static String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        // PHP: $string = str_replace(chr(0),'',$string); (in txt2dbv8.php)
        cleaned = cleaned.replace("\u0000", ""); // Remove NULL characters

        // PHP's csv_limpiar_campos (called by CM_ValidarCab) removes surrounding quotes
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            // PHP doesn't seem to handle escaped quotes like "" -> " inside fields with this simple logic.
            // A full CSV parser would.
        }
        return cleaned;
    }

    public static String decimalToIp(long dec) {
        // PHP: dec2ip
        if (dec < 0) { // Cisco sometimes uses -1 for unknown IPs
            log.warn("Received negative decimal for IP conversion: {}, returning as string.", dec);
            return String.valueOf(dec); // Or handle as invalid
        }
        // Corrected order based on PHP's array_reverse and hexdec logic
        return String.format("%d.%d.%d.%d",
                (dec >> 24) & 0xFF,
                (dec >> 16) & 0xFF,
                (dec >> 8) & 0xFF,
                (dec & 0xFF));
    }

    /**
     * PHP equivalent: limpiar_numero
     * @param number The number string to clean.
     * @param pbxExitPrefixes List of PBX exit prefixes to attempt to remove.
     * @param modoSeguro Corresponds to PHP's $modo_seguro.
     *                   If true, and pbxExitPrefixes are provided but none match,
     *                   the original number (after basic cleaning) is returned.
     *                   If false, and pbxExitPrefixes are provided but none match,
     *                   an empty string is returned (PHP's $maxCaracterAExtraer == 0 case).
     * @return The cleaned number.
     */
    public static String cleanPhoneNumber(String number, List<String> pbxExitPrefixes, boolean modoSeguro) {
        if (number == null) return "";
        String currentNumber = number.trim();
        log.trace("Cleaning phone number: '{}', PBX Prefixes: {}, ModoSeguro: {}", number, pbxExitPrefixes, modoSeguro);

        String numberAfterPrefixStrip = currentNumber;
        boolean pbxPrefixDefined = pbxExitPrefixes != null && !pbxExitPrefixes.isEmpty();
        int maxCaracterAExtraerPhpEquivalent = -1; // -1: no prefixes or not found yet, 0: prefixes defined but none matched, >0: length of matched prefix

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
                maxCaracterAExtraerPhpEquivalent = longestMatchingPrefix.length();
                log.trace("PBX prefix '{}' stripped. Number is now: '{}'", longestMatchingPrefix, numberAfterPrefixStrip);
            } else {
                maxCaracterAExtraerPhpEquivalent = 0; // Prefixes defined, but none matched
                log.trace("PBX prefixes defined but none matched current number '{}'", currentNumber);
            }
        }

        // PHP: if ($maxCaracterAExtraer == 0) { $nuevo = ''; }
        if (maxCaracterAExtraerPhpEquivalent == 0) {
            numberAfterPrefixStrip = "";
        }

        // PHP: if ($modo_seguro && $nuevo == '') { $nuevo = trim($numero); }
        if (modoSeguro && numberAfterPrefixStrip.isEmpty() && maxCaracterAExtraerPhpEquivalent == 0) {
            // This case in PHP means: modo_seguro is true, prefixes were defined but none matched (so $nuevo became empty).
            // In this specific scenario, PHP reverts to the original number for further cleaning.
            numberAfterPrefixStrip = currentNumber; // Revert to original (trimmed) number
            log.trace("ModoSeguro is true and no PBX prefix matched (or resulted in empty after strip), reverting to original for further cleaning: '{}'", numberAfterPrefixStrip);
        }
        // If modo_seguro is false and no prefix matched (maxCaracterAExtraerPhpEquivalent == 0), numberAfterPrefixStrip is already "" and stays "".

        String numToClean = numberAfterPrefixStrip;

        // PHP: $primercar = substr($nuevo, 0, 1);
        // PHP: if ($primercar == '+') { $primercar = ''; } (NOV/2017)
        if (numToClean.startsWith("+")) {
            numToClean = numToClean.substring(1);
            log.trace("Stripped leading '+'. Number is now: '{}'", numToClean);
        }

        if (numToClean.isEmpty()) {
            log.trace("Number is empty after initial cleaning. Returning empty.");
            return "";
        }

        // PHP: $parcial = substr($nuevo, 1);
        // PHP: if ($parcial != '' && !is_numeric($parcial)) { $parcial2 = preg_replace('/[^0-9]/','?', $parcial); ... $p = strpos($parcial2, '?'); if ($p > 0) { $parcial = substr($parcial2, 0, $p); } }
        // This means it only cleans non-digits *after the first character*.
        // And it stops at the *first* non-digit encountered after the first character.
        String firstChar = String.valueOf(numToClean.charAt(0));
        String restOfNumber = numToClean.length() > 1 ? numToClean.substring(1) : "";
        StringBuilder cleanedRest = new StringBuilder();

        if (!restOfNumber.isEmpty()) {
            boolean allDigitsSoFar = true;
            for (char c : restOfNumber.toCharArray()) {
                if (Character.isDigit(c)) {
                    cleanedRest.append(c);
                } else {
                    allDigitsSoFar = false;
                    log.trace("Non-digit '{}' found after first char. Stopping cleaning of rest.", c);
                    break; // Stop at the first non-digit after the first character
                }
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
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());
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