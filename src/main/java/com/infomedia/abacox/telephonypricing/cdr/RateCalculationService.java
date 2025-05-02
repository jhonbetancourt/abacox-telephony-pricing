// FILE: cdr/RateCalculationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Log4j2
public class RateCalculationService {

    private final SpecialLookupService specialLookupService;
    private final PrefixLookupService prefixLookupService;
    private final TrunkLookupService trunkLookupService;
    private final EntityLookupService entityLookupService; // For operator/type names if needed

    private static final BigDecimal SIXTY = new BigDecimal("60");

    /**
     * Applies Special Service pricing rules.
     * @param specialService The matched SpecialService entity.
     * @param callBuilder Builder to update.
     */
    public void applySpecialServicePricing(SpecialService specialService, CallRecord.CallRecordBuilder callBuilder) {
        callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ESPECIALES);
        callBuilder.indicatorId(specialService.getIndicatorId() != null && specialService.getIndicatorId() > 0 ? specialService.getIndicatorId() : null);
        callBuilder.operatorId(null); // Special services don't usually have an operator concept in pricing

        BigDecimal price = Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable(specialService.getVatIncluded()).orElse(false);
        // Use vatAmount as the percentage for calculation consistency if needed, or assume it's absolute
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO);

        BigDecimal billedAmount = price;
        if (!vatIncluded && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            // Assuming vatAmount is the percentage here. If it's absolute, the calculation is different.
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            billedAmount = billedAmount.multiply(vatMultiplier);
            log.trace("Applied VAT {}% to special service price {}", vatPercentage, price);
        }

        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP)); // Or price per call? Clarify logic.
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.billedAmount(billedAmount.setScale(4, RoundingMode.HALF_UP));
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", price, billedAmount);
    }

    /**
     * Applies pricing for internal calls based on type.
     * @param internalCallTypeId The determined internal call type ID.
     * @param callBuilder Builder to update.
     * @param duration Call duration in seconds.
     */
    public void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
        Optional<Map<String, Object>> tariffOpt = prefixLookupService.findInternalTariff(internalCallTypeId);
        if (tariffOpt.isPresent()) {
            Map<String, Object> tariff = tariffOpt.get();
            log.debug("Applying internal tariff for type {}: {}", internalCallTypeId, tariff);
            applyFinalPricing(tariff, duration, callBuilder);
        } else {
            log.warn("No internal tariff found for type {}, setting cost to zero.", internalCallTypeId);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            callBuilder.billedAmount(BigDecimal.ZERO);
        }
    }

    /**
     * Applies pricing based on trunk rates or rules.
     * @param trunk The Trunk used.
     * @param baseRateInfo Initial rate info (type, operator, indicator).
     * @param duration Call duration in seconds.
     * @param originIndicatorId Origin indicator ID.
     * @param originCountryId Origin country ID.
     * @param callDateTime Call timestamp.
     * @param callBuilder Builder to update.
     */
    public void applyTrunkPricing(Trunk trunk, Map<String, Object> baseRateInfo, int duration, Long originIndicatorId, Long originCountryId, LocalDateTime callDateTime, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) baseRateInfo.get("telephony_type_id");
        Long operatorId = (Long) baseRateInfo.get("operator_id");
        Long indicatorId = (Long) baseRateInfo.get("indicator_id"); // Can be null

        Optional<TrunkRate> trunkRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorId, telephonyTypeId);

        if (trunkRateOpt.isPresent()) {
            TrunkRate trunkRate = trunkRateOpt.get();
            log.debug("Applying TrunkRate {} for trunk {}", trunkRate.getId(), trunk.getId());
            Map<String, Object> rateInfo = new HashMap<>(baseRateInfo); // Copy base info
            rateInfo.put("valor_minuto", Optional.ofNullable(trunkRate.getRateValue()).orElse(BigDecimal.ZERO));
            rateInfo.put("valor_minuto_iva", Optional.ofNullable(trunkRate.getIncludesVat()).orElse(false));
            rateInfo.put("ensegundos", trunkRate.getSeconds() != null && trunkRate.getSeconds() > 0);
            rateInfo.put("valor_inicial", BigDecimal.ZERO);
            rateInfo.put("valor_inicial_iva", false);
            // Get VAT from the corresponding Prefix
            prefixLookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, originCountryId)
                    .ifPresent(p -> rateInfo.put("iva", p.getVatValue()));
            applyFinalPricing(rateInfo, duration, callBuilder);
        } else {
            Optional<TrunkRule> trunkRuleOpt = trunkLookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);
            if (trunkRuleOpt.isPresent()) {
                TrunkRule rule = trunkRuleOpt.get();
                log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());
                Map<String, Object> rateInfo = new HashMap<>(baseRateInfo); // Copy base info
                rateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
                rateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
                rateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);
                rateInfo.put("valor_inicial", BigDecimal.ZERO);
                rateInfo.put("valor_inicial_iva", false);

                Long finalOperatorId = operatorId;
                Long finalTelephonyTypeId = telephonyTypeId;

                // Apply re-routing from rule
                if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                    finalOperatorId = rule.getNewOperatorId();
                    callBuilder.operatorId(finalOperatorId);
                    // Optionally update name in rateInfo if needed downstream
                    entityLookupService.findOperatorById(finalOperatorId).ifPresent(op -> rateInfo.put("operator_name", op.getName()));
                }
                if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                    finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                    callBuilder.telephonyTypeId(finalTelephonyTypeId);
                    // Optionally update name in rateInfo if needed downstream
                    entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> rateInfo.put("telephony_type_name", tt.getName()));
                }

                // Get VAT based on the potentially *new* type/operator
                Long finalTelephonyTypeId1 = finalTelephonyTypeId;
                Long finalOperatorId1 = finalOperatorId;
                prefixLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, finalOperatorId, originCountryId)
                        .ifPresentOrElse(
                                p -> rateInfo.put("iva", p.getVatValue()),
                                () -> {
                                    log.warn("No prefix found for rule-defined type {} / operator {}. Using default IVA 0.", finalTelephonyTypeId1, finalOperatorId1);
                                    rateInfo.put("iva", BigDecimal.ZERO);
                                }
                        );

                applyFinalPricing(rateInfo, duration, callBuilder);
            } else {
                log.debug("No specific TrunkRate or TrunkRule found for trunk {}, applying base/band/special pricing", trunk.getName());
                // Fallback to standard pricing (base/band + special rates)
                applySpecialPricing(baseRateInfo, callDateTime, duration, originIndicatorId, callBuilder);
            }
        }
    }

    /**
     * Applies special rate values (discounts, overrides) on top of existing rate info.
     * @param currentRateInfo The current rate info map (base or band).
     * @param callDateTime Call timestamp.
     * @param duration Call duration in seconds.
     * @param originIndicatorId Origin indicator ID.
     * @param callBuilder Builder to update.
     */
    public void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long operatorId = (Long) currentRateInfo.get("operator_id");
        Long bandId = (Long) currentRateInfo.get("band_id"); // May be null or 0

        List<SpecialRateValue> specialRates = specialLookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );
        Optional<SpecialRateValue> applicableRate = findApplicableSpecialRate(specialRates, callDateTime);

        Map<String, Object> rateInfoForFinalCalc = new HashMap<>(currentRateInfo); // Copy to modify

        if (applicableRate.isPresent()) {
            SpecialRateValue rate = applicableRate.get();
            log.debug("Applying SpecialRateValue {}", rate.getId());
            BigDecimal originalRate = (BigDecimal) rateInfoForFinalCalc.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) rateInfoForFinalCalc.get("valor_minuto_iva");

            // Store original rate as initial price if a special rate applies
            rateInfoForFinalCalc.put("valor_inicial", originalRate);
            rateInfoForFinalCalc.put("valor_inicial_iva", originalVatIncluded);

            if (rate.getValueType() != null && rate.getValueType() == 1) { // Percentage discount
                 BigDecimal discountPercentage = Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO);
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) rateInfoForFinalCalc.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
                rateInfoForFinalCalc.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                // VAT inclusion status remains the same as the original rate before discount
                rateInfoForFinalCalc.put("valor_minuto_iva", false); // Discount applied on value *without* VAT
                rateInfoForFinalCalc.put("descuento_p", discountPercentage);
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rate.getId());
            } else { // Fixed value override
                rateInfoForFinalCalc.put("valor_minuto", Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO));
                rateInfoForFinalCalc.put("valor_minuto_iva", Optional.ofNullable(rate.getIncludesVat()).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", rateInfoForFinalCalc.get("valor_minuto"), rate.getId());
            }
            rateInfoForFinalCalc.put("ensegundos", false); // Special rates usually apply per minute unless specified otherwise

        } else {
            log.debug("No applicable special rate found, applying current rate.");
            // No special rate, so initial price is zero
            rateInfoForFinalCalc.put("valor_inicial", BigDecimal.ZERO);
            rateInfoForFinalCalc.put("valor_inicial_iva", false);
            rateInfoForFinalCalc.put("ensegundos", false); // Default to per-minute unless trunk rate/rule specified otherwise
        }

        // Apply final calculation based on potentially modified rateInfo
        applyFinalPricing(rateInfoForFinalCalc, duration, callBuilder);
    }

    /**
     * Performs the final calculation and sets pricing fields in the builder.
     * @param rateInfo Map containing final rate details (valor_minuto, valor_minuto_iva, iva, ensegundos, valor_inicial, valor_inicial_iva).
     * @param duration Call duration in seconds.
     * @param callBuilder Builder to update.
     */
    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
        // Extract values with defaults
        BigDecimal pricePerMinute = Optional.ofNullable((BigDecimal) rateInfo.get("valor_minuto")).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_minuto_iva")).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable((BigDecimal) rateInfo.get("iva")).orElse(BigDecimal.ZERO);
        boolean chargePerSecond = Optional.ofNullable((Boolean) rateInfo.get("ensegundos")).orElse(false);
        BigDecimal initialPrice = Optional.ofNullable((BigDecimal) rateInfo.get("valor_inicial")).orElse(BigDecimal.ZERO);
        // boolean initialVatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_inicial_iva")).orElse(false); // Not directly used in calc below

        BigDecimal calculatedBilledAmount = calculateBilledAmount(
                duration, pricePerMinute, vatIncluded, vatPercentage, chargePerSecond, initialPrice // Pass initial price if applicable
        );

        callBuilder.pricePerMinute(pricePerMinute.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(initialPrice.setScale(4, RoundingMode.HALF_UP));
        callBuilder.billedAmount(calculatedBilledAmount);
        log.trace("Final pricing applied: Rate={}, Initial={}, VAT%={}, PerSec={}, Billed={}",
                  pricePerMinute, initialPrice, vatPercentage, chargePerSecond, calculatedBilledAmount);
    }


    // --- Helper Methods ---

    private BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue /*, boolean initialRateVatIncluded */) {
        if (durationSeconds <= 0) return BigDecimal.ZERO;

        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
        BigDecimal effectiveInitialRateValue = Optional.ofNullable(initialRateValue).orElse(BigDecimal.ZERO);

        BigDecimal durationUnits;
        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
            log.trace("Calculating cost per second for {} seconds", durationSeconds);
        } else {
            // Calculate minutes, rounding up
            durationUnits = new BigDecimal(durationSeconds).divide(SIXTY, 0, RoundingMode.CEILING);
            // Ensure minimum 1 minute charge if duration > 0
            if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) {
                durationUnits = BigDecimal.ONE;
            }
            log.trace("Calculating cost per minute for {} seconds -> {} minutes", durationSeconds, durationUnits);
        }

        // Calculate cost based on duration and rate per unit (second or minute)
        BigDecimal durationCost = effectiveRateValue.multiply(durationUnits);
        log.trace("Base duration cost (rate * duration units): {} * {} = {}", effectiveRateValue, durationUnits, durationCost);

        // Add initial rate (if any) - Assuming initial rate is a flat charge, not per unit
        BigDecimal totalCost = durationCost.add(effectiveInitialRateValue);
        if (effectiveInitialRateValue.compareTo(BigDecimal.ZERO) > 0) {
             log.trace("Added initial charge: {}", effectiveInitialRateValue);
        }

        // Apply VAT if not already included in the rate(s)
        // Note: This assumes VAT applies uniformly to initial and duration costs if not included.
        // More complex logic might be needed if initial/duration VAT rules differ.
        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
            log.trace("Applied VAT ({}%) to total cost, new total: {}", vatPercentage, totalCost);
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

    private Optional<SpecialRateValue> findApplicableSpecialRate(List<SpecialRateValue> candidates, LocalDateTime callDateTime) {
        int callHour = callDateTime.getHour();
        return candidates.stream()
                .filter(rate -> isHourApplicable(rate.getHoursSpecification(), callHour))
                .findFirst(); // Assumes candidates are already sorted by priority
    }

    private boolean isHourApplicable(String hoursSpecification, int callHour) {
        if (!StringUtils.hasText(hoursSpecification)) return true; // No spec means applicable all hours
        try {
            for (String part : hoursSpecification.split(",")) {
                String range = part.trim();
                if (range.contains("-")) {
                    String[] parts = range.split("-");
                    if (parts.length == 2) {
                        int start = Integer.parseInt(parts[0].trim());
                        int end = Integer.parseInt(parts[1].trim());
                        // Handle overnight ranges (e.g., 22-06)
                        if (start <= end) {
                            if (callHour >= start && callHour <= end) return true;
                        } else { // Overnight range
                            if (callHour >= start || callHour <= end) return true;
                        }
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
        return false; // Not applicable if no range matched
    }
}