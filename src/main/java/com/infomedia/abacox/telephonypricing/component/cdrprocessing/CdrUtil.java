// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/CdrUtil.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMAOutputStream;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class CdrUtil {

    private static final int PROBE_LINE_COUNT = 5; // Read the first few lines for validation

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

    /**
     * Performs a complete swap of calling/called party information, including numbers, partitions,
     * and optionally trunks. This replicates the full logic of PHP's `InvertirLlamada`.
     *
     * @param cdrData The CdrData object to modify.
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
     * Partitions and trunks are NOT affected. This is for the non-conference incoming detection case.
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

    /**
     * Calculates the SHA-256 hash of a given byte array.
     * This is the primary method for generating a checksum from file content.
     *
     * @param input The byte array to hash. Can be null.
     * @return The lowercase hexadecimal representation of the SHA-256 hash,
     *         or an empty string if the input is null.
     */
    public static String sha256(byte[] input) {
        if (input == null) {
            return "";
        }

        try {
            // Get an instance of the SHA-256 message digest algorithm
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Perform the hash computation
            byte[] hash = digest.digest(input);

            // Convert the byte array into a hexadecimal string
            // Using StringBuilder for efficient string concatenation in a loop
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                // String.format ensures each byte is represented by two hex characters,
                // with a leading zero if necessary (e.g., "0f" instead of "f").
                hexString.append(String.format("%02x", b));
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // This should never happen in a standard Java environment,
            // as "SHA-256" is a required algorithm.
            // We wrap it in a RuntimeException to avoid cluttering calling code
            // with a checked exception that is virtually impossible to trigger.
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Calculates SHA-256 hash from an InputStream without loading entire content into memory.
     * The stream is NOT closed by this method.
     */
    public static String sha256Stream(InputStream inputStream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Compresses data from an InputStream to an OutputStream using LZMA with maximum compression.
     * Neither stream is closed by this method.
     */
    public static void compressStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        LZMA2Options options = new LZMA2Options(LZMA2Options.PRESET_MAX);

        try (LZMAOutputStream lzmaOutputStream = new LZMAOutputStream(outputStream, options, -1)) {
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                lzmaOutputStream.write(buffer, 0, bytesRead);
            }

            lzmaOutputStream.finish();
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

    /**
     * Decompresses a byte array using LZMA compression (7z format).
     * This method provides better decompression ratios than ZIP/DEFLATE.
     * 
     * @param compressedData The byte array compressed with LZMA.
     * @return The original, uncompressed byte array.
     * @throws IOException if an I/O error occurs during decompression.
     * @throws DataFormatException if the compressed data format is invalid.
     */
    public static byte[] decompress(byte[] compressedData) throws IOException, DataFormatException {
        if (compressedData == null || compressedData.length == 0) {
            throw new DataFormatException("Compressed data is null or empty");
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(compressedData);
             LZMAInputStream lzmaInputStream = new LZMAInputStream(inputStream);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = lzmaInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            return outputStream.toByteArray();
            
        } catch (IOException e) {
            log.error("Failed to decompress LZMA data", e);
            throw new DataFormatException("Invalid LZMA compressed data: " + e.getMessage());
        }
    }

    /**
     * Compresses a byte array using LZMA compression with maximum compression level (7z format).
     * This method provides significantly better compression ratios than ZIP/DEFLATE,
     * at the cost of slower compression speed. Ideal for archival storage of CDR data.
     * 
     * @param data The byte array to compress.
     * @return The compressed byte array in LZMA format.
     * @throws IOException if an I/O error occurs during compression.
     */
    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            // Configure LZMA2 with maximum compression (preset 9)
            // Preset 9 provides the best compression ratio but is slower
            LZMA2Options options = new LZMA2Options(LZMA2Options.PRESET_MAX);
            
            try (LZMAOutputStream lzmaOutputStream = new LZMAOutputStream(outputStream, options, data.length)) {
                lzmaOutputStream.write(data);
                lzmaOutputStream.finish();
            }
            
            byte[] compressed = outputStream.toByteArray();
            
            // Log compression statistics for monitoring
            double compressionRatio = data.length > 0 ? (100.0 * compressed.length / data.length) : 0;
            log.debug("LZMA compression: {} bytes -> {} bytes ({}% of original size)", 
                     data.length, compressed.length, String.format("%.2f", compressionRatio));
            
            return compressed;
            
        } catch (IOException e) {
            log.error("Failed to compress data with LZMA", e);
            throw e;
        }
    }

    /**
     * Legacy method for decompressing ZIP/DEFLATE compressed data.
     * Used for backward compatibility with existing data in the database.
     * 
     * @param compressedData The byte array compressed with Deflater.
     * @return The original, uncompressed byte array.
     * @throws IOException if an I/O error occurs.
     * @throws DataFormatException if the compressed data format is invalid.
     * @deprecated Use {@link #decompress(byte[])} instead for new data.
     */
    @Deprecated
    public static byte[] decompressLegacyZip(byte[] compressedData) throws IOException, DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(compressedData.length)) {
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toByteArray();
        } finally {
            inflater.end();
        }
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