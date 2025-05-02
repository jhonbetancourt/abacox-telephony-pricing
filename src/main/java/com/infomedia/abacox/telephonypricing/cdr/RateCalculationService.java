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

    private final SpecialLookupService specialLookupService;
    private final PrefixLookupService prefixLookupService;
    private final TrunkLookupService trunkLookupService;
    private final EntityLookupService entityLookupService;
    private final CdrProcessingConfig configService;

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

        Long originCountryId = specialService.getOriginCountryId();
        configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_ESPECIALES, originCountryId)
                .ifPresent(op -> callBuilder.operatorId(op.getId()));

        Map<String, Object> rateInfo = new HashMap<>();
        // PHP uses SERVESPECIAL_VALOR as valor_minuto and SERVESPECIAL_IVA as iva percentage
        rateInfo.put("valor_minuto", Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO));
        rateInfo.put("valor_minuto_iva", Optional.ofNullable(specialService.getVatIncluded()).orElse(false));
        // PHP uses SERVESPECIAL_IVA as the VAT *amount*, but the logic in Calcular_Valor uses PREFIJO_IVA (percentage)
        // Here, we assume SERVESPECIAL_IVA is the percentage for consistency with how Calcular_Valor works.
        // If SERVESPECIAL_IVA truly represents a fixed amount, the calculation logic needs adjustment.
        rateInfo.put("iva", Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO));
        rateInfo.put("ensegundos", false); // Special services are typically per-call or per-minute, not per-second
        rateInfo.put("valor_inicial", BigDecimal.ZERO); // PHP logic doesn't use initial value for special services
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
        Long indicatorId = (Long) baseRateInfo.get("indicator_id");

        Optional<TrunkRate> trunkRateOpt = trunkLookupService.findTrunkRate(trunk.getId(), operatorId, telephonyTypeId);
        // Pass trunk *name* for rule lookup as per PHP logic
        Optional<TrunkRule> trunkRuleOpt = trunkLookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);

        Map<String, Object> rateInfo = new HashMap<>(baseRateInfo);
        boolean ruleApplied = false;

        if (trunkRuleOpt.isPresent()) {
            TrunkRule rule = trunkRuleOpt.get();
            ruleApplied = true;
            log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());

            rateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
            rateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
            rateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);
            rateInfo.put("valor_inicial", BigDecimal.ZERO); // Rules don't have initial value in PHP
            rateInfo.put("valor_inicial_iva", false);

            Long finalOperatorId = operatorId;
            Long finalTelephonyTypeId = telephonyTypeId;

            if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                finalOperatorId = rule.getNewOperatorId();
                callBuilder.operatorId(finalOperatorId);
                entityLookupService.findOperatorById(finalOperatorId).ifPresent(op -> rateInfo.put("operator_name", op.getName()));
                log.trace("TrunkRule rerouted Operator to {}", finalOperatorId);
            }
            if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                callBuilder.telephonyTypeId(finalTelephonyTypeId);
                entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> rateInfo.put("telephony_type_name", tt.getName()));
                log.trace("TrunkRule rerouted TelephonyType to {}", finalTelephonyTypeId);
            }

            // Fetch IVA percentage from the Prefix associated with the *final* type and operator
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
            rateInfo.put("tipotele_nombre", rateInfo.get("telephony_type_name") + " (xRule)");

        } else if (trunkRateOpt.isPresent()) {
            TrunkRate trunkRate = trunkRateOpt.get();
            log.debug("Applying TrunkRate {} for trunk {}", trunkRate.getId(), trunk.getId());

            rateInfo.put("valor_minuto", Optional.ofNullable(trunkRate.getRateValue()).orElse(BigDecimal.ZERO));
            rateInfo.put("valor_minuto_iva", Optional.ofNullable(trunkRate.getIncludesVat()).orElse(false));
            rateInfo.put("ensegundos", trunkRate.getSeconds() != null && trunkRate.getSeconds() > 0);
            rateInfo.put("valor_inicial", BigDecimal.ZERO); // Trunk rates don't have initial value
            rateInfo.put("valor_inicial_iva", false);
            // Fetch IVA percentage from the Prefix associated with the *original* type and operator
            prefixLookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, originCountryId)
                    .ifPresentOrElse(
                            p -> rateInfo.put("iva", p.getVatValue()),
                            () -> {
                                log.warn("No prefix found for TrunkRate type {} / operator {}. Using default IVA 0.", telephonyTypeId, operatorId);
                                rateInfo.put("iva", BigDecimal.ZERO);
                            }
                    );
             rateInfo.put("tipotele_nombre", rateInfo.get("telephony_type_name") + " (xTrunkRate)");

        } else {
            log.debug("No specific TrunkRate or TrunkRule found for trunk {}, applying base/band/special pricing", trunk.getName());
            // Fallback to applying special pricing based on the initial rate info
            applySpecialPricing(baseRateInfo, callDateTime, duration, originIndicatorId, callBuilder);
            return; // Exit after applying special pricing
        }

        // Apply final pricing based on the selected rule or rate
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
        Long bandId = (Long) currentRateInfo.get("band_id");
        Long originCountryId = (Long) currentRateInfo.get("origin_country_id"); // Ensure origin country is passed

        if (telephonyTypeId == null || telephonyTypeId <= 0 || duration <= 0) {
            log.trace("Skipping special pricing: Invalid TelephonyType ({}) or Duration ({}).", telephonyTypeId, duration);
            applyFinalPricing(currentRateInfo, duration, callBuilder);
            return;
        }

        List<SpecialRateValue> specialRates = specialLookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );
        Optional<SpecialRateValue> applicableRate = findApplicableSpecialRate(specialRates, callDateTime);

        Map<String, Object> rateInfoForFinalCalc = new HashMap<>(currentRateInfo);

        if (applicableRate.isPresent()) {
            SpecialRateValue rate = applicableRate.get();
            log.debug("Applying SpecialRateValue {}", rate.getId());
            BigDecimal originalRate = (BigDecimal) rateInfoForFinalCalc.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) rateInfoForFinalCalc.get("valor_minuto_iva");

            // Store original rate before applying special rate (PHP: Guardar_ValorInicial)
            rateInfoForFinalCalc.put("valor_inicial", originalRate);
            rateInfoForFinalCalc.put("valor_inicial_iva", originalVatIncluded);

            if (rate.getValueType() != null && rate.getValueType() == 1) { // Percentage discount
                 BigDecimal discountPercentage = Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO);
                 // Calculate current rate without VAT before applying percentage
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) rateInfoForFinalCalc.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
                rateInfoForFinalCalc.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                rateInfoForFinalCalc.put("valor_minuto_iva", false); // Discounted rate is assumed to be without VAT
                rateInfoForFinalCalc.put("descuento_p", discountPercentage);
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rate.getId());
            } else { // Fixed value override
                rateInfoForFinalCalc.put("valor_minuto", Optional.ofNullable(rate.getRateValue()).orElse(BigDecimal.ZERO));
                rateInfoForFinalCalc.put("valor_minuto_iva", Optional.ofNullable(rate.getIncludesVat()).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", rateInfoForFinalCalc.get("valor_minuto"), rate.getId());
            }
            // Fetch IVA percentage from the Prefix associated with the *original* type and operator
            prefixLookupService.findPrefixByTypeOperatorOrigin(telephonyTypeId, operatorId, originCountryId)
                    .ifPresentOrElse(
                            p -> rateInfoForFinalCalc.put("iva", p.getVatValue()),
                            () -> {
                                log.warn("No prefix found for SpecialRate type {} / operator {}. Using default IVA 0.", telephonyTypeId, operatorId);
                                rateInfoForFinalCalc.put("iva", BigDecimal.ZERO);
                            }
                    );
            rateInfoForFinalCalc.put("ensegundos", false); // Special rates typically aren't per-second
            rateInfoForFinalCalc.put("tipotele_nombre", rateInfoForFinalCalc.get("telephony_type_name") + " (xSpecialRate)");

        } else {
            log.debug("No applicable special rate found, applying current rate.");
            // Ensure initial price is zero if no special rate applied
            rateInfoForFinalCalc.put("valor_inicial", BigDecimal.ZERO);
            rateInfoForFinalCalc.put("valor_inicial_iva", false);
            rateInfoForFinalCalc.put("ensegundos", false);
        }

        applyFinalPricing(rateInfoForFinalCalc, duration, callBuilder);
    }

    /**
     * Performs the final calculation and sets pricing fields in the builder. Matches PHP's Calcular_Valor.
     * @param rateInfo Map containing final rate details (valor_minuto, valor_minuto_iva, iva, ensegundos, valor_inicial, valor_inicial_iva).
     * @param duration Call duration in seconds.
     * @param callBuilder Builder to update.
     */
    private void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
        BigDecimal pricePerUnit = Optional.ofNullable((BigDecimal) rateInfo.get("valor_minuto")).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_minuto_iva")).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable((BigDecimal) rateInfo.get("iva")).orElse(BigDecimal.ZERO);
        boolean chargePerSecond = Optional.ofNullable((Boolean) rateInfo.get("ensegundos")).orElse(false);
        BigDecimal initialPrice = Optional.ofNullable((BigDecimal) rateInfo.get("valor_inicial")).orElse(BigDecimal.ZERO);
        boolean initialVatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_inicial_iva")).orElse(false);

        BigDecimal calculatedBilledAmount = calculateBilledAmount(
                duration, pricePerUnit, vatIncluded, vatPercentage, chargePerSecond, initialPrice, initialVatIncluded
        );

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

    private BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue, boolean initialVatIncluded) {
        if (durationSeconds <= 0) return BigDecimal.ZERO;

        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
        BigDecimal effectiveInitialRateValue = Optional.ofNullable(initialRateValue).orElse(BigDecimal.ZERO);
        BigDecimal effectiveVatPercentage = Optional.ofNullable(vatPercentage).orElse(BigDecimal.ZERO);
        BigDecimal vatMultiplier = BigDecimal.ONE.add(effectiveVatPercentage.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));

        BigDecimal durationUnits;
        BigDecimal pricePerUnit = effectiveRateValue;

        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
            log.trace("Calculating cost per second for {} seconds", durationSeconds);
        } else {
            long minutes = (long) Math.ceil((double) durationSeconds / 60.0);
            durationUnits = BigDecimal.valueOf(Math.max(1, minutes)); // PHP rounds up, min 1 minute
            log.trace("Calculating cost per minute for {} seconds -> {} minutes", durationSeconds, durationUnits);
        }

        // Calculate duration cost, applying VAT if needed
        BigDecimal durationCostWithVat = pricePerUnit.multiply(durationUnits);
        if (!rateVatIncluded && effectiveVatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            durationCostWithVat = durationCostWithVat.multiply(vatMultiplier);
            log.trace("Applied VAT ({}%) to duration cost ({}), result: {}", effectiveVatPercentage, pricePerUnit.multiply(durationUnits), durationCostWithVat);
        } else {
             log.trace("Duration cost (VAT included or zero): {}", durationCostWithVat);
        }


        // Calculate initial cost, applying VAT if needed (PHP: cargo_basico logic)
        BigDecimal initialCostWithVat = effectiveInitialRateValue;
        if (effectiveInitialRateValue.compareTo(BigDecimal.ZERO) > 0) {
            if (!initialVatIncluded && effectiveVatPercentage.compareTo(BigDecimal.ZERO) > 0) {
                initialCostWithVat = initialCostWithVat.multiply(vatMultiplier);
                log.trace("Applied VAT ({}%) to initial cost ({}), result: {}", effectiveVatPercentage, effectiveInitialRateValue, initialCostWithVat);
            } else {
                 log.trace("Initial cost (VAT included or zero): {}", initialCostWithVat);
            }
        }

        BigDecimal finalBilledAmount = durationCostWithVat.add(initialCostWithVat);

        return finalBilledAmount.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value.setScale(10, RoundingMode.HALF_UP); // Use higher precision for intermediate calcs
        }
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("VAT divisor is zero, cannot remove VAT from {}", value);
            return value.setScale(10, RoundingMode.HALF_UP);
        }
        return value.divide(vatDivisor, 10, RoundingMode.HALF_UP); // Use higher precision
    }

    private Optional<SpecialRateValue> findApplicableSpecialRate(List<SpecialRateValue> candidates, LocalDateTime callDateTime) {
        int callHour = callDateTime.getHour();
        // The query already ordered by specificity, so we just need the first one that matches the hour
        return candidates.stream()
                .filter(rate -> isHourApplicable(rate.getHoursSpecification(), callHour))
                .findFirst();
    }

     private boolean isHourApplicable(String hoursSpecification, int callHour) {
        if (!StringUtils.hasText(hoursSpecification)) return true; // No hour spec means applicable all day
        try {
            for (String part : hoursSpecification.split(",")) {
                String range = part.trim();
                if (range.contains("-")) {
                    String[] parts = range.split("-");
                    if (parts.length == 2) {
                        int start = Integer.parseInt(parts[0].trim());
                        int end = Integer.parseInt(parts[1].trim());
                        // Handle overnight ranges (e.g., 22-06)
                        if (start <= end) { // Normal range (e.g., 08-17)
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
            return false; // Error parsing means it doesn't apply
        }
        log.trace("Call hour {} is not within specification '{}'", callHour, hoursSpecification);
        return false; // No matching range/hour found
    }
}