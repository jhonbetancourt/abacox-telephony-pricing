package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component; // Changed to Component as it's a helper
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

@Component // Using Component as it's more of a utility/helper collection
@RequiredArgsConstructor
@Log4j2
public class CdrEnrichmentHelper {

    private final EntityLookupService entityLookupService;
    private final PrefixInfoLookupService prefixInfoLookupService;
    private final TrunkLookupService trunkLookupService;
    private final CdrProcessingConfig configService;

    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L; // For getOriginCountryId fallback

    public record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {} // Made public record

    public boolean isInternalCall(String callingNumber, String dialedNumber, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedNumber, dialedIsExt);
        return callingIsExt && dialedIsExt;
    }

    public boolean isLikelyExtension(String number, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return false;
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;

        if (effectiveNumber.length() > 1) {
            if (!effectiveNumber.substring(1).matches("\\d*")) { 
                 log.trace("isLikelyExtension: '{}' contains invalid characters after first.", number);
                 return false;
            }
        } else if (effectiveNumber.length() == 1) {
            if (!effectiveNumber.matches("[\\d#*]")) { 
                 log.trace("isLikelyExtension: single char '{}' is not digit, #, or *.", number);
                 return false;
            }
        }

        if (effectiveNumber.matches("\\d+")) {
            int numLength = effectiveNumber.length();
            if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
                log.trace("isLikelyExtension: '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
                return false;
            }
            try {
                long numValue = Long.parseLong(effectiveNumber);
                if (numValue > extConfig.getMaxExtensionValue()) {
                    log.trace("isLikelyExtension: '{}' value {} exceeds max value {}.", number, numValue, extConfig.getMaxExtensionValue());
                    return false;
                }
            } catch (NumberFormatException e) {
                log.warn("isLikelyExtension: '{}' failed numeric parse despite regex match.", number);
                return false;
            }
        } else if (!effectiveNumber.matches("[\\d#*]+")) { 
             log.trace("isLikelyExtension: '{}' contains invalid characters beyond digits, #, *.", number);
            return false;
        }
        
        int numLength = effectiveNumber.length();
         if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
            log.trace("isLikelyExtension: non-numeric '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
            return false;
        }

        log.trace("isLikelyExtension: '{}' is considered a likely extension.", number);
        return true;
    }

    public LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation); // Uses helper's getOriginCountryId
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
                    Long empLocCountryId = getOriginCountryId(empLoc); // Uses helper's getOriginCountryId
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found or inactive.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            if (employee.getCostCenterId() != null && employee.getCostCenterId() > 0) {
                Optional<CostCenter> ccOpt = entityLookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    if (employee.getCommunicationLocationId() == null || employee.getCommunicationLocationId() <= 0) {
                         empOriginCountryId = ccOpt.get().getOriginCountryId();
                         log.trace("Using OriginCountry {} from Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                    }
                }
            }
            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
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
                        log.warn("Invalid hour range format: {}", range);
                    }
                } else if (!range.isEmpty()) {
                    if (callHour == Integer.parseInt(range)) return true;
                }
            }
        } catch (Exception e) {
            log.error("Error parsing hoursSpecification: '{}'. Assuming not applicable.", hoursSpecification, e);
            return false;
        }
        return false;
    }

    public Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId, Long originCountryId) {
        if (trunk == null || telephonyTypeId == null || actualOperatorId == null || originCountryId == null) {
            return null;
        }

        Optional<TrunkRate> specificRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.trace("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.",
                    trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId;
        }

        Optional<TrunkRate> globalRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId);
        if (globalRateOpt.isPresent()) {
            TrunkRate globalRate = globalRateOpt.get();
            if (globalRate.getNoPrefix() != null && globalRate.getNoPrefix()) {
                Optional<Operator> defaultTrunkOperatorOpt = configService.getOperatorInternal(telephonyTypeId, originCountryId);
                Long defaultTrunkOperatorId = defaultTrunkOperatorOpt.map(Operator::getId).orElse(null);

                boolean isPrefixUnique = prefixInfoLookupService.isPrefixUniqueToOperator(prefixCode, telephonyTypeId, originCountryId);

                if (actualOperatorId > 0 &&
                        !actualOperatorId.equals(defaultTrunkOperatorId) &&
                        isPrefixUnique) {
                    log.trace("Global TrunkRate for trunk {} type {} has noPrefix. Actual op {} differs from default op {} for a unique prefix {}. Rule not applicable.",
                            trunk.getId(), telephonyTypeId, actualOperatorId, defaultTrunkOperatorId, prefixCode);
                    return null; 
                }
            }
            log.trace("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
            return 0L;
        }

        log.trace("No specific or global TrunkRate found for trunk {}, op {}, type {}. No effective trunk operator rule.",
                trunk.getId(), actualOperatorId, telephonyTypeId);
        return null; 
    }

    public int maxNdcLength(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return 0;
        return prefixInfoLookupService.findNdcMinMaxLength(telephonyTypeId, originCountryId).getOrDefault("max", 0);
    }
    
    public Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) return null;
        Indicator indicatorEntity = commLocation.getIndicator();
        if (indicatorEntity != null && indicatorEntity.getOriginCountryId() != null) {
            return indicatorEntity.getOriginCountryId();
        }
        if (commLocation.getIndicatorId() != null && commLocation.getIndicatorId() > 0) {
            Optional<Indicator> indicatorOpt = entityLookupService.findIndicatorById(commLocation.getIndicatorId());
            if (indicatorOpt.isPresent() && indicatorOpt.get().getOriginCountryId() != null) {
                return indicatorOpt.get().getOriginCountryId();
            } else {
                log.warn("Indicator {} linked to CommLocation {} not found or has no OriginCountryId.", commLocation.getIndicatorId(), commLocation.getId());
            }
        } else {
            log.warn("CommLocation {} has no IndicatorId.", commLocation.getId());
        }
        log.warn("Falling back to default OriginCountryId {} for CommLocation {}", COLOMBIA_ORIGIN_COUNTRY_ID, commLocation.getId());
        return COLOMBIA_ORIGIN_COUNTRY_ID;
    }

    public boolean isRateAssumedOrError(Map<String, Object> rateInfo, Long telephonyTypeId) {
        if (rateInfo == null || telephonyTypeId == null) return true; 
        if (telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_ERRORES)) return true;
        String destName = (String) rateInfo.getOrDefault("destination_name", "");
        String typeName = (String) rateInfo.getOrDefault("telephony_type_name", "");
        return destName.toLowerCase().contains("assumed") || typeName.toLowerCase().contains("assumed");
    }
}