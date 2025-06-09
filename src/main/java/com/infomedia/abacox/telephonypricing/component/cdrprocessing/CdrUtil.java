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
        if (line == null) return Arrays.asList("");
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
        }
        return cleaned;
    }

    public static String decimalToIp(long dec) {
        if (dec < 0) {
            log.trace("Received negative decimal for IP conversion: {}, returning as string.", dec);
            return String.valueOf(dec);
        }
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
        int phpMaxCaracterAExtraer = -1;

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

    public static void swapPartyInfo(CdrData cdrData) {
        log.debug("Performing FULL swap of party info. Before: Calling='{}'({}), FinalCalled='{}'({})",
                cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());

        String tempExt = cdrData.getCallingPartyNumber();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setFinalCalledPartyNumber(tempExt);

        String tempExtPart = cdrData.getCallingPartyNumberPartition();
        cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setFinalCalledPartyNumberPartition(tempExtPart);

        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());

        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        log.debug("FULL swap complete. After: Calling='{}'({}), FinalCalled='{}'({})",
                cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());
    }

    /**
     * ADJUSTMENT: New method for partial swap.
     * Performs a PARTIAL swap, exchanging only the calling and called numbers.
     * Partitions and trunks are NOT affected. This is for the non-conference incoming detection case.
     * @param cdrData The CdrData object to modify.
     */
    public static void swapPartyNumbersOnly(CdrData cdrData) {
        log.debug("Performing PARTIAL swap of party numbers only. Before: Calling='{}', FinalCalled='{}'",
                cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());

        String tempExt = cdrData.getCallingPartyNumber();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setFinalCalledPartyNumber(tempExt);

        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());

        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        log.debug("PARTIAL swap complete. After: Calling='{}', FinalCalled='{}'",
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