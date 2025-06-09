// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrUtil.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        if (line == null) return Arrays.asList(""); // Match PHP behavior of returning array with one empty string for null input
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrUtil::cleanCsvField) // PHP's CM_ValidarCab calls csv_limpiar_campos on each field
                .collect(Collectors.toList());
    }

    public static String cleanCsvField(String field) {
        if (field == null) return "";
        String cleaned = field.trim();
        cleaned = cleaned.replace("\u0000", ""); // Remove NULL characters

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            // PHP doesn't seem to handle escaped quotes like "" -> " inside fields with this simple logic.
        }
        return cleaned;
    }

    public static String decimalToIp(long dec) {
        if (dec < 0) {
            log.trace("Received negative decimal for IP conversion: {}, returning as string.", dec);
            return String.valueOf(dec);
        }
        // This is standard little-endian to dotted-quad if the decimal was stored little-endian.
        return String.format("%d.%d.%d.%d",
                (dec & 0xFF),
                (dec >> 8) & 0xFF,
                (dec >> 16) & 0xFF,
                (dec >> 24) & 0xFF);
    }


    public static CleanPhoneNumberResult cleanPhoneNumber(String number, List<String> pbxExitPrefixes, boolean modoSeguro) {
        if (number == null) {
            return new CleanPhoneNumberResult("", false);
        }
        String currentNumber = number.trim();
        log.trace("Cleaning phone number: '{}', PBX Prefixes: {}, ModoSeguro: {}", number, pbxExitPrefixes, modoSeguro);

        String numberAfterPrefixStrip = currentNumber;
        boolean pbxPrefixWasStripped = false;

        boolean pbxPrefixDefined = pbxExitPrefixes != null && !pbxExitPrefixes.isEmpty();
        int phpMaxCaracterAExtraer = -1; // -1: no prefixes, 0: no match, >0: match

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
                phpMaxCaracterAExtraer = longestMatchingPrefix.length();
                pbxPrefixWasStripped = true;
                log.trace("PBX prefix '{}' stripped. Number is now: '{}'", longestMatchingPrefix, numberAfterPrefixStrip);
            } else {
                phpMaxCaracterAExtraer = 0;
                log.trace("PBX prefixes defined but none matched current number '{}'", currentNumber);
            }
        }

        if (phpMaxCaracterAExtraer == 0) {
            numberAfterPrefixStrip = "";
        }

        if (modoSeguro && numberAfterPrefixStrip.isEmpty() && phpMaxCaracterAExtraer == 0) {
            numberAfterPrefixStrip = currentNumber;
            log.trace("ModoSeguro is true and no PBX prefix matched, reverting to original for further cleaning: '{}'", numberAfterPrefixStrip);
        }

        String numToClean = numberAfterPrefixStrip;

        if (numToClean.startsWith("+")) {
            numToClean = numToClean.substring(1);
            log.trace("Stripped leading '+'. Number is now: '{}'", numToClean);
        }

        if (numToClean.isEmpty()) {
            log.trace("Number is empty after initial cleaning. Returning empty.");
            return new CleanPhoneNumberResult("", pbxPrefixWasStripped);
        }

        String firstChar = String.valueOf(numToClean.charAt(0));
        String restOfNumber = numToClean.length() > 1 ? numToClean.substring(1) : "";
        StringBuilder cleanedRest = new StringBuilder();

        if (!restOfNumber.isEmpty()) {
            for (char c : restOfNumber.toCharArray()) {
                if (Character.isDigit(c)) {
                    cleanedRest.append(c);
                } else {
                    log.trace("Non-digit '{}' found after first char. Stopping cleaning of rest.", c);
                    break;
                }
            }
        }

        String finalCleanedNumber = firstChar + cleanedRest.toString();
        log.debug("Cleaned phone number result: '{}', Prefix Stripped: {}", finalCleanedNumber, pbxPrefixWasStripped);
        return new CleanPhoneNumberResult(finalCleanedNumber, pbxPrefixWasStripped);
    }


    /**
     * Swaps all party information: numbers and partitions.
     * Used for conference calls where the entire identity of caller/callee is inverted.
     */
    public static void swapPartyInfo(CdrData cdrData) {
        log.debug("Swapping full party info (numbers and partitions). Before: Calling='{}'({}), FinalCalled='{}'({})",
                cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());

        // Swap numbers
        String tempExt = cdrData.getCallingPartyNumber();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setFinalCalledPartyNumber(tempExt);

        // Swap partitions
        String tempExtPart = cdrData.getCallingPartyNumberPartition();
        cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setFinalCalledPartyNumberPartition(tempExtPart);

        // Align original fields for persistence
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());

        // Update the effective destination number to reflect the swap
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        log.debug("Swapped full party info. After: Calling='{}'({}), FinalCalled='{}'({})",
                cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());
    }

    /**
     * Swaps only the party numbers (calling and final called), leaving partitions untouched.
     * This is used for non-conference incoming calls where the external number becomes the caller
     * and our extension becomes the callee, but their original partitions (or lack thereof) are preserved.
     */
    public static void swapPartyNumbersOnly(CdrData cdrData) {
        log.debug("Swapping party numbers ONLY. Before: Calling='{}', FinalCalled='{}'",
                cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());

        // Swap numbers
        String tempExt = cdrData.getCallingPartyNumber();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setFinalCalledPartyNumber(tempExt);

        // Align original fields for persistence
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());

        // Update the effective destination number to reflect the swap
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        log.debug("Swapped party numbers ONLY. After: Calling='{}', FinalCalled='{}'",
                cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());
    }


    public static void swapTrunks(CdrData cdrData) {
        log.debug("Swapping trunks. Before: Orig='{}', Dest='{}'", cdrData.getOrigDeviceName(), cdrData.getDestDeviceName());
        String tempTrunk = cdrData.getOrigDeviceName();
        cdrData.setOrigDeviceName(cdrData.getDestDeviceName());
        cdrData.setDestDeviceName(tempTrunk);
        log.debug("Swapped trunks. After: Orig='{}', Dest='{}'", cdrData.getOrigDeviceName(), cdrData.getDestDeviceName());
    }

    public static String generateCtlHash(String cdrString, Long commLocationId) {
        // PHP: $cdr5 = sha256($comubicacion_id.'@'.$cdr);
        String ctlHashContent = (commLocationId != null ? commLocationId.toString() : "0") + "@" + (cdrString != null ? cdrString : "");
        return sha256(ctlHashContent);
    }

    public static String sha256(String base) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }


    public static boolean isPossibleExtension(String extensionNumber, ExtensionLimits limits) {
        if (limits == null || extensionNumber == null || extensionNumber.isEmpty()) {
            return false;
        }
        String cleanedExt = CdrUtil.cleanPhoneNumber(extensionNumber, null, false).getCleanedNumber();
        if (cleanedExt.startsWith("+")) cleanedExt = cleanedExt.substring(1);

        if (limits.getSpecialFullExtensions() != null && limits.getSpecialFullExtensions().contains(cleanedExt)) {
            return true;
        }

        boolean phpExtensionValidaForNumericRange = (!cleanedExt.startsWith("0") || cleanedExt.equals("0")) &&
                cleanedExt.matches("\\d+");

        if (phpExtensionValidaForNumericRange) {
            try {
                long extNumValue = Long.parseLong(cleanedExt);
                return extNumValue >= limits.getMinLength() && extNumValue <= limits.getMaxLength();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}