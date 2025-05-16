// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrEnrichmentHelper.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Log4j2
public class CdrEnrichmentHelper {

    private final EntityLookupService entityLookupService;
    private final PrefixInfoLookupService prefixInfoLookupService;
    private final TrunkLookupService trunkLookupService;
    private final CdrProcessingConfig configService;


    public record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {}

    public boolean isInternalCall(String callingNumber, String dialedNumber, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        log.info("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedNumber, dialedIsExt);
        return callingIsExt && dialedIsExt;
    }

    public boolean isLikelyExtension(String number, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return false;
        if (extConfig == null) {
            log.info("isLikelyExtension called with null extConfig for number '{}'. Falling back to basic numeric check.", number);
            return number.matches("\\d{2,7}");
        }

        if (extConfig.getSpecialSyntaxExtensions().contains(number)) {
            log.info("isLikelyExtension: '{}' matched a special syntax extension.", number);
            return true;
        }

        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;

        if (!effectiveNumber.matches("\\d+")) {
            log.info("isLikelyExtension: '{}' is not purely numeric and not in special syntax list.", number);
            return false;
        }
        
        // Check actual length against configured min/max actual lengths
        int numLength = effectiveNumber.length();
        if (numLength < extConfig.getMinActualLength() || numLength > extConfig.getMaxActualLength()) {
            log.info("isLikelyExtension: '{}' (length {}) is outside actual length range ({}-{}).",
                    number, numLength, extConfig.getMinActualLength(), extConfig.getMaxActualLength());
            return false;
        }

        // Additionally, check numeric value if lengths are within typical numeric ranges
        // This helps filter out very large numbers that happen to match length criteria
        // but are not valid extensions (e.g. if maxActualLength allows up to 7 digits,
        // but maxNumericValue is 9999, a 7-digit number like "1000000" would be out of numeric range)
        // However, the primary check should be length based on PHP's $_LIM_INTERNAS behavior.
        // The numeric value check is more of a sanity check for values within the length constraints.
        try {
            long numValue = Long.parseLong(effectiveNumber);
            boolean inNumericValueRange = (numValue >= extConfig.getMinNumericValue() && numValue <= extConfig.getMaxNumericValue());

            if (inNumericValueRange) {
                 log.info("isLikelyExtension: '{}' (length {}, value {}) is within actual length range ({}-{}) and numeric value range ({}-{}).",
                        number, numLength, numValue, extConfig.getMinActualLength(), extConfig.getMaxActualLength(), extConfig.getMinNumericValue(), extConfig.getMaxNumericValue());
            } else {
                 log.info("isLikelyExtension: '{}' (length {}, value {}) is within actual length range ({}-{}) but OUTSIDE numeric value range ({}-{}).",
                        number, numLength, numValue, extConfig.getMinActualLength(), extConfig.getMaxActualLength(), extConfig.getMinNumericValue(), extConfig.getMaxNumericValue());
                 // If it's within length but outside numeric value, it might still be an extension if it's not a special syntax one.
                 // PHP's $_LIM_INTERNAS['min'] and ['max'] were numeric values derived from lengths.
                 // The key is that it must be within the length boundaries derived from DB.
            }
            return true; // If it passed length check and is numeric, consider it. Numeric value range is secondary.
        } catch (NumberFormatException e) {
            log.info("isLikelyExtension: '{}' (effective '{}') failed numeric parse despite regex match and length check.", number, effectiveNumber);
            return false; // Should not happen if it passed matches("\\d+")
        }
    }


    public LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation);
        Long defaultOfficeId = null; 

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId(); 
            Long empOriginCountryId = defaultOriginCountryId; 
            Long empIndicatorId = defaultIndicatorId;     

