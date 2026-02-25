// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrUtil.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class CdrUtil {

    private static final int PROBE_LINE_COUNT = 5; // Read the first few lines for validation

    public static List<String> parseCsvLine(String line, String separator) {
        if (line == null)
            return Arrays.asList("");
        return Arrays.stream(line.split(Pattern.quote(separator)))
                .map(CdrUtil::cleanCsvField)
                .collect(Collectors.toList());
    }

    public static String cleanCsvField(String field) {
        if (field == null)
            return "";
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

    public static CleanPhoneNumberResult cleanPhoneNumber(String number, List<String> pbxExitPrefixes,
            boolean modoSeguro) {
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
                log.trace("PBX prefix '{}' stripped. Number is now: '{}'", longestMatchingPrefix,
                        numberAfterPrefixStrip);
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
            log.trace("ModoSeguro is true and no PBX prefix matched, reverting to original for further cleaning: '{}'",
                    numberAfterPrefixStrip);
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
     * Performs a complete swap of calling/called party information, including
     * numbers, partitions,
     * and optionally trunks. This replicates the full logic of PHP's
     * `InvertirLlamada`.
     *
     * @param cdrData    The CdrData object to modify.
     * @param swapTrunks If true, swaps origDeviceName and destDeviceName.
     */
    public static void swapFull(CdrData cdrData, boolean swapTrunks) {
        log.debug("Performing FULL swap. SwapTrunks: {}. Before: Calling='{}'({}), FinalCalled='{}'({})",
                swapTrunks, cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());

        // Swap numbers
        String tempExt = cdrData.getCallingPartyNumber();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setFinalCalledPartyNumber(tempExt);

        // Swap partitions
        String tempExtPart = cdrData.getCallingPartyNumberPartition();
        cdrData.setCallingPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setFinalCalledPartyNumberPartition(tempExtPart);

        // Update "original" and "effective" fields to reflect the new state
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setOriginalFinalCalledPartyNumberPartition(cdrData.getFinalCalledPartyNumberPartition());
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        if (swapTrunks) {
            swapTrunks(cdrData);
        }

        log.debug("FULL swap complete. After: Calling='{}'({}), FinalCalled='{}'({})",
                cdrData.getCallingPartyNumber(), cdrData.getCallingPartyNumberPartition(),
                cdrData.getFinalCalledPartyNumber(), cdrData.getFinalCalledPartyNumberPartition());
    }

    /**
     * Performs a PARTIAL swap, exchanging only the calling and called numbers.
     * Partitions and trunks are NOT affected. This is for the non-conference
     * incoming detection case.
     * 
     * @param cdrData The CdrData object to modify.
     */
    public static void swapPartyNumbersOnly(CdrData cdrData) {
        log.debug("Performing PARTIAL swap of party numbers only. Before: Calling='{}', FinalCalled='{}'",
                cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());

        String tempExt = cdrData.getCallingPartyNumber();
        cdrData.setCallingPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setFinalCalledPartyNumber(tempExt);

        // Update "original" and "effective" fields to reflect the new state
        cdrData.setOriginalFinalCalledPartyNumber(cdrData.getFinalCalledPartyNumber());
        cdrData.setEffectiveDestinationNumber(cdrData.getFinalCalledPartyNumber());

        log.debug("PARTIAL swap complete. After: Calling='{}', FinalCalled='{}'",
                cdrData.getCallingPartyNumber(), cdrData.getFinalCalledPartyNumber());
    }

    public static void swapTrunks(CdrData cdrData) {
        log.debug("Swapping trunks. Before: Orig='{}', Dest='{}'", cdrData.getOrigDeviceName(),
                cdrData.getDestDeviceName());
        String tempTrunk = cdrData.getOrigDeviceName();
        cdrData.setOrigDeviceName(cdrData.getDestDeviceName());
        cdrData.setDestDeviceName(tempTrunk);
        log.debug("Swapped trunks. After: Orig='{}', Dest='{}'", cdrData.getOrigDeviceName(),
                cdrData.getDestDeviceName());
    }

    public static String cleanExtension(String extension) {
        if (extension == null)
            return "";
        String cleaned = extension.trim();
        // Remove leading '+' if present
        if (cleaned.startsWith("+"))
            cleaned = cleaned.substring(1);
        // Step 1: Trim and remove whitespace
        return cleaned.replaceAll("\\s+", "");
    }

    public static boolean isPossibleExtension(String extensionNumber, ExtensionLimits limits) {
        if (limits == null || extensionNumber == null || extensionNumber.isEmpty()) {
            return false;
        }
        String cleanedExt = CdrUtil.cleanPhoneNumber(extensionNumber, null, false).getCleanedNumber();
        if (cleanedExt.startsWith("+"))
            cleanedExt = cleanedExt.substring(1);

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

    /**
     * Reads the first few lines of a file for probing/validation using streaming.
     * Never loads the entire file into memory.
     *
     * @param file The file to read from.
     * @return A list of the first few lines (up to PROBE_LINE_COUNT).
     */
    public static List<String> readInitialLinesFromFile(File file) {
        List<String> lines = new ArrayList<>();
        if (file == null || !file.exists() || file.length() == 0) {
            return lines;
        }

        try (InputStream inputStream = new FileInputStream(file);
                InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(reader)) {

            String line;
            while ((line = bufferedReader.readLine()) != null && lines.size() < PROBE_LINE_COUNT) {
                lines.add(line);
            }

            log.debug("Read {} initial lines from file for probing", lines.size());

        } catch (IOException e) {
            log.error("Failed to read initial lines from file '{}' for probing.", file.getName(), e);
        }

        return lines;
    }
}