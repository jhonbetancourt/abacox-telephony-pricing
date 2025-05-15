package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
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

    private final PrefixInfoLookupService prefixInfoLookupService; // For determineNationalPrefix
    // CdrProcessingConfig might be needed if constants like COLOMBIA_ORIGIN_COUNTRY_ID are sourced from it
    // private final CdrProcessingConfig configService;

    // This constant is used by preprocessNumberForLookup
    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L;


    @Getter
    @Setter
    public static class FieldWrapper<T> { // Made public static inner class
        T value;
        public FieldWrapper(T v) { this.value = v; }
    }

    public String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLength, number, cleaned);
            } else if (prefixLength == 0 && !pbxPrefixes.isEmpty()){
                log.trace("Prefix removal requested, PBX prefixes defined, but no matching prefix found in {}. Returning empty.", number);
                return "";
            }
        }
        String firstChar = "";
        String restOfString = cleaned;
        if (!cleaned.isEmpty()) {
            firstChar = cleaned.substring(0, 1);
            restOfString = cleaned.substring(1);
        }
        if ("+".equals(firstChar)) {
            firstChar = ""; 
        }
        restOfString = restOfString.replaceAll("[^0-9]", "");
        cleaned = firstChar + restOfString;

        return cleaned;
    }
    
    public String cleanNumber(String number, List<String> pbxPrefixes, boolean removePbxPrefixIfNeeded, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        // extConfig is passed but not used in the provided snippet of the second cleanNumber.
        // The implementation here matches the second cleanNumber from the prompt.
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        if (removePbxPrefixIfNeeded && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLength, number, cleaned);
            } else if (prefixLength == 0 && !pbxPrefixes.isEmpty()){
                log.trace("PBX prefix removal requested, but no matching prefix found in {}. Number remains: {}", number, cleaned);
            }
        }

        String firstChar = "";
        String restOfString = cleaned;
        if (!cleaned.isEmpty()) {
            firstChar = cleaned.substring(0, 1);
            restOfString = cleaned.substring(1);
        }
        if ("+".equals(firstChar)) {
            firstChar = "";
        }

        if (restOfString.length() > 0 && !restOfString.matches("\\d*")) {
            StringBuilder numericRest = new StringBuilder();
            for (char c : restOfString.toCharArray()) {
                if (Character.isDigit(c)) {
                    numericRest.append(c);
                } else {
                    break;
                }
            }
            restOfString = numericRest.toString();
        }
        cleaned = firstChar + restOfString;

        return cleaned;
    }


    public int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = -1; 
        if (number == null || pbxPrefixes == null) { 
            return -1;
        }
        if (pbxPrefixes.isEmpty()) return -1; 

        longestMatchLength = 0; 
        boolean prefixFound = false;
        for (String prefix : pbxPrefixes) {
            String trimmedPrefix = prefix != null ? prefix.trim() : "";
            if (!trimmedPrefix.isEmpty() && number.startsWith(trimmedPrefix)) {
                if (trimmedPrefix.length() > longestMatchLength) {
                    longestMatchLength = trimmedPrefix.length();
                }
                prefixFound = true;
            }
        }
        return prefixFound ? longestMatchLength : 0;
    }

    public String preprocessNumberForLookup(String number, Long originCountryId, FieldWrapper<Long> forcedTelephonyType, CommunicationLocation commLocation) {
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number;
        }
        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        if (len == 10) {
            if (number.startsWith("3") && number.matches("^3[0-4][0-9]\\d{7}$")) { 
                processedNumber = "03" + number;
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("60")) { 
                String ndcFromNumber = number.substring(2, 3);
                Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicator())
                                                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));

                if (localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)) {
                    if (number.length() >= 3) {
                        processedNumber = number.substring(3); 
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                    }
                } else {
                    String nationalPrefixBasedOnCompany = determineNationalPrefix(number, originCountryId);
                    if (nationalPrefixBasedOnCompany != null) {
                        processedNumber = nationalPrefixBasedOnCompany + number.substring(2);
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                    } else {
                        processedNumber = "09" + number.substring(2);
                        forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                        log.trace("Number {} (60X...) not local by NDC and no company match, defaulting to national with '09'.", number);
                    }
                }
            }
        } else if (len == 12) {
            if (number.startsWith("573") && number.matches("^573[0-4][0-9]\\d{7}$")) {
                processedNumber = "03" + number.substring(2); 
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("603") && number.matches("^603[0-4][0-9]\\d{7}$")) {
                processedNumber = "03" + number.substring(2); 
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("5760") && number.matches("^5760\\d{8}$")) {
                processedNumber = number.substring(4); 
                 String ndcFromNumber = processedNumber.substring(0,1);
                 Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicator())
                                                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));
                 if(localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)){
                    processedNumber = processedNumber.substring(1);
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                 } else {
                    processedNumber = "09" + processedNumber;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                 }

            } else if (number.startsWith("6060") && number.matches("^6060\\d{8}$")) {
                processedNumber = number.substring(4); 
                 String ndcFromNumber = processedNumber.substring(0,1);
                 Optional<Integer> localNdcOpt = Optional.ofNullable(commLocation.getIndicator())
                                                        .flatMap(ind -> prefixInfoLookupService.findLocalNdcForIndicator(ind.getId()));
                 if(localNdcOpt.isPresent() && String.valueOf(localNdcOpt.get()).equals(ndcFromNumber)){
                    processedNumber = processedNumber.substring(1);
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
                 } else {
                    processedNumber = "09" + processedNumber;
                    forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_NACIONAL);
                 }
            }
        } else if (len == 11) {
            if (number.startsWith("03") && number.matches("^03[0-4][0-9]\\d{7}$")) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (number.startsWith("604") && number.matches("^604\\d{8}$")) { 
                processedNumber = number.substring(3); 
            }
        } else if (len == 9 && number.startsWith("60") && number.matches("^60\\d{7}$")) {
            processedNumber = number.substring(2); 
        }

        if (!originalNumber.equals(processedNumber)) {
            log.debug("Preprocessed Colombian number for lookup: {} -> {}", originalNumber, processedNumber);
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
            int ndc = Integer.parseInt(ndcStr);
            long subscriberNumber = Long.parseLong(subscriberNumberStr);

            Optional<String> companyOpt = prefixInfoLookupService.findCompanyForNationalSeries(ndc, subscriberNumber, originCountryId);

            if (companyOpt.isPresent()) {
                String company = companyOpt.get().toUpperCase();
                if (company.contains("TELMEX")) return "0456"; 
                if (company.contains("COLOMBIA TELECOMUNICACIONES")) return "09"; 
                if (company.contains("UNE EPM")) return "05"; 
                if (company.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ") || company.contains("ETB")) return "07"; 
                log.trace("Company '{}' found for NDC {}, Sub {}, but no matching national prefix rule.", company, ndc, subscriberNumber);
            } else {
                log.trace("No company found for NDC {}, Sub {} to determine national prefix.", ndc, subscriberNumber);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing NDC/Subscriber for national prefix determination: NDC={}, Sub={}", ndcStr, subscriberNumberStr, e);
        }
        return null;
    }
}