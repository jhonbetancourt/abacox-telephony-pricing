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
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedNumber, dialedIsExt);
        return callingIsExt && dialedIsExt;
    }

    public boolean isLikelyExtension(String number, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return false;

        // Check against special syntax extensions first (PHP's $_LIM_INTERNAS['full'])
        if (extConfig.getSpecialSyntaxExtensions().contains(number)) {
            log.trace("isLikelyExtension: '{}' matched a special syntax extension.", number);
            return true;
        }

        // Proceed with numeric checks if not a special syntax extension
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;

        // PHP's ExtensionPosible checks if numeric and within derived numeric min/max.
        // It doesn't explicitly check for non-digit characters if it's not in 'full'.
        // However, the derived min/max are based on lengths of *numeric* extensions.
        // So, if it's not purely numeric, it wouldn't match the numeric range.
        if (!effectiveNumber.matches("\\d+")) {
            log.trace("isLikelyExtension: '{}' is not purely numeric and not in special syntax list.", number);
            return false;
        }

        try {
            long numValue = Long.parseLong(effectiveNumber);
            boolean inRange = (numValue >= extConfig.getMinNumericValue() && numValue <= extConfig.getMaxNumericValue());
            if (inRange) {
                log.trace("isLikelyExtension: '{}' (value {}) is within numeric range ({}-{}).", number, numValue, extConfig.getMinNumericValue(), extConfig.getMaxNumericValue());
            } else {
                log.trace("isLikelyExtension: '{}' (value {}) is outside numeric range ({}-{}).", number, numValue, extConfig.getMinNumericValue(), extConfig.getMaxNumericValue());
            }
            return inRange;
        } catch (NumberFormatException e) {
            log.warn("isLikelyExtension: '{}' (effective '{}') failed numeric parse despite regex match.", number, effectiveNumber);
            return false;
        }
    }


    public LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation);
        Long defaultOfficeId = null; // Office ID is typically subdivision_id from Employee

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId(); // This is the "office"
            Long empOriginCountryId = defaultOriginCountryId; // Start with default
            Long empIndicatorId = defaultIndicatorId;     // Start with default

            // Prefer employee's specific communication location if set
            if (employee.getCommunicationLocationId() != null && employee.getCommunicationLocationId() > 0) {
                Optional<CommunicationLocation> empLocOpt = entityLookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = getOriginCountryId(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found or inactive. Using defaults/fallback.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            // If no specific employee comm_location, try Cost Center's origin country
            // PHP logic: if ($subdireccionOrigen != '' && $oficinaBuscadaOrigen != $oficinaBuscadaDestino)
            // This implies that if an employee is found, their subdivision (office) and its country/indicator are primary.
            // The PHP code doesn't explicitly show Cost Center overriding an employee's direct location info,
            // but rather uses it if other info is missing.
            // Here, if employee's comm_location wasn't used, we can check cost center's country.
            // The indicator would still likely be the default or employee's comm_location's indicator if that was resolved.
            if (employee.getCostCenterId() != null && employee.getCostCenterId() > 0) {
                Optional<CostCenter> ccOpt = entityLookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    // Only override if the employee's specific comm_location didn't provide a country
                    if (empOriginCountryId == null || empOriginCountryId.equals(defaultOriginCountryId)) { // Check if it's still the default
                         empOriginCountryId = ccOpt.get().getOriginCountryId();
                         log.trace("Using OriginCountry {} from Employee's CostCenter {} as employee's comm_location did not specify one.", empOriginCountryId, employee.getCostCenterId());
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
        if (!StringUtils.hasText(hoursSpecification)) return true; // No spec means applicable all hours
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
            return false; // Error in parsing means not applicable
        }
        return false; // No matching range/hour found
    }

    public Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId, Long originCountryId) {
        if (trunk == null || telephonyTypeId == null || actualOperatorId == null || originCountryId == null) {
            log.trace("findEffectiveTrunkOperator - Invalid input: trunk={}, ttId={}, actualOpId={}, ocId={}",
                    trunk != null ? trunk.getId() : "null", telephonyTypeId, actualOperatorId, originCountryId);
            return null; // Or throw IllegalArgumentException
        }

        // PHP: if (isset($existe_troncal['operador_destino'][$operador_id][$tipotele_id]))
        Optional<TrunkRate> specificRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.trace("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.",
                    trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId; // Use the actual operator if a specific rate exists for it
        }

        // PHP: else { $operador_troncal = 0; ... if ($existe_troncal['operador_destino'][$operador_troncal][$tipotele_id]['noprefijo'] > 0) ... }
        Optional<TrunkRate> globalRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId); // 0L for global/default operator
        if (globalRateOpt.isPresent()) {
            TrunkRate globalRate = globalRateOpt.get();
            if (globalRate.getNoPrefix() != null && globalRate.getNoPrefix()) {
                // PHP: if ($operador_id > 0 && $operador_id != $_lista_Troncales['opxdefecto'][$tipotele_id] && count($_lista_Prefijos['ctlope'][$prefijo]) == 1)
                Optional<Operator> defaultTrunkOperatorOpt = configService.getOperatorInternal(telephonyTypeId, originCountryId);
                Long defaultTrunkOperatorId = defaultTrunkOperatorOpt.map(Operator::getId).orElse(null);

                boolean isPrefixUnique = prefixInfoLookupService.isPrefixUniqueToOperator(prefixCode, telephonyTypeId, originCountryId);

                if (actualOperatorId > 0 &&
                        !actualOperatorId.equals(defaultTrunkOperatorId) &&
                        isPrefixUnique) {
                    log.trace("Global TrunkRate for trunk {} type {} has noPrefix. Actual op {} differs from default op {} for a unique prefix {}. Rule not applicable, returning null (PHP equivalent of -2).",
                            trunk.getId(), telephonyTypeId, actualOperatorId, defaultTrunkOperatorId, prefixCode);
                    return null; // PHP returns -2, interpreted as not applicable
                }
            }
            log.trace("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
            return 0L; // Use global/default operator 0
        }

        log.trace("No specific or global TrunkRate found for trunk {}, op {}, type {}. No effective trunk operator rule, returning null (PHP equivalent of -1).",
                trunk.getId(), actualOperatorId, telephonyTypeId);
        return null; // PHP returns -1, no rule applicable
    }

    public int maxNdcLength(Long telephonyTypeId, Long originCountryId) {
        if (telephonyTypeId == null || originCountryId == null) return 0;
        return prefixInfoLookupService.findNdcMinMaxLength(telephonyTypeId, originCountryId).getOrDefault("max", 0);
    }

    public Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) {
            log.warn("getOriginCountryId called with null commLocation, falling back to default {}", CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID);
            return CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID;
        }
        // Prefer loaded entity if available
        Indicator indicatorEntity = commLocation.getIndicator();
        if (indicatorEntity != null && indicatorEntity.getOriginCountryId() != null) {
            return indicatorEntity.getOriginCountryId();
        }
        // Fallback to ID lookup if entity not loaded or lacks country
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
        log.warn("Falling back to default OriginCountryId {} for CommLocation {}", CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID, commLocation.getId());
        return CdrProcessingConfig.COLOMBIA_ORIGIN_COUNTRY_ID;
    }

    public boolean isRateAssumedOrError(Map<String, Object> rateInfo, Long telephonyTypeId) {
        if (rateInfo == null || telephonyTypeId == null) return true; // Treat null info as error/assumed
        if (telephonyTypeId.equals(CdrProcessingConfig.TIPOTELE_ERRORES)) return true;

        String destName = (String) rateInfo.getOrDefault("destination_name", "");
        String typeName = (String) rateInfo.getOrDefault("telephony_type_name", "");

        // PHP: strpos($infovalor['destino'], _ASUMIDO) !== false || strpos($infovalor['tipotele_nombre'], _ASUMIDO) !== false
        // Assuming _ASUMIDO is "(Assumed)"
        boolean isAssumed = destName.toLowerCase().contains("(assumed)") || typeName.toLowerCase().contains("(assumed)");
        boolean isNoTelephonyType = (Long) rateInfo.getOrDefault("telephony_type_id", 0L) <= 0;

        return isAssumed || isNoTelephonyType;
    }
}