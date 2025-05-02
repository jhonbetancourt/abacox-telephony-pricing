// FILE: cdr/RateCalculationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Log4j2
public class RateCalculationService {

    private final CdrProcessingConfig configService;
    private final SpecialLookupService specialLookupService;
    private final PrefixLookupService prefixLookupService;
    private final TrunkLookupService trunkLookupService;
    private final EntityLookupService entityLookupService; // For operator/type names if needed

    private static final BigDecimal SIXTY = new BigDecimal("60");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * Applies Special Service pricing rules. Matches PHP's procesaServespecial.
     * @param specialService The matched SpecialService entity.
     * @param callBuilder Builder to update.
     * @param duration Call duration in seconds.
     */
    public void applySpecialServicePricing(SpecialService specialService, CallRecord.CallRecordBuilder callBuilder, int duration) {
        callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ESPECIALES);
        callBuilder.indicatorId(specialService.getIndicatorId() != null && specialService.getIndicatorId() > 0 ? specialService.getIndicatorId() : null);

        // PHP's operador_interno logic: Find the operator linked via Prefix table for this type/country
        Long originCountryId = specialService.getOriginCountryId();
        configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_ESPECIALES, originCountryId)
                .ifPresent(op -> callBuilder.operatorId(op.getId()));

        // Use a map to mimic PHP's $infovalor structure for calculation
        Map<String, Object> rateInfo = new HashMap<>();
        rateInfo.put("valor_minuto", Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO));
        rateInfo.put("valor_minuto_iva", Optional.ofNullable(specialService.getVatIncluded()).orElse(false));
        rateInfo.put("iva", Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO)); // PHP uses SERVESPECIAL_IVA as the percentage
        rateInfo.put("ensegundos", false); // Special services are typically per-call or per-minute
        rateInfo.put("valor_inicial", BigDecimal.ZERO); // PHP's buscar_NumeroEspecial doesn't handle initial charge
        rateInfo.put("valor_inicial_iva", false);

        applyFinalPricing(rateInfo, duration, callBuilder);
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", rateInfo.get("valor_minuto"), callBuilder.build().getBilledAmount());
    }

    /**
     * Applies pricing for internal calls based on type. Matches PHP's procesaInterna -> TarifasInternas.
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
     * Applies pricing based on trunk rates or rules. Matches PHP's Calcular_Valor_Reglas.
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
        Optional<TrunkRule> trunkRuleOpt = trunkLookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);

        Map<String, Object> rateInfo = new HashMap<>(baseRateInfo); // Start with base rate info
        boolean ruleApplied = false;

        // PHP prioritizes specific TrunkRule over TrunkRate if both match? Let's assume rule overrides rate if found.
        if (trunkRuleOpt.isPresent()) {
            TrunkRule rule = trunkRuleOpt.get();
            ruleApplied = true;
            log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());

            rateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
            rateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
            rateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);
            rateInfo.put("valor_inicial", BigDecimal.ZERO); // Rules don't have initial charge concept
            rateInfo.put("valor_inicial_iva", false);

            Long finalOperatorId = operatorId;
            Long finalTelephonyTypeId = telephonyTypeId;

            // Apply re-routing from rule
            if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                finalOperatorId = rule.getNewOperatorId();
                callBuilder.operatorId(finalOperatorId); // Update the main record
                entityLookupService.findOperatorById(finalOperatorId).ifPresent(op -> rateInfo.put("operator_name", op.getName()));
                log.trace("TrunkRule rerouted Operator to {}", finalOperatorId);
            }
            if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                callBuilder.telephonyTypeId(finalTelephonyTypeId); // Update the main record
                entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> rateInfo.put("telephony_type_name", tt.getName()));
                log.trace("TrunkRule rerouted TelephonyType to {}", finalTelephonyTypeId);
            }

            // Get VAT based on the potentially *new* type/operator
            Long finalTelephonyTypeId1 = finalTelephonyTypeId; // Need final variable for lambda
            Long finalOperatorId1 = finalOperatorId; // Need final variable for lambda
            prefixLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, finalOperatorId, originCountryId)
                    .ifPresentOrElse(
                            p -> rateInfo.put("iva", p.getVatValue()),
                            () -> {
                                log.warn("No prefix found for rule-defined type {} / operator {}. Using default IVA 0.", finalTelephonyTypeId1, finalOperatorId1);
                                rateInfo.put("iva", BigDecimal.ZERO);
                            }
                    );
            rateInfo.put("tipotele_nombre", rateInfo.get("telephony_type_name") + " (xRule)"); // Add suffix like PHP

        } else if (trunkRateOpt.isPresent()) {
            TrunkRate trunkRate = trunkRateOpt.get();
            log.debug("Applying TrunkRate {} for trunk {}", trunkRate.getId(), trunk.getId());

            rateInfo.put("valor_minuto", Optional.ofNullable(trunkRate.getRateValue()).orElse(BigDecimal.ZERO));
            rateInfo.put("valor_minuto_iva", Optional.ofNullable(trunkRate.getIncludesVat()).orElse(false));
            rateInfo.put("ensegundos", trunkRate.getSeconds() != null && trunkRate.getSeconds() > 0);
            rateInfo.put("valor_inicial", BigDecimal.ZERO); // Rates don't have initial charge concept
            rateInfo.put("valor_inicial_iva", false);
            // Get VAT from the corresponding Prefix for the original type/operator
            prefixLookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, originCountryId)
                    .ifPresentOrElse(
                            p -> rateInfo.put("iva", p.getVatValue()),
                            () -> {
                                log.warn("No prefix found for TrunkRate type {} / operator {}. Using default IVA 0.", telephonyTypeId, operatorId);
                                rateInfo.put("iva", BigDecimal.ZERO);
                            }
                    );
             rateInfo.put("tipotele_nombre", rateInfo.get("telephony_type_name") + " (xTrunkRate)"); // Add suffix

        } else {
            log.debug("No specific TrunkRate or TrunkRule found for trunk {}, applying base/band/special pricing", trunk.getName());
            // Fallback to standard pricing (base/band + special rates)
            applySpecialPricing(baseRateInfo, callDateTime, duration, originIndicatorId, callBuilder);
            return; // Exit as special pricing handles the final calculation
        }

        // Apply final calculation based on trunk rate/rule info
        applyFinalPricing(rateInfo, duration, callBuilder);
    }

    /**
     * Applies special rate values (discounts, overrides) on top of existing rate info. Matches PHP's Obtener_ValorEspecial.
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

        // If type or duration is invalid, skip special pricing
        if (telephonyTypeId == null || telephonyTypeId <= 0 || duration <= 0) {
            log.trace("Skipping special pricing: Invalid TelephonyType ({}) or Duration ({}).", telephonyTypeId, duration);
            applyFinalPricing(currentRateInfo, duration, callBuilder); // Apply the current rate as is
            return;
        }

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

            // PHP: Guarda_ValorInicial - Store original rate as initial price if a special rate applies
            rateInfoForFinalCalc.put("valor_inicial", originalRate);
            rateInfoForFinalCalc.put("valor_inicial_iva", originalVatIncluded);

            if (rate.getValueType() != null && rate.getValueType() == 1) { // Percentage discount
                 BigDecimal discountPercentage = Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO);
                // Calculate discount on the rate *without* VAT
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) rateInfoForFinalCalc.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
                rateInfoForFinalCalc.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                // The resulting rate is now *without* VAT, regardless of original status
                rateInfoForFinalCalc.put("valor_minuto_iva", false);
                rateInfoForFinalCalc.put("descuento_p", discountPercentage); // Store for info
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rate.getId());
            } else { // Fixed value override
                rateInfoForFinalCalc.put("valor_minuto", Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO));
                rateInfoForFinalCalc.put("valor_minuto_iva", Optional.ofNullable(rate.getIncludesVat()).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", rateInfoForFinalCalc.get("valor_minuto"), rate.getId());
            }
            // Fetch IVA based on the *original* operator/type, as special rate might not define it
            prefixLookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, (Long) currentRateInfo.get("origin_country_id"))
                    .ifPresentOrElse(
                            p -> rateInfoForFinalCalc.put("iva", p.getVatValue()),
                            () -> {
                                log.warn("No prefix found for SpecialRate type {} / operator {}. Using default IVA 0.", telephonyTypeId, operatorId);
                                rateInfoForFinalCalc.put("iva", BigDecimal.ZERO);
                            }
                    );
            rateInfoForFinalCalc.put("ensegundos", false); // Special rates usually apply per minute
            rateInfoForFinalCalc.put("tipotele_nombre", rateInfoForFinalCalc.get("telephony_type_name") + " (xSpecialRate)"); // Add suffix

        } else {
            log.debug("No applicable special rate found, applying current rate.");
            // No special rate, so initial price is zero (PHP: Guarda_ValorInicial not called)
            rateInfoForFinalCalc.put("valor_inicial", BigDecimal.ZERO);
            rateInfoForFinalCalc.put("valor_inicial_iva", false);
            rateInfoForFinalCalc.put("ensegundos", false); // Default to per-minute
        }

        // Apply final calculation based on potentially modified rateInfo
        applyFinalPricing(rateInfoForFinalCalc, duration, callBuilder);
    }

    /**
     * Performs the final calculation and sets pricing fields in the builder. Matches PHP's Calcular_Valor.
     * @param rateInfo Map containing final rate details (valor_minuto, valor_minuto_iva, iva, ensegundos, valor_inicial, valor_inicial_iva).
     * @param duration Call duration in seconds.
     * @param callBuilder Builder to update.
     */
    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
        // Extract values with defaults
        BigDecimal pricePerUnit = Optional.ofNullable((BigDecimal) rateInfo.get("valor_minuto")).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_minuto_iva")).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable((BigDecimal) rateInfo.get("iva")).orElse(BigDecimal.ZERO);
        boolean chargePerSecond = Optional.ofNullable((Boolean) rateInfo.get("ensegundos")).orElse(false);
        BigDecimal initialPrice = Optional.ofNullable((BigDecimal) rateInfo.get("valor_inicial")).orElse(BigDecimal.ZERO);
        // boolean initialVatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_inicial_iva")).orElse(false); // PHP didn't use this in Calcular_Valor

        BigDecimal calculatedBilledAmount = calculateBilledAmount(
                duration, pricePerUnit, vatIncluded, vatPercentage, chargePerSecond, initialPrice
        );

        // Store the rate *per minute* even if charged per second
        BigDecimal pricePerMinuteStored = pricePerUnit;
        if (chargePerSecond) {
            pricePerMinuteStored = pricePerUnit.multiply(SIXTY);
        }

        callBuilder.pricePerMinute(pricePerMinuteStored.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(initialPrice.setScale(4, RoundingMode.HALF_UP));
        callBuilder.billedAmount(calculatedBilledAmount);
        log.trace("Final pricing applied: Rate={}, Initial={}, VAT%={}, PerSec={}, Billed={}",
                  pricePerUnit, initialPrice, vatPercentage, chargePerSecond, calculatedBilledAmount);
    }


    // --- Helper Methods ---

    /**
     * Calculates the final billed amount based on duration, rate, VAT, and charge unit.
     * Matches PHP's Calcular_Valor logic.
     */
    private BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue) {
        if (durationSeconds <= 0) return BigDecimal.ZERO;

        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
        // PHP's Calcular_Valor did not include initialRateValue (cargo_basico was separate and removed).
        // We add it here if present, assuming it's a flat charge applied *before* VAT if rateVatIncluded is false.
        BigDecimal effectiveInitialRateValue = Optional.ofNullable(initialRateValue).orElse(BigDecimal.ZERO);

        BigDecimal durationUnits;
        BigDecimal pricePerUnit = effectiveRateValue;

        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
            log.trace("Calculating cost per second for {} seconds", durationSeconds);
            // pricePerUnit remains the rate per second
        } else {
            // PHP: Calculate minutes, rounding up (integer division + ceil effect)
            long minutes = (long) Math.ceil((double) durationSeconds / 60.0);
            // Ensure minimum 1 minute charge if duration > 0
            durationUnits = BigDecimal.valueOf(Math.max(1, minutes));
            log.trace("Calculating cost per minute for {} seconds -> {} minutes", durationSeconds, durationUnits);
            // pricePerUnit remains the rate per minute
        }

        // Calculate cost based on duration and rate per unit
        BigDecimal durationCost = pricePerUnit.multiply(durationUnits);
        log.trace("Base duration cost (rate * duration units): {} * {} = {}", pricePerUnit, durationUnits, durationCost);

        // Add initial rate (if any)
        BigDecimal totalCostBeforeVat = durationCost.add(effectiveInitialRateValue);
        if (effectiveInitialRateValue.compareTo(BigDecimal.ZERO) > 0) {
             log.trace("Added initial charge: {}", effectiveInitialRateValue);
        }

        // Apply VAT if not already included in the rate(s)
        BigDecimal finalBilledAmount = totalCostBeforeVat;
        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
            finalBilledAmount = totalCostBeforeVat.multiply(vatMultiplier);
            log.trace("Applied VAT ({}%) to total cost ({}), new total: {}", vatPercentage, totalCostBeforeVat, finalBilledAmount);
        } else {
            log.trace("VAT already included in rate or VAT is zero/null. Final cost: {}", finalBilledAmount);
        }

        return finalBilledAmount.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("VAT divisor is zero, cannot remove VAT from {}", value);
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        return value.divide(vatDivisor, 4, RoundingMode.HALF_UP);
    }

    /**
     * Finds the single most applicable special rate from a list of candidates.
     * PHP logic implicitly takes the first match based on its ORDER BY clause.
     * We simulate this by filtering based on hour applicability and taking the first.
     *
     * @param candidates   List of potential SpecialRateValue entities, assumed sorted by priority.
     * @param callDateTime The timestamp of the call.
     * @return Optional containing the best matching SpecialRateValue.
     */
    private Optional<SpecialRateValue> findApplicableSpecialRate(List<SpecialRateValue> candidates, LocalDateTime callDateTime) {
        int callHour = callDateTime.getHour();
        return candidates.stream()
                .filter(rate -> isHourApplicable(rate.getHoursSpecification(), callHour))
                .findFirst(); // Takes the first match based on the list's sorting (simulates PHP ORDER BY)
    }

    /**
     * Checks if the call hour falls within the specified hour ranges.
     * Matches PHP's ArregloHoras logic.
     *
     * @param hoursSpecification Comma-separated hours/ranges (e.g., "8-12,14,18-22").
     * @param callHour           The hour of the call (0-23).
     * @return True if the hour is applicable, false otherwise.
     */
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
                        if (start <= end) { // Normal range (e.g., 8-17)
                            if (callHour >= start && callHour <= end) return true;
                        } else { // Overnight range (e.g., 22-06)
                            if (callHour >= start || callHour <= end) return true;
                        }
                    } else {
                        log.warn("Invalid hour range format: {}", range);
                    }
                } else if (!range.isEmpty()) { // Single hour
                    if (callHour == Integer.parseInt(range)) return true;
                }
            }
        } catch (Exception e) {
            log.error("Error parsing hoursSpecification: '{}'. Assuming not applicable.", hoursSpecification, e);
            return false;
        }
        log.trace("Call hour {} is not within specification '{}'", callHour, hoursSpecification);
        return false; // Not applicable if no range matched
    }
}