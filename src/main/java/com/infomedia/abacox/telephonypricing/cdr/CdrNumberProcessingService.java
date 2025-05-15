package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrNumberProcessingService {

    private final PrefixInfoLookupService prefixInfoLookupService;
    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L;
    private final EntityLookupService entityLookupService;


    @Getter
    @Setter
    public static class FieldWrapper<T> {
        T value;
        public FieldWrapper(T v) { this.value = v; }
    }

    /**
     * Cleans a phone number by potentially removing a PBX prefix and non-numeric characters.
     * This version matches the PHP `limpiar_numero` when `modo_seguro = false`.
     * If `removePrefix` is true and a PBX prefix is found, the number after prefix is processed.
     * If `removePrefix` is true but no PBX prefix is found (and pbxPrefixes is not empty),
     * it implies the number was expected to have a prefix, so an empty string is returned.
     *
     * @param number       The number to clean.
     * @param pbxPrefixes  List of PBX prefixes to check for.
     * @param removePrefix If true, attempt to remove a PBX prefix.
     * @return The cleaned number, or empty string if prefix removal was expected but failed.
     */
    public String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String currentNumber = number.trim();
        int prefixLengthToRemove = 0;

        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLengthToRemove = getPrefixLength(currentNumber, pbxPrefixes);
            if (prefixLengthToRemove > 0) {
                currentNumber = currentNumber.substring(prefixLengthToRemove);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLengthToRemove, number, currentNumber);
            } else { // prefixLengthToRemove == 0 means no prefix from the list matched
                log.trace("Prefix removal requested for '{}', PBX prefixes defined, but no matching prefix found. Returning empty as per PHP logic (non-modo_seguro).", number);
                return ""; // PHP logic: if prefix expected but not found, result is empty
            }
        }
        // If removePrefix was false, or pbxPrefixes was null/empty, or prefix was successfully removed, proceed with char cleaning.

        String firstChar = "";
        String restOfString = currentNumber;
        if (!currentNumber.isEmpty()) {
            firstChar = currentNumber.substring(0, 1);
            restOfString = currentNumber.substring(1);
        }

        if ("+".equals(firstChar)) { // Remove leading '+' if it's the very first character
            firstChar = "";
        }

        // PHP: $parcial = substr($nuevo, 1); if ($parcial != '' && !is_numeric($parcial)) ...
        // This means only the part *after* the first character is aggressively cleaned of non-digits.
        // The first character is preserved unless it was '+'.
        StringBuilder numericRest = new StringBuilder();
        for (char c : restOfString.toCharArray()) {
            if (Character.isDigit(c)) {
                numericRest.append(c);
            } else {
                // PHP: $p = strpos($parcial2, '?'); if ($p > 0) { $parcial = substr($parcial2, 0, $p); }
                // This implies it takes digits up to the first non-digit.
                break;
            }
        }
        restOfString = numericRest.toString();
        String cleaned = firstChar + restOfString;

        log.trace("Cleaned number (version 1): Original='{}', PBXPrefixes={}, removePrefix={}, Result='{}'", number, pbxPrefixes, removePrefix, cleaned);
        return cleaned;
    }

    /**
     * Cleans a phone number, more aligned with PHP `limpiar_numero` when `modo_seguro = true`.
     * If `removePbxPrefixIfNeeded` is true and a PBX prefix is found, it's removed.
     * If `removePbxPrefixIfNeeded` is true but no PBX prefix is found (and pbxPrefixes is not empty),
     * the original number (after trimming) is processed for character cleaning (unlike the other version).
     *
     * @param number                 The number to clean.
     * @param pbxPrefixes            List of PBX prefixes.
     * @param removePbxPrefixIfNeeded If true, attempt PBX prefix removal.
     * @param extConfig              (Currently unused in this specific cleaning logic, but kept for signature consistency if needed later)
     * @return The cleaned number.
     */
    public String cleanNumber(String number, List<String> pbxPrefixes, boolean removePbxPrefixIfNeeded, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return "";
        String currentNumber = number.trim();

        if (removePbxPrefixIfNeeded && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            int prefixLengthToRemove = getPrefixLength(currentNumber, pbxPrefixes);
            if (prefixLengthToRemove > 0) {
                currentNumber = currentNumber.substring(prefixLengthToRemove);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLengthToRemove, number, currentNumber);
            } else {
                // In "modo_seguro" (this version), if prefix not found, we continue with the original (trimmed) number.
                log.trace("PBX prefix removal requested for '{}', but no matching prefix found. Processing original number.", number);
            }
        }

        String firstChar = "";
        String restOfString = currentNumber;
        if (!currentNumber.isEmpty()) {
            firstChar = currentNumber.substring(0, 1);
            restOfString = currentNumber.substring(1);
        }

        if ("+".equals(firstChar)) {
            firstChar = "";
        }

        StringBuilder numericRest = new StringBuilder();
        for (char c : restOfString.toCharArray()) {
            if (Character.isDigit(c)) {
                numericRest.append(c);
            } else {
                break; // Stop at first non-digit after the first character
            }
        }
        restOfString = numericRest.toString();
        String cleaned = firstChar + restOfString;

        log.trace("Cleaned number (version 2 - extConfig ignored for now): Original='{}', PBXPrefixes={}, removePbxPrefixIfNeeded={}, Result='{}'", number, pbxPrefixes, removePbxPrefixIfNeeded, cleaned);
        return cleaned;
    }


    public int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = -1; // PHP: $maxCaracterAExtraer = -1;
        if (number == null || pbxPrefixes == null) {
            return -1; // No number or no prefixes to check
        }
        if (pbxPrefixes.isEmpty()) return -1; // No prefixes defined, so no prefix can be found

        longestMatchLength = 0; // PHP: $maxCaracterAExtraer = 0; after loop if no match
        boolean prefixFoundThisIteration = false;
        for (String prefix : pbxPrefixes) {
            String trimmedPrefix = prefix != null ? prefix.trim() : "";
            if (!trimmedPrefix.isEmpty() && number.startsWith(trimmedPrefix)) {
                if (trimmedPrefix.length() > longestMatchLength) {
                    longestMatchLength = trimmedPrefix.length();
                }
                prefixFoundThisIteration = true; // A match was found in this iteration
            }
        }
        // PHP logic: if a prefix was defined but none matched, $maxCaracterAExtraer remains 0.
        // If no prefixes were defined (or all were empty), it remains -1.
        // This implementation returns 0 if prefixes were checked but none matched.
        return prefixFoundThisIteration ? longestMatchLength : 0;
    }

    public String preprocessNumberForLookup(String number, Long originCountryId, FieldWrapper<Long> forcedTelephonyType, CommunicationLocation commLocation) {
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Only apply for Colombia and non-null numbers
        }
        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        // Ensure forcedTelephonyType is initialized if null
        if (forcedTelephonyType == null) {
            forcedTelephonyType = new FieldWrapper<>(null);
        }

        if (len == 10) {
            if (number.startsWith("3") && number.matches("^3[0-4][0-9]\\d{7}$")) { // Mobile starting with 3 (300-349)
                processedNumber = "03" + number; // Standardize to 03 + 10 digits
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("60")) { // Fixed line with new 60X prefix
                String ndcFromNumber = number.substring(2, 3); // X from 60X
                String subscriberPart = number.substring(3);   // 7 digits after 60X

                Optional<Indicator> commLocationIndicatorOpt = Optional.ofNullable(commLocation.getIndicatorId())
                        .flatMap(entityLookupService::findIndicatorById);

                Optional<Integer> localNdcOpt = commLocationIndicatorOpt
                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));

                if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                    // It's a local call
                    processedNumber = subscriberPart; // Use the 7 digits
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                } else {
                    // It's national, determine operator prefix
                    String nationalOperatorPrefix = determineNationalPrefix(number, originCountryId);
                    if (nationalOperatorPrefix != null) {
                        processedNumber = nationalOperatorPrefix + ndcFromNumber + subscriberPart; // OpPrefix + X + 7_digits
                    } else {
                        processedNumber = "09" + ndcFromNumber + subscriberPart; // Default to 09 + X + 7_digits
                        log.trace("Number {} (60X...) not local by NDC and no company match for national prefix, defaulting to '09'.", number);
                    }
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                }
            }
        } else if (len == 12) {
            if (number.startsWith("573") && number.matches("^573[0-4][0-9]\\d{7}$")) { // Mobile with 573 prefix
                processedNumber = "03" + number.substring(3); // Standardize to 03 + 10 digits (original was substring(2))
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("603") && number.matches("^603[0-4][0-9]\\d{7}$")) { // Mobile with 603 prefix
                processedNumber = "03" + number.substring(3); // Standardize to 03 + 10 digits (original was substring(2))
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("5760") && number.matches("^5760\\d{8}$")) { // Fixed with 5760X prefix
                String ndcAndSubscriber = number.substring(4); // X + 7_digits
                String ndcFromNumber = ndcAndSubscriber.substring(0, 1);
                String subscriberPart = ndcAndSubscriber.substring(1);

                Optional<Indicator> commLocationIndicatorOpt = Optional.ofNullable(commLocation.getIndicatorId())
                        .flatMap(entityLookupService::findIndicatorById);
                Optional<Integer> localNdcOpt = commLocationIndicatorOpt
                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));

                if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                    processedNumber = subscriberPart;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                } else {
                    processedNumber = "09" + ndcAndSubscriber; // Default to 09 + X + 7_digits
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                }
            } else if (number.startsWith("6060") && number.matches("^6060\\d{8}$")) { // Fixed with 6060X prefix
                String ndcAndSubscriber = number.substring(4); // X + 7_digits
                String ndcFromNumber = ndcAndSubscriber.substring(0, 1);
                String subscriberPart = ndcAndSubscriber.substring(1);

                Optional<Indicator> commLocationIndicatorOpt = Optional.ofNullable(commLocation.getIndicatorId())
                        .flatMap(entityLookupService::findIndicatorById);
                Optional<Integer> localNdcOpt = commLocationIndicatorOpt
                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));

                if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                    processedNumber = subscriberPart;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                } else {
                    processedNumber = "09" + ndcAndSubscriber; // Default to 09 + X + 7_digits
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03") && number.matches("^03[0-4][0-9]\\d{7}$")) { // Mobile with 03 prefix
                // Number is already in standard 03 + 10 digit format
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("604") && number.matches("^604\\d{8}$")) { // Fixed with 604 + 8 digits (likely Medellin area)
                processedNumber = number.substring(3); // Remove 604, leaving X + 7 digits
                // Further processing to determine if local or national would happen in evaluateDestination
            }
        } else if (len == 9 && number.startsWith("60") && number.matches("^60\\d{7}$")) { // Fixed with 60 + 7 digits (local number)
            processedNumber = number.substring(2); // Remove 60, leaving 7 digits
            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
        }

        if (!originalNumber.equals(processedNumber)) {
            log.debug("Preprocessed Colombian number for lookup: {} -> {}. Forced Type: {}", originalNumber, processedNumber, forcedTelephonyType.getValue());
        }
        return processedNumber;
    }

    public String determineNationalPrefix(String number10DigitStartingWith60, Long originCountryId) {
        if (number10DigitStartingWith60 == null || !number10DigitStartingWith60.startsWith("60") || number10DigitStartingWith60.length() != 10) {
            return null;
        }
        String ndcStr;
        if (number10DigitStartingWith60.length() >=3) { // Should always be true due to previous check
            ndcStr = number10DigitStartingWith60.substring(2, 3); // The X in 60X
        } else {
            return null; // Should not happen
        }
        String subscriberNumberStr = number10DigitStartingWith60.substring(3); // The 7 digits after 60X

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d{7}")) {
            log.warn("Invalid NDC or subscriber number format in determineNationalPrefix: NDC={}, Sub={}", ndcStr, subscriberNumberStr);
            return null;
        }
        try {
            int ndc = Integer.parseInt(ndcStr);
            long subscriberNumber = Long.parseLong(subscriberNumberStr);

            Optional<String> companyOpt = prefixInfoLookupService.findCompanyForNationalSeries(ndc, subscriberNumber, originCountryId);

            if (companyOpt.isPresent()) {
                String company = companyOpt.get().toUpperCase();
                if (company.contains("TELMEX") || company.contains("CLARO HOGAR")) return "0456"; // Claro Fijo
                if (company.contains("COLOMBIA TELECOMUNICACIONES")) return "09"; // Movistar Fijo
                if (company.contains("UNE EPM")) return "05"; // UNE/Tigo Fijo
                if (company.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ") || company.contains("ETB")) return "07"; // ETB Fijo
                log.trace("Company '{}' found for NDC {}, Sub {}, but no matching national operator prefix rule.", company, ndc, subscriberNumber);
            } else {
                log.trace("No company found for NDC {}, Sub {} to determine national operator prefix.", ndc, subscriberNumber);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing NDC/Subscriber for national operator prefix determination: NDC={}, Sub={}", ndcStr, subscriberNumberStr, e);
        }
        return null; // Default if no specific company match
    }
}