// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrNumberProcessingService.java
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

    public String cleanNumber(String number, List<String> pbxPrefixes, boolean removePbxPrefixIfNeeded, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return "";
        String currentNumber = number.trim();
        String originalNumberForLog = number; // For logging

        if (removePbxPrefixIfNeeded && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            int prefixLengthFound = getPrefixLength(currentNumber, pbxPrefixes);
            if (prefixLengthFound > 0) {
                currentNumber = currentNumber.substring(prefixLengthFound);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLengthFound, originalNumberForLog, currentNumber);
            } else if (prefixLengthFound == 0) { // PHP: $maxCaracterAExtraer == 0 (prefix defined but not found)
                 log.trace("PBX prefix removal requested for '{}', but no matching prefix found. Number considered invalid for this path.", originalNumberForLog);
                 // In PHP, if $maxCaracterAExtraer == 0, $nuevo becomes ''. If $modo_seguro is false, it returns ''.
                 // If $modo_seguro is true, it would return original.
                 // For `procesaSaliente_Complementar` (which calls `evaluarDestino_pos` -> `limpiar_numero`),
                 // `modo_seguro` is effectively true if it's not a trunk call or if trunk allows PBX prefix.
                 // If a prefix *should* have been stripped but wasn't, the number might be invalid.
                 // For now, if prefix was expected but not found, we treat the number as potentially invalid for further processing
                 // by returning empty, unless modo_seguro (which is implicitly true for this method's typical use)
                 // would revert to original.
                 // To align with PHP's $nuevo = '' when $maxCaracterAExtraer == 0 and $modo_seguro is false:
                 // This path is tricky. PHP's `limpiar_numero` with `modo_seguro=false` (which is not the common case for `evaluarDestino_pos`)
                 // would return "" if prefix was defined but not found.
                 // If `modo_seguro=true`, it returns the original number.
                 // This Java method's `removePbxPrefixIfNeeded` implies `modo_seguro` behavior.
                 // If `removePbxPrefixIfNeeded` is true, and prefixes are defined, but none match,
                 // PHP's `limpiar_numero` (with `modo_seguro=true`) would return the original number.
                 // So, if `prefixLengthFound == 0`, we continue with `currentNumber` (which is still the original).
                 // The log above is sufficient.
            }
            // if prefixLengthFound == -1 (no prefixes defined), currentNumber remains original.
        }


        String firstChar = "";
        String restOfString = currentNumber;
        if (!currentNumber.isEmpty()) {
            firstChar = currentNumber.substring(0, 1);
            restOfString = currentNumber.substring(1);
        }

        if ("+".equals(firstChar)) {
            firstChar = ""; // Remove leading '+'
        }

        StringBuilder numericRest = new StringBuilder();
        for (char c : restOfString.toCharArray()) {
            if (Character.isDigit(c)) {
                numericRest.append(c);
            } else {
                // PHP's limpiar_numero with preg_replace('/[^0-9]/','?', $parcial)
                // and then strpos($parcial2, '?') means it truncates at the first non-digit
                // after the first character.
                break;
            }
        }
        restOfString = numericRest.toString();
        String cleaned = firstChar + restOfString;

        log.trace("Cleaned number: Original='{}', PBXPrefixes={}, removePbxPrefixIfNeeded={}, Result='{}'", originalNumberForLog, pbxPrefixes, removePbxPrefixIfNeeded, cleaned);
        return cleaned;
    }


    /**
     * Determines the length of the first matching PBX prefix found in the number.
     * Mirrors PHP's `Validar_prefijoSalida`.
     *
     * @param number      The number to check.
     * @param pbxPrefixes A list of PBX prefixes to check against.
     * @return Length of the first matching prefix.
     *         0 if prefixes are defined but none match.
     *         -1 if pbxPrefixes is null or empty (no prefixes to check).
     */
    public int getPrefixLength(String number, List<String> pbxPrefixes) {
        if (number == null) return -1; // Or 0, depending on how null number should be treated
        if (pbxPrefixes == null || pbxPrefixes.isEmpty()) {
            return -1; // No prefixes to check
        }

        for (String prefix : pbxPrefixes) {
            String trimmedPrefix = prefix != null ? prefix.trim() : "";
            if (!trimmedPrefix.isEmpty() && number.startsWith(trimmedPrefix)) {
                return trimmedPrefix.length(); // Found a match, return its length
            }
        }
        return 0; // Prefixes were defined, but none matched
    }

    public String preprocessNumberForLookup(String number, Long originCountryId, FieldWrapper<Long> forcedTelephonyType, CommunicationLocation commLocation) {
        if (number == null || originCountryId == null || !originCountryId.equals(CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number;
        }

        String originalNumberLog = number;
        String processedNumber = number;
        int len = number.length();

        // Ensure forcedTelephonyType is not null for setting value
        if (forcedTelephonyType == null) {
            forcedTelephonyType = new FieldWrapper<>(null); // Should not happen if called correctly
        }


        if (len == 10) {
            if (number.startsWith("3")) {
                if (number.matches("^\\d{10}$")) {
                    String firstThree = number.substring(0, 3);
                    try {
                        int prefixVal = Integer.parseInt(firstThree);
                        if (prefixVal >= 300 && prefixVal <= 350) {
                            processedNumber = "03" + number;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
                        }
                    } catch (NumberFormatException e) {
                        log.trace("Non-numeric prefix for 10-digit number starting with 3: {}", firstThree);
                    }
                }
            } else if (number.startsWith("60")) {
                if (number.matches("^60\\d{8}$")) {
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
                            processedNumber = subscriberPart;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                        } else if (seriesDep.equalsIgnoreCase(commDep)) {
                            processedNumber = subscriberPart;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL_EXT);
                        } else {
                            String nationalOpPrefix = configService.mapCompanyToNationalOperatorPrefix(seriesCompany);
                            String numWithout60 = number.substring(2);
                            if (StringUtils.hasText(nationalOpPrefix)) {
                                processedNumber = nationalOpPrefix + numWithout60;
                            } else {
                                processedNumber = "09" + numWithout60; // PHP Default
                            }
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                        }
                    } else {
                        String numWithout60 = number.substring(2);
                        processedNumber = "09" + numWithout60;
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                    }
                }
            }
        } else if (len == 12) {
            if (number.startsWith("573") || number.startsWith("603")) {
                 String mobilePartAfterPrefix = number.substring(2);
                if (mobilePartAfterPrefix.length() == 10 && mobilePartAfterPrefix.matches("^\\d{10}$")) {
                     String firstThreeOfMobile = mobilePartAfterPrefix.substring(0, 3);
                    try {
                        int mobilePrefixVal = Integer.parseInt(firstThreeOfMobile);
                        if (mobilePrefixVal >= 300 && mobilePrefixVal <= 350) {
                            processedNumber = mobilePartAfterPrefix;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
                        }
                    } catch (NumberFormatException e) {
                        log.trace("Non-numeric prefix for 12-digit mobile: {}", firstThreeOfMobile);
                    }
                }
            } else if (number.startsWith("5760") || number.startsWith("6060")) {
                if (number.matches("^(5760|6060)\\d{8}$")) {
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
            }
        } else if (len == 11) {
            if (number.startsWith("03")) {
                String mobilePart = number.substring(1);
                if (mobilePart.length() == 10 && mobilePart.matches("^\\d{10}$")) {
                    String firstThreeOfMobile = mobilePart.substring(0, 3);
                     try {
                        int mobilePrefixVal = Integer.parseInt(firstThreeOfMobile);
                        if (mobilePrefixVal >= 300 && mobilePrefixVal <= 350) {
                            processedNumber = mobilePart;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
                        }
                    } catch (NumberFormatException e) {
                        log.trace("Non-numeric prefix for 11-digit '03...' mobile: {}", firstThreeOfMobile);
                    }
                }
            } else if (number.startsWith("604")) {
                 if (number.matches("^604\\d{8}$")) {
                    processedNumber = number.substring(3);
                    // No forced type in PHP for this specific case
                }
            }
        } else if (len == 9) {
            if (number.startsWith("60")) {
                if (number.matches("^60\\d{7}$")) {
                    processedNumber = number.substring(2);
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                }
            }
        }

        if (!originalNumberLog.equals(processedNumber)) {
            log.debug("Preprocessed Colombian number for lookup: {} -> {}. Forced Type: {}", originalNumberLog, processedNumber, forcedTelephonyType.getValue());
        }
        return processedNumber;
    }
}