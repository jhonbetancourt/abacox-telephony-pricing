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

        if (removePbxPrefixIfNeeded && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            int prefixLengthToRemove = getPrefixLength(currentNumber, pbxPrefixes);
            if (prefixLengthToRemove > 0) {
                currentNumber = currentNumber.substring(prefixLengthToRemove);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLengthToRemove, number, currentNumber);
            } else if (prefixLengthToRemove == 0 && !pbxPrefixes.isEmpty()) { // prefix was defined but not found
                 log.trace("PBX prefix removal requested for '{}', but no matching prefix found. Processing original number.", number);
                 // In PHP, if maxCaracterAExtraer == 0, it means a prefix was defined but not found,
                 // and $nuevo would be set to ''. If $modo_seguro is true and $nuevo is '', it resets to original.
                 // Here, if prefix not found, we continue with currentNumber. If mode_seguro logic is needed,
                 // it implies that if a prefix *should* have been there but wasn't, the number is invalid.
                 // For now, this matches the "else" part of PHP's `limpiar_numero` where it processes the number as is if prefix not stripped.
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
                // PHP's limpiar_numero with preg_replace('/[^0-9]/','?', $parcial)
                // and then strpos($parcial2, '?') means it truncates at the first non-digit
                // after the first character.
                break;
            }
        }
        restOfString = numericRest.toString();
        String cleaned = firstChar + restOfString;

        log.trace("Cleaned number: Original='{}', PBXPrefixes={}, removePbxPrefixIfNeeded={}, Result='{}'", number, pbxPrefixes, removePbxPrefixIfNeeded, cleaned);
        return cleaned;
    }


    public int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = -1; // PHP: $maxCaracterAExtraer = -1; (return -1 if no prefixes to check)
        if (number == null || pbxPrefixes == null) {
            return -1;
        }
        if (pbxPrefixes.isEmpty()) return -1;

        longestMatchLength = 0; // PHP: if a prefix is defined, default to 0 if not found
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
        // PHP: if $valorpbx == $prefijoAComparar, break and return $maxCaracterAExtraer (length of found prefix)
        // PHP: if loop finishes and no match, $maxCaracterAExtraer remains 0 (if prefixes were checked)
        return prefixFoundThisIteration ? longestMatchLength : 0;
    }

    /**
     * Preprocesses a dialed number, primarily for Colombian numbering plan changes.
     * This method mirrors the logic of PHP's `_esCelular_fijo`.
     *
     * @param number              The original dialed number.
     * @param originCountryId     The origin country ID.
     * @param forcedTelephonyType Wrapper to return a potentially forced TelephonyType ID.
     * @param commLocation        The communication location for context (city/department).
     * @return The processed number, possibly transformed.
     */
    public String preprocessNumberForLookup(String number, Long originCountryId, FieldWrapper<Long> forcedTelephonyType, CommunicationLocation commLocation) {
        if (number == null || originCountryId == null || !originCountryId.equals(CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Only apply for Colombia and non-null numbers
        }

        String originalNumberLog = number; // For logging
        String processedNumber = number;
        int len = number.length();

        // Reset forcedTelephonyType at the beginning if it's meant to be an output of this specific call only
        // forcedTelephonyType.setValue(null); // Caller should initialize if needed.

        if (len == 10) {
            if (number.startsWith("3")) {
                if (number.matches("^\\d{10}$")) { // Ensure it's all digits
                    String firstThree = number.substring(0, 3);
                    try {
                        int prefixVal = Integer.parseInt(firstThree);
                        if (prefixVal >= 300 && prefixVal <= 350) { // PHP: $n3 >= 300 && $n3 <= 350
                            processedNumber = "03" + number; // PHP: $g_numero = "03".$numero;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR); // PHP: $g_tipotele = 2;
                        }
                    } catch (NumberFormatException e) {
                        log.trace("Non-numeric prefix for 10-digit number starting with 3: {}", firstThree);
                    }
                }
            } else if (number.startsWith("60")) {
                if (number.matches("^60\\d{8}$")) { // Ensure pattern 60XXXXXXXX
                    String ndcFromNumber = number.substring(2, 3);
                    String subscriberPart = number.substring(3); // Last 7 digits

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
                            processedNumber = subscriberPart; // PHP: $g_numero = substr($numero, 3);
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                        } else if (seriesDep.equalsIgnoreCase(commDep)) { // Local Extended
                            processedNumber = subscriberPart; // PHP: $g_numero = substr($numero, 3);
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL_EXT);
                        } else {
                            String nationalOpPrefix = configService.mapCompanyToNationalOperatorPrefix(seriesCompany);
                            String numWithout60 = number.substring(2); // number without "60"
                            if (StringUtils.hasText(nationalOpPrefix)) {
                                processedNumber = nationalOpPrefix + numWithout60; // PHP: $g_numero = $ind.$numero; (where $numero was number without "60")
                            } else {
                                processedNumber = "09" + numWithout60; // PHP Default
                            }
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                        }
                    } else { // No series details found, default to national with "09"
                        String numWithout60 = number.substring(2);
                        processedNumber = "09" + numWithout60; // PHP: $g_numero = '09'.$numero;
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                    }
                }
            }
        } else if (len == 12) {
            if (number.startsWith("573") || number.startsWith("603")) {
                String mobilePartAfterPrefix = number.substring(2); // e.g., "3151234567" from "57315..."
                if (mobilePartAfterPrefix.length() == 10 && mobilePartAfterPrefix.matches("^\\d{10}$")) {
                    String firstThreeOfMobile = mobilePartAfterPrefix.substring(0, 3);
                    try {
                        int mobilePrefixVal = Integer.parseInt(firstThreeOfMobile);
                        if (mobilePrefixVal >= 300 && mobilePrefixVal <= 350) {
                            processedNumber = mobilePartAfterPrefix; // PHP: $telefono = substr($telefono, -10); $g_numero = $telefono;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR); // PHP: $g_tipotele = 2;
                        }
                    } catch (NumberFormatException e) {
                        log.trace("Non-numeric prefix for 12-digit mobile: {}", firstThreeOfMobile);
                    }
                }
            } else if (number.startsWith("5760") || number.startsWith("6060")) {
                if (number.matches("^(5760|6060)\\d{8}$")) {
                    String ndcAndSubscriber = number.substring(4); // Last 8 digits
                    String ndcFromNumber = ndcAndSubscriber.substring(0, 1);
                    String subscriberPart = ndcAndSubscriber.substring(1); // Last 7 digits

                    Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicatorId())
                            .flatMap(entityLookupService::findIndicatorById)
                            .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));

                    if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                        processedNumber = subscriberPart; // PHP: $g_numero = $subscriberPart;
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                    } else {
                        processedNumber = "09" + ndcAndSubscriber; // PHP: $g_numero = "09" + $ndcAndSubscriber;
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                    }
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03")) {
                String mobilePart = number.substring(1); // Number without leading "0", e.g., "3151234567"
                if (mobilePart.length() == 10 && mobilePart.matches("^\\d{10}$")) {
                    String firstThreeOfMobile = mobilePart.substring(0, 3);
                    try {
                        int mobilePrefixVal = Integer.parseInt(firstThreeOfMobile);
                        if (mobilePrefixVal >= 300 && mobilePrefixVal <= 350) {
                            processedNumber = mobilePart; // PHP: $telefono = substr($telefono, -10); $g_numero = $telefono;
                            forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR); // PHP: $g_tipotele = 2;
                        }
                    } catch (NumberFormatException e) {
                        log.trace("Non-numeric prefix for 11-digit '03...' mobile: {}", firstThreeOfMobile);
                    }
                }
            } else if (number.startsWith("604")) {
                if (number.matches("^604\\d{8}$")) {
                    processedNumber = number.substring(3); // Remove "604" -> PHP: $telefono = substr($telefono, -8); $g_numero = $telefono;
                    // PHP does not set $g_tipotele here, Java also doesn't force.
                }
            }
        } else if (len == 9) {
            if (number.startsWith("60")) {
                if (number.matches("^60\\d{7}$")) {
                    processedNumber = number.substring(2); // Remove "60" -> PHP: $telefono = substr($telefono, -7); $g_numero = $telefono;
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