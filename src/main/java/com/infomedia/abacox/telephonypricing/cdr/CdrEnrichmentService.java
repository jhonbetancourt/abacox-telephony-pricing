package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class CdrEnrichmentService {

    private final LookupService lookupService;
    private final CdrProcessingConfig configService;

    private static final BigDecimal SIXTY = new BigDecimal("60");
    private static final Long COLOMBIA_ORIGIN_COUNTRY_ID = 1L;
    private static final String ASSUMED_SUFFIX = " (Assumed)";

    public Optional<CallRecord> enrichCdr(StandardizedCallEventDto standardDto, CommunicationLocation commLocation) {
        log.info("Enriching Standardized CDR: {}, Hash: {}", standardDto.getGlobalCallId(), standardDto.getCdrHash());

        CallRecord.CallRecordBuilder callBuilder = CallRecord.builder();
        // --- Populate Builder from Standardized DTO ---
        callBuilder.commLocationId(commLocation.getId()); // Context
        callBuilder.commLocation(commLocation);
        callBuilder.serviceDate(standardDto.getCallStartTime());
        callBuilder.employeeExtension(standardDto.getCallingPartyNumber()); // Use effective number from DTO
        callBuilder.employeeAuthCode(standardDto.getAuthCode());
        callBuilder.destinationPhone(standardDto.getCalledPartyNumber()); // Use effective number from DTO
        callBuilder.dial(standardDto.getCalledPartyNumber()); // Use effective number from DTO
        callBuilder.duration(standardDto.getDurationSeconds());
        callBuilder.ringCount(standardDto.getRingDurationSeconds());
        callBuilder.isIncoming(standardDto.isIncoming()); // Use flag from DTO
        callBuilder.trunk(standardDto.getDestinationTrunkIdentifier());
        callBuilder.initialTrunk(standardDto.getSourceTrunkIdentifier());
        callBuilder.employeeTransfer(standardDto.getRedirectingPartyNumber());
        callBuilder.transferCause(CallTransferCause.fromValue(Optional.ofNullable(standardDto.getRedirectReason()).orElse(0)).getValue()); // Use reason from DTO
        callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue()); // Default, set later
        callBuilder.fileInfoId(standardDto.getFileInfoId());
        callBuilder.originIp(null); // Standard DTO doesn't have origin IP currently, add if needed
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.indicatorId(null);
        callBuilder.cdrHash(standardDto.getCdrHash()); // Set hash from DTO

        // --- Conference Handling (Simplified) ---
        if (standardDto.isConference()) {
            log.debug("CDR {} identified as conference leg.", standardDto.getGlobalCallId());
            // Parser should have already set the correct semantic caller/called/redirect
            // Assign cause and finalize
            callBuilder.assignmentCause(CallAssignmentCause.CONFERENCE.getValue());

            // Employee lookup based on the *effective* calling party provided by the parser
            Optional<Employee> confEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                    standardDto.getCallingPartyNumber(),
                    standardDto.getAuthCode(),
                    commLocation.getId()
            );
            confEmployeeOpt.ifPresent(employee -> {
                callBuilder.employeeId(employee.getId());
                callBuilder.employee(employee);
            });
            if (confEmployeeOpt.isEmpty()) {
                log.warn("Conference employee lookup failed for effective caller: {}", standardDto.getCallingPartyNumber());
            }

            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_INTERNA_IP); // Or dedicated type
            callBuilder.operatorId(null);
            callBuilder.indicatorId(null);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            // Transfer cause might be derivable from redirectReason if parser provides it, otherwise keep default or set specific

            linkAssociatedEntities(callBuilder);
            CallRecord finalRecord = callBuilder.build();
            log.info("Processed conference CDR {}: Type={}, Billed=0", standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId());
            return Optional.of(finalRecord); // Return early
        }
        // No need for post-conference check here if parser handles it via flags/hints

        // --- Regular Call Processing ---

        // --- Employee Lookup ---
        // Use effective calling party from standard DTO
        Optional<Employee> employeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(
                standardDto.getCallingPartyNumber(),
                standardDto.getAuthCode(),
                commLocation.getId()
        );
        employeeOpt.ifPresent(employee -> {
            callBuilder.employeeId(employee.getId());
            callBuilder.employee(employee);
            if (StringUtils.hasText(standardDto.getAuthCode())) {
                callBuilder.assignmentCause(CallAssignmentCause.AUTH_CODE.getValue());
            } else {
                callBuilder.assignmentCause(CallAssignmentCause.EXTENSION.getValue());
            }
        });
        if (employeeOpt.isEmpty()) {
            log.warn("Employee lookup failed for CDR {} (Effective Caller: {}, Code: {})",
                    standardDto.getGlobalCallId(), standardDto.getCallingPartyNumber(), standardDto.getAuthCode());
            callBuilder.assignmentCause(CallAssignmentCause.UNKNOWN.getValue());
        }


        // --- Determine Call Type and Enrich ---
        try {
            // Use isIncoming flag directly from standard DTO
            if (standardDto.isIncoming()) {
                // Pass effective calling number from DTO
                processIncomingCall(standardDto, commLocation, callBuilder, standardDto.getCallingPartyNumber());
            } else {
                // Pass effective calling and called numbers from DTO
                processOutgoingCall(standardDto, commLocation, callBuilder,
                        standardDto.getCallingPartyNumber(), standardDto.getCalledPartyNumber());
            }
        } catch (Exception e) {
            log.error("Error during enrichment for CDR {}: {}", standardDto.getGlobalCallId(), e.getMessage(), e);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            callBuilder.indicatorId(null);
        }

        // --- Final Adjustments ---
        CallRecord tempRecord = callBuilder.build();
        if (tempRecord.getDuration() != null && tempRecord.getDuration() <= 0
                && tempRecord.getTelephonyTypeId() != null
                && tempRecord.getTelephonyTypeId() != CdrProcessingConfig.TIPOTELE_ERRORES) {
            log.debug("Call duration is zero or less, setting TelephonyType to SINCONSUMO ({})", CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_SINCONSUMO);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }

        // Link associated entities
        linkAssociatedEntities(callBuilder);

        // Build final record
        CallRecord finalRecord = callBuilder.build();

        log.info("Successfully enriched CDR {}: Type={}, Billed={}",
                standardDto.getGlobalCallId(), finalRecord.getTelephonyTypeId(), finalRecord.getBilledAmount());
        return Optional.of(finalRecord);
    }


    // ========================================================================
    // Processing Logic Methods (processOutgoingCall, processIncomingCall, etc.)
    // (Keep the implementations from the previous step, ensuring they handle null indicatorId correctly)
    // ========================================================================

    // ... (Keep all other methods like processOutgoingCall, processIncomingCall, evaluateDestinationAndRate, etc. as they were in the previous corrected version) ...
    // ... (Ensure these methods correctly handle null indicatorId where applicable) ...

     // --- Helper Methods ---

    @Getter
    @Setter
    private static class FieldWrapper<T> {
        T value;

        FieldWrapper(T v) {
            this.value = v;
        }
    }

    private <T> void swapFields(FieldWrapper<T> field1, FieldWrapper<T> field2) {
        T temp = field1.getValue();
        field1.setValue(field2.getValue());
        field2.setValue(temp);
        log.trace("Swapped fields: {} <-> {}", field1.getValue(), field2.getValue());
    }

    private record LocationInfo(Long indicatorId, Long originCountryId, Long officeId) {
    }

    private LocationInfo getLocationInfo(Employee employee, CommunicationLocation defaultLocation) {
        Long defaultIndicatorId = defaultLocation.getIndicatorId();
        Long defaultOriginCountryId = getOriginCountryId(defaultLocation); // Use helper
        Long defaultOfficeId = null; // Office (Subdivision) is primarily from Employee

        if (employee != null) {
            Long empOfficeId = employee.getSubdivisionId(); // Subdivision ID represents the office/department
            Long empOriginCountryId = defaultOriginCountryId; // Start with default
            Long empIndicatorId = defaultIndicatorId; // Start with default

            // 1. Check Employee's assigned Communication Location (overrides default)
            if (employee.getCommunicationLocationId() != null) {
                Optional<CommunicationLocation> empLocOpt = lookupService.findCommunicationLocationById(employee.getCommunicationLocationId());
                if (empLocOpt.isPresent()) {
                    CommunicationLocation empLoc = empLocOpt.get();
                    empIndicatorId = empLoc.getIndicatorId() != null ? empLoc.getIndicatorId() : defaultIndicatorId;
                    Long empLocCountryId = getOriginCountryId(empLoc);
                    empOriginCountryId = empLocCountryId != null ? empLocCountryId : defaultOriginCountryId;
                    log.trace("Using location info from Employee's CommLocation {}: Indicator={}, Country={}", employee.getCommunicationLocationId(), empIndicatorId, empOriginCountryId);
                    return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
                } else {
                    log.warn("Employee {} has CommLocationId {} assigned, but location not found.", employee.getId(), employee.getCommunicationLocationId());
                }
            }

            // 2. Check Employee's Cost Center for Origin Country (if no specific CommLocation)
            if (employee.getCostCenterId() != null) {
                Optional<CostCenter> ccOpt = lookupService.findCostCenterById(employee.getCostCenterId());
                if (ccOpt.isPresent() && ccOpt.get().getOriginCountryId() != null) {
                    empOriginCountryId = ccOpt.get().getOriginCountryId();
                    log.trace("Using OriginCountry {} from Employee's CostCenter {}", empOriginCountryId, employee.getCostCenterId());
                }
            }

            log.trace("Final location info for Employee {}: Indicator={}, Country={}, Office={}", employee.getId(), empIndicatorId, empOriginCountryId, empOfficeId);
            return new LocationInfo(empIndicatorId, empOriginCountryId, empOfficeId);
        }

        log.trace("Using default location info: Indicator={}, Country={}, Office={}", defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
        return new LocationInfo(defaultIndicatorId, defaultOriginCountryId, defaultOfficeId);
    }

    private Long getOriginCountryId(CommunicationLocation commLocation) {
        if (commLocation == null) return null;
        Indicator indicator = commLocation.getIndicator();
        if (indicator != null && indicator.getOriginCountryId() != null) {
            return indicator.getOriginCountryId();
        }
        if (commLocation.getIndicatorId() != null) {
            Optional<Indicator> indicatorOpt = lookupService.findIndicatorById(commLocation.getIndicatorId());
            if (indicatorOpt.isPresent() && indicatorOpt.get().getOriginCountryId() != null) {
                return indicatorOpt.get().getOriginCountryId();
            } else {
                log.warn("Indicator {} linked to CommLocation {} not found or has no OriginCountryId.", commLocation.getIndicatorId(), commLocation.getId());
            }
        } else {
            log.warn("CommLocation {} has no IndicatorId.", commLocation.getId());
        }
        return null;
    }

    private BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue, boolean initialRateVatIncluded) {
        if (durationSeconds <= 0) return BigDecimal.ZERO;

        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);

        BigDecimal durationUnits;
        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
            log.trace("Calculating cost per second for {} seconds", durationSeconds);
        } else {
            durationUnits = new BigDecimal(durationSeconds).divide(SIXTY, 0, RoundingMode.CEILING);
            if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) {
                durationUnits = BigDecimal.ONE; // Minimum 1 minute charge
            }
            log.trace("Calculating cost per minute for {} seconds -> {} minutes", durationSeconds, durationUnits);
        }

        BigDecimal totalCost = effectiveRateValue.multiply(durationUnits);
        log.trace("Base cost (rate * duration): {} * {} = {}", effectiveRateValue, durationUnits, totalCost);

        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
            log.trace("Applied VAT ({}%), new total: {}", vatPercentage, totalCost);
        } else {
            log.trace("VAT already included in rate or VAT is zero/null.");
        }

        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("VAT divisor is zero, cannot remove VAT from {}", value);
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        return value.divide(vatDivisor, 4, RoundingMode.HALF_UP);
    }

    private void applySpecialServicePricing(SpecialService specialService, CallRecord.CallRecordBuilder callBuilder, int duration) {
        callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ESPECIALES);
        callBuilder.indicatorId(specialService.getIndicatorId() != null && specialService.getIndicatorId() > 0 ? specialService.getIndicatorId() : null);
        callBuilder.operatorId(null);

        BigDecimal price = Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable(specialService.getVatIncluded()).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO);

        BigDecimal billedAmount = price;
        if (!vatIncluded && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            billedAmount = billedAmount.multiply(vatMultiplier);
            log.trace("Applied VAT {}% to special service price {}", vatPercentage, price);
        }

        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.billedAmount(billedAmount.setScale(4, RoundingMode.HALF_UP));
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", price, billedAmount);
    }

    private void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
        Optional<Map<String, Object>> tariffOpt = lookupService.findInternalTariff(internalCallTypeId);
        if (tariffOpt.isPresent()) {
            Map<String, Object> tariff = tariffOpt.get();
            tariff.putIfAbsent("valor_minuto", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_minuto_iva", false);
            tariff.putIfAbsent("iva", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_inicial", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_inicial_iva", false);
            tariff.putIfAbsent("ensegundos", false);

            log.debug("Applying internal tariff for type {}: {}", internalCallTypeId, tariff);
            applyFinalPricing(tariff, duration, callBuilder);
        } else {
            log.warn("No internal tariff found for type {}, setting cost to zero.", internalCallTypeId);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            callBuilder.billedAmount(BigDecimal.ZERO);
        }
    }

    private void applyTrunkPricing(Trunk trunk, Map<String, Object> baseRateInfo, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) baseRateInfo.get("telephony_type_id");
        Long operatorId = (Long) baseRateInfo.get("operator_id");
        Long indicatorId = (Long) baseRateInfo.get("indicator_id");
        Long originCountryId = getOriginCountryId(trunk.getCommLocation());

        Optional<TrunkRate> trunkRateOpt = lookupService.findTrunkRate(trunk.getId(), operatorId, telephonyTypeId);

        if (trunkRateOpt.isPresent()) {
            TrunkRate trunkRate = trunkRateOpt.get();
            log.debug("Applying TrunkRate {} for trunk {}", trunkRate.getId(), trunk.getId());
            baseRateInfo.put("valor_minuto", Optional.ofNullable(trunkRate.getRateValue()).orElse(BigDecimal.ZERO));
            baseRateInfo.put("valor_minuto_iva", Optional.ofNullable(trunkRate.getIncludesVat()).orElse(false));
            baseRateInfo.put("ensegundos", trunkRate.getSeconds() != null && trunkRate.getSeconds() > 0);
            baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
            baseRateInfo.put("valor_inicial_iva", false);
            lookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, originCountryId)
                    .ifPresent(p -> baseRateInfo.put("iva", p.getVatValue()));
            applyFinalPricing(baseRateInfo, duration, callBuilder);
        } else {
            Optional<TrunkRule> trunkRuleOpt = lookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);
            if (trunkRuleOpt.isPresent()) {
                TrunkRule rule = trunkRuleOpt.get();
                log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());
                baseRateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
                baseRateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
                baseRateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);
                baseRateInfo.put("valor_inicial", BigDecimal.ZERO);
                baseRateInfo.put("valor_inicial_iva", false);

                Long finalOperatorId = operatorId;
                Long finalTelephonyTypeId = telephonyTypeId;

                if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                    finalOperatorId = rule.getNewOperatorId();
                    callBuilder.operatorId(finalOperatorId);
                    lookupService.findOperatorById(finalOperatorId).ifPresent(op -> baseRateInfo.put("operator_name", op.getName()));
                }
                if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                    finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                    callBuilder.telephonyTypeId(finalTelephonyTypeId);
                    lookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> baseRateInfo.put("telephony_type_name", tt.getName()));
                }

                Long finalTelephonyTypeId1 = finalTelephonyTypeId;
                Long finalOperatorId1 = finalOperatorId;
                lookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, finalOperatorId, originCountryId)
                        .ifPresentOrElse(
                                p -> baseRateInfo.put("iva", p.getVatValue()),
                                () -> {
                                    log.warn("No prefix found for rule-defined type {} / operator {}. Using default IVA 0.", finalTelephonyTypeId1, finalOperatorId1);
                                    baseRateInfo.put("iva", BigDecimal.ZERO);
                                }
                        );

                applyFinalPricing(baseRateInfo, duration, callBuilder);
            } else {
                log.debug("No specific TrunkRate or TrunkRule found for trunk {}, applying base/band/special pricing", trunk.getName());
                applySpecialPricing(baseRateInfo, callBuilder.build().getServiceDate(), duration, originIndicatorId, callBuilder);
            }
        }
    }

    private void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long operatorId = (Long) currentRateInfo.get("operator_id");
        Long bandId = (Long) currentRateInfo.get("band_id");

        List<SpecialRateValue> specialRates = lookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );
        Optional<SpecialRateValue> applicableRate = findApplicableSpecialRate(specialRates, callDateTime);

        if (applicableRate.isPresent()) {
            SpecialRateValue rate = applicableRate.get();
            log.debug("Applying SpecialRateValue {}", rate.getId());
            BigDecimal originalRate = (BigDecimal) currentRateInfo.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) currentRateInfo.get("valor_minuto_iva");

            currentRateInfo.put("valor_inicial", originalRate);
            currentRateInfo.put("valor_inicial_iva", originalVatIncluded);

            if (rate.getValueType() != null && rate.getValueType() == 1) { // Percentage discount
                 BigDecimal discountPercentage = Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO);
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
                currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                currentRateInfo.put("valor_minuto_iva", originalVatIncluded);
                currentRateInfo.put("descuento_p", discountPercentage);
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rate.getId());
            } else { // Fixed value override
                currentRateInfo.put("valor_minuto", Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO));
                currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(rate.getIncludesVat()).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", currentRateInfo.get("valor_minuto"), rate.getId());
            }
            currentRateInfo.put("ensegundos", false);
            applyFinalPricing(currentRateInfo, duration, callBuilder);
        } else {
            log.debug("No applicable special rate found, applying current rate.");
            currentRateInfo.put("valor_inicial", BigDecimal.ZERO);
            currentRateInfo.put("valor_inicial_iva", false);
            currentRateInfo.put("ensegundos", false);
            applyFinalPricing(currentRateInfo, duration, callBuilder);
        }
    }

    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
        BigDecimal pricePerMinute = Optional.ofNullable((BigDecimal) rateInfo.get("valor_minuto")).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_minuto_iva")).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable((BigDecimal) rateInfo.get("iva")).orElse(BigDecimal.ZERO);
        boolean chargePerSecond = Optional.ofNullable((Boolean) rateInfo.get("ensegundos")).orElse(false);
        BigDecimal initialPrice = Optional.ofNullable((BigDecimal) rateInfo.get("valor_inicial")).orElse(BigDecimal.ZERO);
        boolean initialVatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_inicial_iva")).orElse(false);

        BigDecimal calculatedBilledAmount = calculateBilledAmount(
                duration, pricePerMinute, vatIncluded, vatPercentage, chargePerSecond, initialPrice, initialVatIncluded
        );

        callBuilder.pricePerMinute(pricePerMinute.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(initialPrice.setScale(4, RoundingMode.HALF_UP));
        callBuilder.billedAmount(calculatedBilledAmount);
        log.trace("Final pricing applied: Rate={}, Initial={}, Billed={}", pricePerMinute, initialPrice, calculatedBilledAmount);
    }

    private String formatDestinationName(Map<String, Object> indicatorInfo) {
        String city = (String) indicatorInfo.get("city_name");
        String country = (String) indicatorInfo.get("department_country");
        if (StringUtils.hasText(city) && StringUtils.hasText(country)) return city + " (" + country + ")";
        return StringUtils.hasText(city) ? city : (StringUtils.hasText(country) ? country : "Unknown Destination");
    }

    private Optional<SpecialRateValue> findApplicableSpecialRate(List<SpecialRateValue> candidates, LocalDateTime callDateTime) {
        int callHour = callDateTime.getHour();
        return candidates.stream()
                .filter(rate -> isHourApplicable(rate.getHoursSpecification(), callHour))
                .findFirst();
    }

    private boolean isHourApplicable(String hoursSpecification, int callHour) {
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

    private String preprocessNumberForLookup(String number, Long originCountryId) {
        if (number == null || originCountryId == null || !originCountryId.equals(COLOMBIA_ORIGIN_COUNTRY_ID)) {
            return number; // Apply rules only for Colombia
        }

        int len = number.length();
        String originalNumber = number;
        String processedNumber = number;

        // --- Logic from _esCelular_fijo ---
        if (len == 10) {
            if (number.startsWith("3")) {
                if (number.matches("^3[0-4][0-9]\\d{7}$")) {
                    processedNumber = "03" + number;
                }
            } else if (number.startsWith("60")) {
                String nationalPrefix = determineNationalPrefix(number);
                if (nationalPrefix != null) {
                    processedNumber = nationalPrefix + number.substring(2);
                } else {
                    processedNumber = number.substring(2);
                }
            }
        }
        // --- Logic from _esEntrante_60 ---
        else if (len == 12) {
            if (number.startsWith("573") || number.startsWith("603")) {
                if (number.matches("^(57|60)3[0-4][0-9]\\d{7}$")) {
                    processedNumber = number.substring(2);
                    processedNumber = "03" + processedNumber;
                }
            } else if (number.startsWith("6060") || number.startsWith("5760")) {
                 if (number.matches("^(57|60)60\\d{8}$")) {
                    processedNumber = number.substring(4);
                }
            }
        } else if (len == 11) {
            if (number.startsWith("03")) {
                // Keep as is
            } else if (number.startsWith("604")) {
                 if (number.matches("^604\\d{8}$")) {
                    processedNumber = number.substring(3);
                }
            }
        } else if (len == 9 && number.startsWith("60")) {
             if (number.matches("^60\\d{7}$")) {
                processedNumber = number.substring(2);
            }
        }

        if (!originalNumber.equals(processedNumber)) {
            log.debug("Preprocessed number for lookup: {} -> {}", originalNumber, processedNumber);
        }
        return processedNumber;
    }

    private String determineNationalPrefix(String number10Digit) {
        if (number10Digit == null || !number10Digit.startsWith("60") || number10Digit.length() != 10) {
            return null;
        }
        String ndcStr = number10Digit.substring(2, 3);
        String subscriberNumberStr = number10Digit.substring(3);

        if (!ndcStr.matches("\\d") || !subscriberNumberStr.matches("\\d+")) {
            return null;
        }
        int ndc = Integer.parseInt(ndcStr);
        long subscriberNumber = Long.parseLong(subscriberNumberStr);

        Optional<Map<String, Object>> seriesInfoOpt = lookupService.findSeriesInfoForNationalLookup(ndc, subscriberNumber);

        if (seriesInfoOpt.isPresent()) {
            String company = (String) seriesInfoOpt.get().get("series_company");
            if (company != null) {
                company = company.toUpperCase();
                if (company.contains("TELMEX")) return "0456";
                if (company.contains("COLOMBIA TELECOMUNICACIONES")) return "09";
                if (company.contains("UNE EPM")) return "05";
                if (company.contains("EMPRESA DE TELECOMUNICACIONES DE BOGOTÁ")) return "07";
            }
        }
        return null;
    }

     private String cleanNumber(String number, List<String> pbxPrefixes, boolean removePrefix) {
        if (!StringUtils.hasText(number)) return "";
        String cleaned = number.trim();
        int prefixLength = 0;

        if (removePrefix && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            prefixLength = getPrefixLength(cleaned, pbxPrefixes);
            if (prefixLength > 0) {
                cleaned = cleaned.substring(prefixLength);
                log.trace("Removed PBX prefix (length {}) from {}, result: {}", prefixLength, number, cleaned);
            } else {
                log.trace("Prefix removal requested but no matching prefix found in {}", number);
            }
        }

        boolean hasPlus = cleaned.startsWith("+");
        cleaned = cleaned.replaceAll("[^0-9#*]", "");
        if (hasPlus && !cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }
        return cleaned;
    }

    private int getPrefixLength(String number, List<String> pbxPrefixes) {
        int longestMatchLength = 0;
        if (number != null && pbxPrefixes != null && !pbxPrefixes.isEmpty()) {
            for (String prefix : pbxPrefixes) {
                String trimmedPrefix = prefix != null ? prefix.trim() : "";
                if (!trimmedPrefix.isEmpty() && number.startsWith(trimmedPrefix)) {
                    if (trimmedPrefix.length() > longestMatchLength) {
                        longestMatchLength = trimmedPrefix.length();
                    }
                }
            }
        }
        return longestMatchLength;
    }

    private boolean isInternalCall(String callingNumber, String dialedNumber, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        boolean callingIsExt = isLikelyExtension(callingNumber, extConfig);
        boolean dialedIsExt = isLikelyExtension(dialedNumber, extConfig);
        log.trace("isInternalCall check: Caller '{}' (isExt: {}) -> Dialed '{}' (isExt: {})", callingNumber, callingIsExt, dialedNumber, dialedIsExt);
        return callingIsExt && dialedIsExt;
    }

    private boolean isLikelyExtension(String number, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        if (!StringUtils.hasText(number)) return false;
        String effectiveNumber = number.startsWith("+") ? number.substring(1) : number;

        if (!effectiveNumber.matches("[\\d#*]+")) {
            log.trace("isLikelyExtension: '{}' contains invalid characters.", number);
            return false;
        }

        int numLength = effectiveNumber.length();
        if (numLength < extConfig.getMinLength() || numLength > extConfig.getMaxLength()) {
            log.trace("isLikelyExtension: '{}' length {} outside range ({}-{}).", number, numLength, extConfig.getMinLength(), extConfig.getMaxLength());
            return false;
        }

        try {
            if (effectiveNumber.matches("\\d+")) {
                long numValue = Long.parseLong(effectiveNumber);
                if (numValue > extConfig.getMaxExtensionValue()) {
                    log.trace("isLikelyExtension: '{}' value {} exceeds max value {}.", number, numValue, extConfig.getMaxExtensionValue());
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            log.trace("isLikelyExtension: '{}' contains non-digits, skipping max value check.", number);
        }
        log.trace("isLikelyExtension: '{}' is considered a likely extension.", number);
        return true;
    }

     private Long findEffectiveTrunkOperator(Trunk trunk, Long telephonyTypeId, String prefixCode, Long actualOperatorId) {
        Optional<TrunkRate> specificRateOpt = lookupService.findTrunkRate(trunk.getId(), actualOperatorId, telephonyTypeId);
        if (specificRateOpt.isPresent()) {
            log.trace("Found specific TrunkRate for trunk {}, op {}, type {}. Using operator {}.", trunk.getId(), actualOperatorId, telephonyTypeId, actualOperatorId);
            return actualOperatorId;
        }

        Optional<TrunkRate> globalRateOpt = lookupService.findTrunkRate(trunk.getId(), 0L, telephonyTypeId);
        if (globalRateOpt.isPresent()) {
             if (globalRateOpt.get().getNoPrefix() != null && globalRateOpt.get().getNoPrefix()) {
                 Long defaultOperatorId = configService.getOperatorInternal(telephonyTypeId, getOriginCountryId(trunk.getCommLocation()))
                                                      .map(Operator::getId).orElse(null);
                 if (actualOperatorId != null && !actualOperatorId.equals(defaultOperatorId)) {
                     log.trace("Global TrunkRate ignores prefix, but actual operator {} is not the default for type {}. Operator rule not applicable.", actualOperatorId, telephonyTypeId);
                     return null;
                 }
             }
             log.trace("Found global TrunkRate for trunk {}, type {}. Using operator 0.", trunk.getId(), telephonyTypeId);
             return 0L;
        }

        log.trace("No specific or global TrunkRate found for trunk {}, op {}, type {}. Defaulting to operator 0 for potential rule lookup.", trunk.getId(), actualOperatorId, telephonyTypeId);
        return 0L;
    }

    // Add the missing processOutgoingCall method implementation here
    // (Copy the implementation from the previous corrected version)
    private void processOutgoingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber, String effectiveDialedNumber) {
        log.debug("Processing outgoing call for CDR {} (effective: {} -> {})", standardDto.getGlobalCallId(), effectiveCallingNumber, effectiveDialedNumber);

        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());
        boolean shouldRemovePrefixInitially = getPrefixLength(effectiveDialedNumber, pbxPrefixes) > 0;
        String cleanedNumberForDial = cleanNumber(effectiveDialedNumber, pbxPrefixes, shouldRemovePrefixInitially);
        // Set dial field in the builder if not already set, or override if needed
        callBuilder.dial(cleanedNumberForDial);
        log.trace("Cleaned number for DIAL field: {}", cleanedNumberForDial);

        CdrProcessingConfig.ExtensionLengthConfig extConfig = configService.getExtensionLengthConfig(commLocation.getId());
        if (isInternalCall(effectiveCallingNumber, cleanedNumberForDial, extConfig)) {
            log.debug("Processing as internal call (effective: {} -> {})", effectiveCallingNumber, cleanedNumberForDial);
            // Pass standardDto for context if needed by internal processing
            processInternalCall(standardDto, commLocation, callBuilder, cleanedNumberForDial, extConfig);
            return;
        }

        Long originCountryId = getOriginCountryId(commLocation);
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                effectiveDialedNumber, commLocation.getId(), CallDirection.OUTGOING.getValue()
        );
        String numberAfterPbxRule = effectiveDialedNumber;
        if (pbxRuleOpt.isPresent()) {
            // ... (apply PBX rule logic as before) ...
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && effectiveDialedNumber.startsWith(searchPattern)) {
                String numberAfterSearch = effectiveDialedNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                log.debug("Applied OUTGOING PBX rule {}, number changed to {}", rule.getId(), numberAfterPbxRule);
            }
        }

        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            // ... (set forcedTelephonyType based on preprocessed number) ...
            log.debug("Number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) { // Check if it looks like a local number AFTER preprocessing
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
            }
        }

        Long indicatorIdForSpecial = commLocation.getIndicatorId();
        if (indicatorIdForSpecial != null && originCountryId != null) {
            String numberForSpecialCheck = cleanNumber(preprocessedNumber, Collections.emptyList(), false);
            Optional<SpecialService> specialServiceOpt = lookupService.findSpecialService(numberForSpecialCheck, indicatorIdForSpecial, originCountryId);
            if (specialServiceOpt.isPresent()) {
                // ... (apply special service pricing) ...
                SpecialService specialService = specialServiceOpt.get();
                log.debug("Call matches Special Service: {}", specialService.getId());
                applySpecialServicePricing(specialService, callBuilder, standardDto.getDurationSeconds());
                callBuilder.dial(numberForSpecialCheck); // Update dial field
                return; // Finished
            }
        }

        log.debug("Processing as external outgoing call (effective: {} -> preprocessed: {})", effectiveCallingNumber, preprocessedNumber);
        // Pass standardDto for context (e.g., trunk info)
        evaluateDestinationAndRate(standardDto, commLocation, callBuilder, preprocessedNumber, pbxPrefixes, forcedTelephonyType.getValue());
    }


    private void processIncomingCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String effectiveCallingNumber) {
        // (Refactor similarly to processOutgoingCall, using standardDto fields)
        log.debug("Processing incoming call for CDR {} (effective caller: {})", standardDto.getGlobalCallId(), effectiveCallingNumber);

        String originalIncomingNumber = effectiveCallingNumber;
        Long originCountryId = getOriginCountryId(commLocation);
        Long commIndicatorId = commLocation.getIndicatorId();
        List<String> pbxPrefixes = configService.getPbxPrefixes(commLocation.getId());

        // Apply PBX Special Rule (Direction INCOMING)
        Optional<PbxSpecialRule> pbxRuleOpt = lookupService.findPbxSpecialRule(
                originalIncomingNumber, commLocation.getId(), CallDirection.INCOMING.getValue()
        );
        String numberAfterPbxRule = originalIncomingNumber;
        if (pbxRuleOpt.isPresent()) {
            PbxSpecialRule rule = pbxRuleOpt.get();
            String replacement = rule.getReplacement() != null ? rule.getReplacement() : "";
            String searchPattern = rule.getSearchPattern();
            if (StringUtils.hasText(searchPattern) && originalIncomingNumber.startsWith(searchPattern)) {
                String numberAfterSearch = originalIncomingNumber.substring(searchPattern.length());
                numberAfterPbxRule = replacement + numberAfterSearch;
                log.debug("Applied INCOMING PBX rule {} to incoming number, result: {}", rule.getId(), numberAfterPbxRule);
            }
        }

        // Preprocess Number (Colombian rules)
        String preprocessedNumber = preprocessNumberForLookup(numberAfterPbxRule, originCountryId);
        FieldWrapper<Long> forcedTelephonyType = new FieldWrapper<>(null);
        if (!preprocessedNumber.equals(numberAfterPbxRule)) {
            log.debug("Incoming number preprocessed for lookup: {} -> {}", numberAfterPbxRule, preprocessedNumber);
            if (preprocessedNumber.startsWith("03") && preprocessedNumber.length() == 12) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_CELULAR);
            } else if (preprocessedNumber.matches("^\\d{7,8}$")) {
                forcedTelephonyType.setValue(CdrProcessingConfig.TIPOTELE_LOCAL);
            }
        }

        // Determine number for origin lookup
        String numberForLookup;
        int prefixLen = getPrefixLength(numberAfterPbxRule, pbxPrefixes);
        if (prefixLen > 0) {
            numberForLookup = numberAfterPbxRule.substring(prefixLen);
            numberForLookup = cleanNumber(numberForLookup, Collections.emptyList(), false);
            log.trace("Incoming call had PBX prefix, looking up origin for: {}", numberForLookup);
        } else {
            numberForLookup = preprocessedNumber;
            log.trace("Incoming call had no PBX prefix, looking up origin for: {}", numberForLookup);
        }

        // Set 'dial' field (caller's number for incoming)
        callBuilder.dial(cleanNumber(preprocessedNumber, Collections.emptyList(), false));

        // Determine Origin
        if (originCountryId != null && commIndicatorId != null) {
            List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(numberForLookup, originCountryId);

            // Filter if type was forced
            if (forcedTelephonyType.getValue() != null) {
                Long forcedType = forcedTelephonyType.getValue();
                prefixes = prefixes.stream()
                        .filter(p -> forcedType.equals(p.get("telephony_type_id")))
                        .collect(Collectors.toList());
                if (prefixes.isEmpty())
                    log.warn("Forced TelephonyType ID {} has no matching prefixes for incoming lookup number {}", forcedType, numberForLookup);
            }

            Optional<Map<String, Object>> bestPrefix = prefixes.stream().findFirst();

            if (bestPrefix.isPresent()) {
                Map<String, Object> prefixInfo = bestPrefix.get();
                Long telephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
                Long operatorId = (Long) prefixInfo.get("operator_id");
                String prefixCode = (String) prefixInfo.get("prefix_code");

                callBuilder.telephonyTypeId(telephonyTypeId);
                callBuilder.operatorId(operatorId);

                String numberWithoutPrefix = numberForLookup;
                if (StringUtils.hasText(prefixCode) && numberForLookup.startsWith(prefixCode)) {
                    numberWithoutPrefix = numberForLookup.substring(prefixCode.length());
                }

                Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(numberWithoutPrefix, telephonyTypeId, originCountryId);
                Long indicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null);
                callBuilder.indicatorId(indicatorId);

                log.debug("Incoming call classified as Type ID: {}, Operator ID: {}, Indicator ID: {}",
                        telephonyTypeId, operatorId, indicatorId);

            } else {
                // Fallback: Assume LOCAL
                log.warn("Could not classify incoming call origin for {}, assuming LOCAL", numberForLookup);
                callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_LOCAL);
                callBuilder.indicatorId(commIndicatorId); // Use location's own indicator
                configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_LOCAL, originCountryId)
                        .ifPresent(op -> callBuilder.operatorId(op.getId()));
            }
        } else {
            log.warn("Missing Origin Country or Indicator for CommunicationLocation {}, cannot classify incoming call origin.", commLocation.getId());
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.indicatorId(null);
        }

        // Incoming calls typically zero cost
        callBuilder.billedAmount(BigDecimal.ZERO);
        callBuilder.pricePerMinute(BigDecimal.ZERO);
        callBuilder.initialPrice(BigDecimal.ZERO);
    }

    private void processInternalCall(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String destinationExtension, CdrProcessingConfig.ExtensionLengthConfig extConfig) {
        // (Refactor similarly, using standardDto fields)
        String sourceExtension = standardDto.getCallingPartyNumber(); // Use effective caller
        log.debug("Processing internal call from {} to {}", sourceExtension, destinationExtension);

        Optional<Employee> sourceEmployeeOpt = Optional.ofNullable(callBuilder.build().getEmployee());
        Optional<Employee> destEmployeeOpt = lookupService.findEmployeeByExtensionOrAuthCode(destinationExtension, null, commLocation.getId());

        LocationInfo sourceLoc = getLocationInfo(sourceEmployeeOpt.orElse(null), commLocation);
        LocationInfo destLoc = getLocationInfo(destEmployeeOpt.orElse(null), commLocation);

        Long internalCallTypeId = null;
        Long operatorId = null;
        Long indicatorId = null; // Start with null

        if (destEmployeeOpt.isPresent()) {
            callBuilder.destinationEmployeeId(destEmployeeOpt.get().getId());
            callBuilder.destinationEmployee(destEmployeeOpt.get());
            indicatorId = destLoc.indicatorId;

            if (sourceLoc.originCountryId != null && destLoc.originCountryId != null && !sourceLoc.originCountryId.equals(destLoc.originCountryId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNACIONAL_IP;
            } else if (sourceLoc.indicatorId != null && destLoc.indicatorId != null && !sourceLoc.indicatorId.equals(destLoc.indicatorId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_NACIONAL_IP;
            } else if (sourceLoc.officeId != null && destLoc.officeId != null && !sourceLoc.officeId.equals(destLoc.officeId)) {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_IP;
            } else {
                internalCallTypeId = CdrProcessingConfig.TIPOTELE_INTERNA_IP;
            }
            log.debug("Internal call type determined by location comparison: {}", internalCallTypeId);

        } else {
            log.warn("Internal call destination extension {} not found as employee.", destinationExtension);
            Optional<Map<String, Object>> internalPrefixOpt = lookupService.findInternalPrefixMatch(destinationExtension, sourceLoc.originCountryId);
            if (internalPrefixOpt.isPresent()) {
                internalCallTypeId = (Long) internalPrefixOpt.get().get("telephony_type_id");
                operatorId = (Long) internalPrefixOpt.get().get("operator_id");
                Optional<Map<String, Object>> indInfo = lookupService.findIndicatorByNumber(destinationExtension, internalCallTypeId, sourceLoc.originCountryId);
                indicatorId = indInfo.map(m -> (Long)m.get("indicator_id")).filter(id -> id != null && id > 0).orElse(null);
                log.debug("Destination {} matched internal prefix for type {}, Op {}, Ind {}", destinationExtension, internalCallTypeId, operatorId, indicatorId);
            } else {
                internalCallTypeId = CdrProcessingConfig.getDefaultInternalCallTypeId();
                indicatorId = sourceLoc.indicatorId;
                log.debug("Destination {} not found and no internal prefix matched, defaulting to type {}, Ind {}", destinationExtension, internalCallTypeId, indicatorId);
            }
        }

        callBuilder.telephonyTypeId(internalCallTypeId);
        callBuilder.indicatorId(indicatorId != null && indicatorId > 0 ? indicatorId : null);

        if (operatorId == null) {
            configService.getOperatorInternal(internalCallTypeId, sourceLoc.originCountryId)
                    .ifPresent(op -> callBuilder.operatorId(op.getId()));
        } else {
            callBuilder.operatorId(operatorId);
        }

        applyInternalPricing(internalCallTypeId, callBuilder, standardDto.getDurationSeconds());
    }

    // Modify signature
    private void evaluateDestinationAndRate(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Long forcedTelephonyTypeId) {
        // Use standardDto.getDestinationTrunkIdentifier() for trunk lookup
        Optional<Trunk> trunkOpt = lookupService.findTrunkByCode(standardDto.getDestinationTrunkIdentifier(), commLocation.getId());
        boolean usesTrunk = trunkOpt.isPresent();
        Long originIndicatorId = commLocation.getIndicatorId();

        // Pass standardDto down
        Optional<Map<String, Object>> finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, trunkOpt, forcedTelephonyTypeId);

        if (finalRateInfoOpt.isEmpty() && usesTrunk) {
            log.warn("Initial rate lookup failed for trunk call {}, attempting fallback (no trunk info)", standardDto.getGlobalCallId());
            // Pass standardDto down
            finalRateInfoOpt = attemptRateLookup(standardDto, commLocation, callBuilder, initialNumberToLookup, pbxPrefixes, Optional.empty(), forcedTelephonyTypeId);
        }

        if (finalRateInfoOpt.isPresent()) {
            // ... (apply results as before, setting indicatorId potentially to null) ...
            Map<String, Object> finalRateInfo = finalRateInfoOpt.get();
            callBuilder.telephonyTypeId((Long) finalRateInfo.get("telephony_type_id"));
            callBuilder.operatorId((Long) finalRateInfo.get("operator_id"));
            callBuilder.indicatorId((Long) finalRateInfo.get("indicator_id")); // Can be null
            callBuilder.dial((String) finalRateInfo.getOrDefault("effective_number", initialNumberToLookup));

            boolean appliedTrunkPricing = finalRateInfo.containsKey("applied_trunk_pricing") && (Boolean) finalRateInfo.get("applied_trunk_pricing");
            if (appliedTrunkPricing && usesTrunk) {
                applyTrunkPricing(trunkOpt.get(), finalRateInfo, standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
            } else {
                applySpecialPricing(finalRateInfo, standardDto.getCallStartTime(), standardDto.getDurationSeconds(), originIndicatorId, callBuilder);
            }
        } else {
            // ... (handle error as before, ensure indicatorId is null) ...
            log.error("Could not determine rate for number: {} (effective: {}) after fallback.", standardDto.getCalledPartyNumber(), initialNumberToLookup);
            callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ERRORES);
            callBuilder.dial(initialNumberToLookup);
            callBuilder.indicatorId(null);
            callBuilder.billedAmount(BigDecimal.ZERO);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
        }
    }

    private Optional<Map<String, Object>> attemptRateLookup(StandardizedCallEventDto standardDto, CommunicationLocation commLocation, CallRecord.CallRecordBuilder callBuilder, String initialNumberToLookup, List<String> pbxPrefixes, Optional<Trunk> trunkOpt, Long forcedTelephonyTypeId) {
        // (Refactor similarly, using standardDto fields where appropriate)
        Long originCountryId = getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        boolean usesTrunk = trunkOpt.isPresent();
        Trunk trunk = trunkOpt.orElse(null);

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot attempt rate lookup: Missing Origin Country ({}) or Indicator ID ({}) for Location {}", originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        String effectiveNumber = initialNumberToLookup;
        if (usesTrunk && trunk.getNoPbxPrefix() != null && trunk.getNoPbxPrefix()) {
            // Use calledPartyNumber from standard DTO as it's the effective one
            effectiveNumber = cleanNumber(standardDto.getCalledPartyNumber(), Collections.emptyList(), false);
            log.trace("Attempting lookup (Trunk {} ignores PBX prefix): {}", trunk.getId(), effectiveNumber);
        } else {
            log.trace("Attempting lookup (Effective number): {}", effectiveNumber);
        }

        List<Map<String, Object>> prefixes = lookupService.findPrefixesByNumber(effectiveNumber, originCountryId);

        if (forcedTelephonyTypeId != null) {
            prefixes = prefixes.stream()
                    .filter(p -> forcedTelephonyTypeId.equals(p.get("telephony_type_id")))
                    .collect(Collectors.toList());
            if (!prefixes.isEmpty()) {
                log.debug("Lookup filtered to forced TelephonyType ID: {}", forcedTelephonyTypeId);
            } else {
                log.warn("Forced TelephonyType ID {} has no matching prefixes for number {}", forcedTelephonyTypeId, effectiveNumber);
            }
        }

        Map<String, Object> assumedRateInfo = null;
        boolean allPrefixesConsistent = !prefixes.isEmpty();
        Long firstOperatorId = null;
        Long firstTelephonyTypeId = null;

        for (Map<String, Object> prefixInfo : prefixes) {
            Long currentTelephonyTypeId = (Long) prefixInfo.get("telephony_type_id");
            Long currentOperatorId = (Long) prefixInfo.get("operator_id");
            String currentPrefixCode = (String) prefixInfo.get("prefix_code");
            Long currentPrefixId = (Long) prefixInfo.get("prefix_id");
            int typeMinLength = (Integer) prefixInfo.get("telephony_type_min");
            int typeMaxLength = (Integer) prefixInfo.get("telephony_type_max");
            boolean bandOk = (Boolean) prefixInfo.get("prefix_band_ok");

            if (firstOperatorId == null) {
                firstOperatorId = currentOperatorId;
                firstTelephonyTypeId = currentTelephonyTypeId;
            } else if (!firstOperatorId.equals(currentOperatorId) || !firstTelephonyTypeId.equals(currentTelephonyTypeId)) {
                allPrefixesConsistent = false;
            }

            String numberWithoutPrefix = effectiveNumber;
            boolean prefixRemoved = false;
            boolean removePrefixForLookup = true;

            if (StringUtils.hasText(currentPrefixCode) && effectiveNumber.startsWith(currentPrefixCode)) {
                if (usesTrunk) {
                    Long operatorTroncal = findEffectiveTrunkOperator(trunk, currentTelephonyTypeId, currentPrefixCode, currentOperatorId);
                    if (operatorTroncal != null && operatorTroncal >= 0) {
                        Optional<TrunkRate> trOpt = lookupService.findTrunkRate(trunk.getId(), operatorTroncal, currentTelephonyTypeId);
                        if (trOpt.isPresent() && trOpt.get().getNoPrefix() != null && trOpt.get().getNoPrefix()) {
                            removePrefixForLookup = false;
                            log.trace("TrunkRate for prefix {} (effective op {}) prevents prefix removal during indicator lookup", currentPrefixCode, operatorTroncal);
                        }
                    } else {
                        log.trace("No matching trunk operator rule found for prefix {}, type {}, op {}. Defaulting prefix removal.", currentPrefixCode, currentTelephonyTypeId, currentOperatorId);
                    }
                }
                if (removePrefixForLookup && currentTelephonyTypeId != CdrProcessingConfig.TIPOTELE_LOCAL) {
                    numberWithoutPrefix = effectiveNumber.substring(currentPrefixCode.length());
                    prefixRemoved = true;
                    log.trace("Prefix {} removed for indicator lookup (Type: {}), remaining: {}", currentPrefixCode, currentTelephonyTypeId, numberWithoutPrefix);
                } else if (removePrefixForLookup) {
                    log.trace("Prefix {} not removed for indicator lookup (Type: {})", currentPrefixCode, currentTelephonyTypeId);
                    removePrefixForLookup = false;
                }
            } else {
                removePrefixForLookup = false;
            }

            int prefixLength = (currentPrefixCode != null ? currentPrefixCode.length() : 0);
            int effectiveMinLength = prefixRemoved ? Math.max(0, typeMinLength - prefixLength) : typeMinLength;
            int effectiveMaxLength = prefixRemoved ? Math.max(0, typeMaxLength - prefixLength) : typeMaxLength;

            if (numberWithoutPrefix.length() < effectiveMinLength) {
                log.trace("Skipping prefix {} - number part {} too short (min {})", currentPrefixCode, numberWithoutPrefix, effectiveMinLength);
                continue;
            }
            if (effectiveMaxLength > 0 && numberWithoutPrefix.length() > effectiveMaxLength) {
                log.trace("Trimming number part {} to max length {}", numberWithoutPrefix, effectiveMaxLength);
                numberWithoutPrefix = numberWithoutPrefix.substring(0, effectiveMaxLength);
            }

            Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                    numberWithoutPrefix, currentTelephonyTypeId, originCountryId
            );

            boolean destinationFound = indicatorInfoOpt.isPresent();
            boolean lengthMatch = (effectiveMaxLength > 0 && numberWithoutPrefix.length() == effectiveMaxLength && !destinationFound);
            boolean considerMatch = destinationFound || lengthMatch;

            if (considerMatch) {
                Long destinationIndicatorId = indicatorInfoOpt
                        .map(ind -> (Long) ind.get("indicator_id"))
                        .filter(id -> id != null && id > 0)
                        .orElse(null);
                Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null);

                Long finalTelephonyTypeId = currentTelephonyTypeId;
                Long finalPrefixId = currentPrefixId;
                boolean finalBandOk = bandOk;
                if (currentTelephonyTypeId == CdrProcessingConfig.TIPOTELE_LOCAL && destinationNdc != null) {
                    boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
                    if (isExtended) {
                        finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                        log.debug("Reclassified call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                        Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                        if (localExtOpOpt.isPresent()) {
                            Optional<Prefix> localExtPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                            if (localExtPrefixOpt.isPresent()) {
                                finalPrefixId = localExtPrefixOpt.get().getId();
                                finalBandOk = localExtPrefixOpt.get().isBandOk();
                                log.trace("Using Prefix ID {} for LOCAL_EXTENDED rate lookup", finalPrefixId);
                            } else {
                                log.warn("Could not find prefix for LOCAL_EXTENDED type {}, operator {}, country {}. Rate lookup might be incorrect.", finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                            }
                        } else {
                            log.warn("Could not find internal operator for LOCAL_EXTENDED type {}. Rate lookup might be incorrect.", finalTelephonyTypeId);
                        }
                    }
                }

                Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

                if (rateInfoOpt.isPresent()) {
                    Map<String, Object> rateInfo = rateInfoOpt.get();
                    rateInfo.put("telephony_type_id", finalTelephonyTypeId);
                    rateInfo.put("operator_id", currentOperatorId);
                    rateInfo.put("indicator_id", destinationIndicatorId); // Pass potentially null ID
                    rateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
                    rateInfo.put("operator_name", lookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                    rateInfo.put("destination_name", indicatorInfoOpt.map(this::formatDestinationName).orElse(lengthMatch ? "Unknown (Length Match)" : "Unknown Destination"));
                    rateInfo.put("band_name", rateInfo.get("band_name"));
                    rateInfo.put("effective_number", effectiveNumber);
                    rateInfo.put("applied_trunk_pricing", usesTrunk);

                    log.debug("Attempt successful: Found rate for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    return Optional.of(rateInfo);
                } else {
                    log.warn("Rate info not found for prefix {}, indicator {}", currentPrefixCode, destinationIndicatorId);
                    if (assumedRateInfo == null && allPrefixesConsistent) {
                        assumedRateInfo = new HashMap<>();
                        assumedRateInfo.put("telephony_type_id", finalTelephonyTypeId);
                        assumedRateInfo.put("operator_id", currentOperatorId);
                        assumedRateInfo.put("indicator_id", null); // Assumed has null indicator
                        assumedRateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type") + ASSUMED_SUFFIX);
                        assumedRateInfo.put("operator_name", lookupService.findOperatorById(currentOperatorId).map(Operator::getName).orElse("Unknown Operator"));
                        assumedRateInfo.put("destination_name", "Assumed Destination");
                        assumedRateInfo.put("effective_number", effectiveNumber);
                        assumedRateInfo.put("applied_trunk_pricing", usesTrunk);
                        Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(finalPrefixId);
                        baseRateOpt.ifPresent(assumedRateInfo::putAll);
                        log.debug("Storing assumed rate info based on consistent prefix {} (Type: {}, Op: {})", currentPrefixCode, finalTelephonyTypeId, currentOperatorId);
                    }
                }
            } else {
                log.trace("No indicator found and length mismatch for prefix {}, number part {}", currentPrefixCode, numberWithoutPrefix);
            }
        } // End prefix loop

        // Fallbacks
        if (prefixes.isEmpty() && !usesTrunk) {
            log.debug("No prefix found for non-trunk call, attempting lookup as LOCAL for {}", effectiveNumber);
            Optional<Map<String, Object>> localRateInfoOpt = findRateInfoForLocal(commLocation, effectiveNumber);
            if (localRateInfoOpt.isPresent()) {
                Map<String, Object> localRateInfo = localRateInfoOpt.get();
                localRateInfo.put("effective_number", effectiveNumber);
                localRateInfo.put("applied_trunk_pricing", false);
                return localRateInfoOpt;
            } else {
                log.warn("LOCAL fallback failed for number: {}", effectiveNumber);
            }
        }

        if (assumedRateInfo != null && allPrefixesConsistent) {
            log.warn("Using assumed rate info for number {} based on consistent prefix type/operator.", effectiveNumber);
            return Optional.of(assumedRateInfo);
        }

        log.warn("Attempt failed: No matching rate found for number: {}", effectiveNumber);
        return Optional.empty();
    }

    // Add the missing findRateInfoForLocal method implementation here
    // (Copy the implementation from the previous corrected version)
     private Optional<Map<String, Object>> findRateInfoForLocal(CommunicationLocation commLocation, String effectiveNumber) {
        Long originCountryId = getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        Long localType = CdrProcessingConfig.TIPOTELE_LOCAL;

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot find LOCAL rate: Missing Origin Country ({}) or Indicator ID ({}) for Location {}", originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        Optional<Operator> internalOpOpt = configService.getOperatorInternal(localType, originCountryId);
        if (internalOpOpt.isEmpty()) {
            log.warn("Cannot find internal operator for LOCAL type ({}) in country {}", localType, originCountryId);
            return Optional.empty();
        }
        Long internalOperatorId = internalOpOpt.get().getId();

        Optional<Prefix> localPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(localType, internalOperatorId, originCountryId);
        if (localPrefixOpt.isEmpty()) {
            log.warn("Cannot find Prefix entity for LOCAL type ({}) and Operator {} in Country {}", localType, internalOperatorId, originCountryId);
            return Optional.empty();
        }
        Prefix localPrefix = localPrefixOpt.get();

        Optional<Map<String, Object>> indicatorInfoOpt = lookupService.findIndicatorByNumber(
                effectiveNumber, localType, originCountryId
        );
        if (indicatorInfoOpt.isEmpty()) {
            log.warn("Could not find LOCAL indicator for number {}", effectiveNumber);
            return Optional.empty(); // No indicator means no destination match
        }
        Long destinationIndicatorId = indicatorInfoOpt
            .map(ind -> (Long) ind.get("indicator_id"))
            .filter(id -> id != null && id > 0)
            .orElse(null);
        Integer destinationNdc = indicatorInfoOpt.map(ind -> (Integer) ind.get("series_ndc")).orElse(null); // Get NDC for extended check

        // --- Local Extended Check ---
        Long finalTelephonyTypeId = localType;
        Long finalPrefixId = localPrefix.getId();
        boolean finalBandOk = localPrefix.isBandOk();
        if (destinationNdc != null) { // Check if destination NDC was found
            boolean isExtended = lookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                log.debug("Reclassified LOCAL fallback call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                if (localExtOpOpt.isPresent()) {
                    Optional<Prefix> localExtPrefixOpt = lookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                    if (localExtPrefixOpt.isPresent()) {
                        finalPrefixId = localExtPrefixOpt.get().getId();
                        finalBandOk = localExtPrefixOpt.get().isBandOk();
                        log.trace("Using Prefix ID {} for LOCAL_EXTENDED rate lookup", finalPrefixId);
                    } else {
                        log.warn("Could not find prefix for LOCAL_EXTENDED type {}, operator {}, country {}. Rate lookup might be incorrect.", finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
                    }
                } else {
                    log.warn("Could not find internal operator for LOCAL_EXTENDED type {}. Rate lookup might be incorrect.", finalTelephonyTypeId);
                }
            }
        }
        // --- End Local Extended Check ---


        // Find the rate info (base or band) using the *final* determined prefix and type
        Optional<Map<String, Object>> rateInfoOpt = findRateInfo(finalPrefixId, destinationIndicatorId, originIndicatorId, finalBandOk);

        if (rateInfoOpt.isPresent()) {
            Map<String, Object> rateInfo = rateInfoOpt.get();
            // Populate the result map with all necessary info for pricing
            rateInfo.put("telephony_type_id", finalTelephonyTypeId); // Use final type
            rateInfo.put("operator_id", internalOperatorId);
            rateInfo.put("indicator_id", destinationIndicatorId);
            rateInfo.put("telephony_type_name", lookupService.findTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Unknown Type"));
            rateInfo.put("operator_name", internalOpOpt.get().getName());
            rateInfo.put("destination_name", formatDestinationName(indicatorInfoOpt.get()));
            rateInfo.put("band_name", rateInfo.get("band_name")); // Comes from findRateInfo
            return Optional.of(rateInfo);
        } else {
            log.warn("Rate info not found for LOCAL fallback (Type: {}, Prefix: {}, Indicator: {})", finalTelephonyTypeId, finalPrefixId, destinationIndicatorId);
        }

        return Optional.empty(); // Fallback failed
    }

    // Add the missing findRateInfo method implementation here
    // (Copy the implementation from the previous corrected version)
     private Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
        Optional<Map<String, Object>> baseRateOpt = lookupService.findBaseRateForPrefix(prefixId);
        if (baseRateOpt.isEmpty()) {
            log.warn("Base rate info not found for prefixId: {}", prefixId);
            return Optional.empty();
        }
        Map<String, Object> rateInfo = new HashMap<>(baseRateOpt.get());
        rateInfo.put("band_id", 0L);
        rateInfo.put("band_name", "");
        rateInfo.putIfAbsent("base_value", BigDecimal.ZERO);
        rateInfo.putIfAbsent("vat_included", false);
        rateInfo.putIfAbsent("vat_value", BigDecimal.ZERO);

        boolean useBands = bandOk && indicatorId != null;
        log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, useBands={}",
                prefixId, indicatorId, originIndicatorId, bandOk, useBands);

        if (useBands) {
            Optional<Map<String, Object>> bandOpt = lookupService.findBandByPrefixAndIndicator(prefixId, indicatorId, originIndicatorId);
            if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                rateInfo.put("base_value", bandInfo.get("band_value"));
                rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                rateInfo.put("band_id", bandInfo.get("band_id"));
                rateInfo.put("band_name", bandInfo.get("band_name"));
                log.trace("Using band rate for prefix {}, indicator {}: BandID={}, Value={}, VatIncluded={}",
                        prefixId, indicatorId, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
            } else {
                log.trace("Band lookup enabled for prefix {} but no matching band found for indicator {}", prefixId, indicatorId);
            }
        } else {
            log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing/null)", prefixId);
        }
        // Map final values
        rateInfo.put("valor_minuto", rateInfo.get("base_value"));
        rateInfo.put("valor_minuto_iva", rateInfo.get("vat_included"));
        rateInfo.put("iva", rateInfo.get("vat_value")); // Keep the VAT percentage

        return Optional.of(rateInfo);
    }

    // Add the missing linkAssociatedEntities method implementation here
    // (Copy the implementation from the previous corrected version)
     private void linkAssociatedEntities(CallRecord.CallRecordBuilder callBuilder) {
        CallRecord record = callBuilder.build(); // Build temporary record

        // Link Telephony Type
        if (record.getTelephonyTypeId() != null && record.getTelephonyType() == null) {
            lookupService.findTelephonyTypeById(record.getTelephonyTypeId())
                    .ifPresentOrElse(callBuilder::telephonyType, () -> log.warn("Could not link TelephonyType entity for ID: {}", record.getTelephonyTypeId()));
        }

        // Link Operator
        if (record.getOperatorId() != null && record.getOperator() == null) {
            lookupService.findOperatorById(record.getOperatorId())
                    .ifPresentOrElse(callBuilder::operator, () -> log.warn("Could not link Operator entity for ID: {}", record.getOperatorId()));
        }

        // Link Indicator
        if (record.getIndicatorId() != null && record.getIndicator() == null) {
            lookupService.findIndicatorById(record.getIndicatorId())
                    .ifPresentOrElse(callBuilder::indicator, () -> log.warn("Could not link Indicator entity for ID: {}", record.getIndicatorId()));
        } else if (record.getIndicatorId() == null) {
             log.trace("Indicator ID is null, skipping entity linking.");
        }

        // Link Destination Employee
        if (record.getDestinationEmployeeId() != null && record.getDestinationEmployee() == null) {
            lookupService.findEmployeeById(record.getDestinationEmployeeId())
                    .ifPresentOrElse(callBuilder::destinationEmployee, () -> log.warn("Could not link destination employee entity for ID: {}", record.getDestinationEmployeeId()));
        }
    }

} // End of CdrEnrichmentService class