            if (employee.getCommunicationLocationId() != null && employee.getCommunicationLocationId() > 0) {
                Optional<CommunicationLocation> empLocOpt = entityLookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = getOriginCountryId(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.info("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.info("Employee {} has CommLocationId {} assigned, but location not found or inactive. Using defaults/fallback.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            if (employee.getCostCenterId() != null && employee.getCostCenterId() > 0) {
                Optional<CostCenter> ccOpt = entityLookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    boolean commLocProvidedCountry = false;
                    if (employee.getCommunicationLocationId() != null && employee.getCommunicationLocationId() > 0) {
                         Optional<CommunicationLocation> empLocOpt = entityLookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                         if (empLocOpt.isPresent()) {
                             Long empLocCountryId = getOriginCountryId(empLocOpt.get());
                             if(empLocCountryId != null && !empLocCountryId.equals(defaultOriginCountryId)) {
                                 commLocProvidedCountry = true;
                             }
                         }
                    }
                    if (!commLocProvidedCountry) { 
                         empOriginCountryId = ccOpt.get().getOriginCountryId();
                         log.info("Using OriginCountry {} from Employee's CostCenter {} as employee's comm_location did not specify one or was default.", empOriginCountryId, employee.getCostCenterId());
                    }
                }
            }
            log.info("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        log.info("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    public String formatDestinationName(Map<String, Object> indicatorInfo) {
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown Destination");
    }

    public boolean isHourApplicable(String hoursSpecification, int callHour) {
        if (!StringUtils.hasText(hoursSpecification)) return true; 
        try {
            for (String part : hoursSpecification.split(",")) {
                String range = part.trim();
                if (range.contains("-")) {
                    String[] parts = range.split("-");
                    if (parts.length == 2) {
                        int start = Integer.parseInt(parts[0].trim());
                        int end = Integer.parseInt(parts[1].trim());
                        if (callHour >= start && callHour <= end) return true;
                    } else {
                        log.info("Invalid hour range format: {}", range);
                    }
                } else if (!range.isEmpty()) {
                    if (callHour == Integer.parseInt(range)) return true;
                }
            }
        } catch (Exception e) {
            log.info("Error parsing hoursSpecification: '{}'. Assuming not applicable.", hoursSpecification, e);
            return false; 
        }
        return false; 
    }

    public Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId, Long originCountryId) {
        if (trunk == null || telephonyTypeId == null || actualOperatorId == null || originCountryId == null) {
            log.info("findEffectiveTrunkOperator - Invalid input: trunk={}, ttId={}, actualOpId={}, ocId={}",
                    trunk != null ? trunk.getId() : "null", telephonyTypeId, actualOperatorId, originCountryId);
            return null; 
        }

        Optional<TrunkRate> specificRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.info("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.",
                    trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId; 
        }

        Optional<TrunkRate> globalRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId); // 0L for global/default operator
        if (globalRateOpt.isPresent()) {
            TrunkRate globalRate = globalRateOpt.get();
            if (globalRate.getNoPrefix() != null && globalRate.getNoPrefix()) {
                Optional<Operator> defaultTrunkOperatorOpt = configService.getOperatorInternal(telephonyTypeId, originCountryId);
                Long defaultTrunkOperatorId = defaultTrunkOperatorOpt.map(Operator::getId).orElse(null);

                boolean isPrefixUnique = prefixInfoLookupService.isPrefixUniqueToOperator(prefixCode, telephonyTypeId, originCountryId);

                if (actualOperatorId > 0 &&
                        !actualOperatorId.equals(defaultTrunkOperatorId) &&
                        isPrefixUnique) {
                    log.info("Global TrunkRate for trunk {} type {} has noPrefix. Actual op {} differs from default op {} for a unique prefix {}. Rule not applicable, returning null (PHP equivalent of -2).",
                            trunk.getId(), telephonyTypeId, actualOperatorId, defaultTrunkOperatorId, prefixCode);
                    return null; 
                }
            }
            log.info("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
            return 0L; 
        }

        log.info("No specific or global TrunkRate found for trunk {}, op {}, type {}. No effective trunk operator rule, returning null (PHP equivalent of -1).",
                trunk.getId(), actualOperatorId, telephonyTypeId);
        return null; 
    }

    public int maxNdcLength(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return 0;
        return prefixInfoLookupService.findNdcMinMaxLength(telephonyTypeId, originCountryId).getOrDefault("max", 0);
    }

    public Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) {
            log.info("getOriginCountryId called with null commLocation, falling back to default {}", CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID);
            return CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID;
        }
        
        Indicator indicatorEntity = commLocation.getIndicator();
        if (indicatorEntity != null && indicatorEntity.getOriginCountryId() != null) {
            return indicatorEntity.getOriginCountryId();
        }
        
        if (commLocation.getIndicatorId() != null && commLocation.getIndicatorId() > 0) {
            Optional<Indicator> indicatorOpt = entityLookupService.findIndicatorById(commLocation.getIndicatorId());
            if (indicatorOpt.isPresent() && indicatorOpt.get().getOriginCountryId() != null) {
                return indicatorOpt.get().getOriginCountryId();
            } else {
                log.info("Indicator {} linked to CommLocation {} not found or has no OriginCountryId.", commLocation.getIndicatorId(), commLocation.getId());
            }
        } else {
            log.info("CommLocation {} has no IndicatorId.", commLocation.getId());
        }
        log.info("Falling back to default OriginCountryId {} for CommLocation {}", CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID, commLocation.getId());
        return CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID;
    }

    public boolean isRateAssumedOrError(Map<String, Object> rateInfo, Long telephonyTypeId) {
        if (rateInfo == null || telephonyTypeId == null) return true; 
        if (telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_ERRORES)) return true;

        String destName = (String) rateInfo.getOrDefault("destination_name", "");
        String typeName = (String) rateInfo.getOrDefault("telephony_type_name", "");
        
        boolean isAssumed = destName.toLowerCase().contains("(assumed)") || typeName.toLowerCase().contains("(assumed)");
        boolean isNoTelephonyType = (Long) rateInfo.getOrDefault("telephony_type_id", 0L) <= 0;

        return isAssumed || isNoTelephonyType;
    }
}