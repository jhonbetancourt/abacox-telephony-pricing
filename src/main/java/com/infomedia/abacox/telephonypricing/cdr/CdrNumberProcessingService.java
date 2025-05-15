package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrNumberProcessingService {

    private final PrefixInfoLookupService prefixInfoLookupService;
    private final EntityLookupService entityLookupService;
    private final CdrProcessingConfig configService;


    @Getter
    @Setter
    public static class FieldWrapper<T> {
        T value;
        public FieldWrapper(T v) { this.value = v; }
    }

    public String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String currentNumber = number.trim();
        int prefixLengthToRemove = 0;

        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLengthToRemove = getPrefixLength(currentNumber, pbxPrefixes);
            if (prefixLengthToRemove > 0) {
                currentNumber = currentNumber.substring(prefixLengthToRemove);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLengthToRemove, number, currentNumber);
            } else {
                log.trace("Prefix removal requested for '{}', PBX prefixes defined, but no matching prefix found. Returning empty as per PHP logic (non-modo_seguro).", number);
                return "";
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
                break;
            }
        }
        restOfString = numericRest.toString();
        String cleaned = firstChar + restOfString;

        log.trace("Cleaned number (version 1): Original='{}', PBXPrefixes={}, removePrefix={}, Result='{}'", number, pbxPrefixes, removePrefix, cleaned);
        return cleaned;
    }

    public String cleanNumber(String number, List<String> pbxPrefixes, boolean removePbxPrefixIfNeeded, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return "";
        String currentNumber = number.trim();

        if (removePbxPrefixIfNeeded && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            int prefixLengthToRemove = getPrefixLength(currentNumber, pbxPrefixes);
            if (prefixLengthToRemove > 0) {
                currentNumber = currentNumber.substring(prefixLengthToRemove);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLengthToRemove, number, currentNumber);
            } else {
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
                break;
            }
        }
        restOfString = numericRest.toString();
        String cleaned = firstChar + restOfString;

        log.trace("Cleaned number (version 2 - extConfig ignored for now): Original='{}', PBXPrefixes={}, removePbxPrefixIfNeeded={}, Result='{}'", number, pbxPrefixes, removePbxPrefixIfNeeded, cleaned);
        return cleaned;
    }


    public int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = -1;
        if (number == null || pbxPrefixes == null) {
            return -1;
        }
        if (pbxPrefixes.isEmpty()) return -1;

        longestMatchLength = 0;
        boolean prefixFoundThisIteration = false;
        for (String prefix : pbxPrefixes) {
            String trimmedPrefix = prefix != null ? prefix.trim() : "";
            if (!trimmedPrefix.isEmpty() && number.startsWith(trimmedPrefix)) {
                if (trimmedPrefix.length() > longestMatchLength) {
                    longestMatchLength = trimmedPrefix.length();
                }
                prefixFoundThisIteration = true;
            }
        }
        return prefixFoundThisIteration ? longestMatchLength : 0;
    }

    public String preprocessNumberForLookup(String number, Long originCountryId, FieldWrapper<Long> forcedTelephonyType, CommunicationLocation commLocation) {
        if (number == null || originCountryId == null || !originCountryId.equals(CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Only apply for Colombia and non-null numbers
        }
        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        if (forcedTelephonyType == null) { // Should not happen if called correctly
            forcedTelephonyType = new FieldWrapper<>(null);
        }

        if (len == 10) {
            if (number.startsWith("3") && number.matches("^3[0-4][0-9]\\d{7}$")) { // Mobile starting with 3 (300-349 range)
                processedNumber = "03" + number;
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("60")) { // Fixed line with new 60X prefix
                String ndcFromNumber = number.substring(2, 3);
                String subscriberPart = number.substring(3);

                Optional<Map<String, String>> seriesDetailsOpt = prefixInfoLookupService.findNationalSeriesDetailsByNdcAndSubscriber(
                        ndcFromNumber, Long.parseLong(subscriberPart), originCountryId
                );

                if (seriesDetailsOpt.isPresent()) {
                    Map<String, String> seriesDetails = seriesDetailsOpt.get();
                    String seriesDep = seriesDetails.get("department_country");
                    String seriesCity = seriesDetails.get("city_name");
                    String seriesCompany = seriesDetails.get("company");

                    Optional<Indicator> commLocationIndicatorOpt = Optional.ofNullable(commLocation.getIndicatorId())
                            .flatMap(entityLookupService::findIndicatorById);
                    String commDep = commLocationIndicatorOpt.map(Indicator::getDepartmentCountry).orElse("");
                    String commCity = commLocationIndicatorOpt.map(Indicator::getCityName).orElse("");

                    if (seriesDep.equalsIgnoreCase(commDep) && seriesCity.equalsIgnoreCase(commCity)) {
                        processedNumber = subscriberPart; // Local
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                    } else if (seriesDep.equalsIgnoreCase(commDep)) {
                        processedNumber = subscriberPart; // Local Extended
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL_EXT); // Or LOCAL, depending on how pricing handles this
                    } else {
                        String nationalOpPrefix = configService.mapCompanyToNationalOperatorPrefix(seriesCompany);
                        if (StringUtils.hasText(nationalOpPrefix)) {
                            processedNumber = nationalOpPrefix + ndcFromNumber + subscriberPart;
                        } else {
                            processedNumber = "09" + ndcFromNumber + subscriberPart; // Default
                        }
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                    }
                } else { // No series details found, default to national with "09"
                    processedNumber = "09" + ndcFromNumber + subscriberPart;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                }
            }
        } else if (len == 12) {
            if (number.startsWith("573") && number.matches("^573[0-4][0-9]\\d{7}$")) {
                processedNumber = "03" + number.substring(3);
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("603") && number.matches("^603[0-4][0-9]\\d{7}$")) {
                processedNumber = "03" + number.substring(3);
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("5760") && number.matches("^5760\\d{8}$")) {
                String ndcAndSubscriber = number.substring(4);
                String ndcFromNumber = ndcAndSubscriber.substring(0, 1);
                String subscriberPart = ndcAndSubscriber.substring(1);
                Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicatorId())
                        .flatMap(entityLookupService::findIndicatorById)
                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));
                if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                    processedNumber = subscriberPart;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                } else {
                    processedNumber = "09" + ndcAndSubscriber;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                }
            } else if (number.startsWith("6060") && number.matches("^6060\\d{8}$")) {
                 String ndcAndSubscriber = number.substring(4);
                String ndcFromNumber = ndcAndSubscriber.substring(0, 1);
                String subscriberPart = ndcAndSubscriber.substring(1);
                Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicatorId())
                        .flatMap(entityLookupService::findIndicatorById)
                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));
                if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                    processedNumber = subscriberPart;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                } else {
                    processedNumber = "09" + ndcAndSubscriber;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03") && number.matches("^03[0-4][0-9]\\d{7}$")) {
                // No change to number, it's already in 03 + 10_digits format
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("604") && number.matches("^604\\d{8}$")) {
                processedNumber = number.substring(3); // Remove 604
                // Type will be determined later by evaluateDestination
            }
        } else if (len == 9 && number.startsWith("60") && number.matches("^60\\d{7}$")) {
            processedNumber = number.substring(2); // Remove 60
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
        if (number10DigitStartingWith60.length() >=3) {
            ndcStr = number10DigitStartingWith60.substring(2, 3);
        } else {
            return null;
        }
        String subscriberNumberStr = number10DigitStartingWith60.substring(3);

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d{7}")) {
            log.warn("Invalid NDC or subscriber number format in determineNationalPrefix: NDC={}, Sub={}", ndcStr, subscriberNumberStr);
            return null;
        }
        try {
            long subscriberNumber = Long.parseLong(subscriberNumberStr);
            Optional<Map<String, String>> seriesDetailsOpt = prefixInfoLookupService.findNationalSeriesDetailsByNdcAndSubscriber(ndcStr, subscriberNumber, originCountryId);

            if (seriesDetailsOpt.isPresent()) {
                String company = seriesDetailsOpt.get().get("company");
                return configService.mapCompanyToNationalOperatorPrefix(company);
            } else {
                log.trace("No company found for NDC {}, Sub {} to determine national operator prefix.", ndcStr, subscriberNumber);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing Subscriber number for national operator prefix determination: Sub={}", subscriberNumberStr, e);
        }
        return ""; // Return empty if no specific mapping, caller will default to "09"
    }
}