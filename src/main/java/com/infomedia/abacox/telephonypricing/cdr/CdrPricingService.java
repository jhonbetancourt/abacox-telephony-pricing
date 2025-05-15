// FILE: com/infomedia/abacox/telephonypricing/cdr/CdrPricingService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

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
public class CdrPricingService {

    private final PrefixInfoLookupService prefixInfoLookupService;
    private final ConfigurationLookupService configurationLookupService;
    private final SpecialRuleLookupService specialRuleLookupService;
    private final TrunkLookupService trunkLookupService;
    private final EntityLookupService entityLookupService;
    private final CdrProcessingConfig configService;
    private final CdrEnrichmentHelper cdrEnrichmentHelper;


    private static final BigDecimal SIXTY = new BigDecimal("60");

    public Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
        Optional<Map<String, Object>> baseRateOpt = prefixInfoLookupService.findBaseRateForPrefix(prefixId);
        if (baseRateOpt.isEmpty()) {
            log.info("Base rate info not found for prefixId: {}", prefixId);
            return Optional.empty();
        }
        Map<String, Object> rateInfo = new HashMap<>(baseRateOpt.get());
        rateInfo.put("band_id", 0L); // Default if no band found
        rateInfo.put("band_name", "");
        rateInfo.putIfAbsent("base_value", BigDecimal.ZERO);
        rateInfo.putIfAbsent("vat_included", false);
        rateInfo.putIfAbsent("vat_value", BigDecimal.ZERO);

        Long telephonyTypeIdFromPrefix = (Long) rateInfo.get("telephony_type_id");
        boolean isLocalTypeForBandCheck = (telephonyTypeIdFromPrefix != null &&
                telephonyTypeIdFromPrefix.equals(CdrProcessingConfig.TIPOTELE_LOCAL));

        boolean useEffectiveBandLookup = bandOk && ( (indicatorId != null && indicatorId > 0) || isLocalTypeForBandCheck );

        log.info("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, isLocalType={}, useEffectiveBandLookup={}",
                prefixId, indicatorId, originIndicatorId, bandOk, isLocalTypeForBandCheck, useEffectiveBandLookup);

        if (useEffectiveBandLookup) {
            Long indicatorForBandLookup = (isLocalTypeForBandCheck && (indicatorId == null || indicatorId <= 0))
                                          ? originIndicatorId
                                          : indicatorId;

            Optional<Map<String, Object>> bandOpt = prefixInfoLookupService.findBandByPrefixAndIndicator(prefixId, indicatorForBandLookup, originIndicatorId);
            if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                rateInfo.put("base_value", bandInfo.get("band_value"));
                rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                rateInfo.put("band_id", bandInfo.get("band_id"));
                rateInfo.put("band_name", bandInfo.get("band_name"));
                log.info("Using band rate for prefix {}, effective indicator {}: BandID={}, Value={}, VatIncluded={}",
                        prefixId, indicatorForBandLookup, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
            } else {
                log.info("Band lookup enabled for prefix {} but no matching band found for effective indicator {}", prefixId, indicatorForBandLookup);
            }
        } else {
            log.info("Using base rate for prefix {} (Bands not applicable or indicator missing/null for non-local)", prefixId);
        }
        // Ensure these fields are populated from either band or prefix base rate, for consistent structure
        rateInfo.putIfAbsent("valor_minuto", rateInfo.get("base_value"));
        rateInfo.putIfAbsent("valor_minuto_iva", rateInfo.get("vat_included"));
        rateInfo.putIfAbsent("iva", rateInfo.get("vat_value")); // VAT rate comes from prefix

        return Optional.of(rateInfo);
    }

    public void applySpecialServicePricing(SpecialService specialService, CallRecord.CallRecordBuilder callBuilder, int duration) {
        callBuilder.telephonyTypeId(CdrProcessingConfig.TIPOTELE_ESPECIALES);
        callBuilder.indicatorId(specialService.getIndicatorId() != null && specialService.getIndicatorId() > 0 ? specialService.getIndicatorId() : null);
        configService.getOperatorInternal(CdrProcessingConfig.TIPOTELE_ESPECIALES, specialService.getOriginCountryId())
                .ifPresent(op -> callBuilder.operatorId(op.getId()));

        BigDecimal price = Optional.ofNullable(specialService.getValue()).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable(specialService.getVatIncluded()).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO);

        BigDecimal calculatedBilledAmount = calculateBilledAmount(duration, price, vatIncluded, vatPercentage, false, BigDecimal.ZERO, false);

        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(BigDecimal.ZERO); // Special services in PHP didn't seem to have initial/base charge distinct from value
        callBuilder.billedAmount(calculatedBilledAmount.setScale(4, RoundingMode.HALF_UP));
        log.info("Applied Special Service pricing: Rate={}, Billed={}", price, calculatedBilledAmount);
    }

    public void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
        Optional<Map<String, Object>> tariffOpt = configurationLookupService.findInternalTariff(internalCallTypeId);
        if (tariffOpt.isPresent()) {
            Map<String, Object> tariff = tariffOpt.get();
            // Ensure all necessary keys are present with defaults if not returned by DB
            tariff.putIfAbsent("valor_minuto", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_minuto_iva", false);
            tariff.putIfAbsent("iva", BigDecimal.ZERO);
            tariff.putIfAbsent("valor_inicial", BigDecimal.ZERO); // PHP's PREFIJO_CARGO_BASICO equivalent
            tariff.putIfAbsent("valor_inicial_iva", false); // PHP's PREFIJO_CB_IVAINC equivalent
            tariff.putIfAbsent("ensegundos", false);
            log.info("Applying internal tariff for type {}: {}", internalCallTypeId, tariff);
            applyFinalPricing(tariff, duration, callBuilder);
        } else {
            log.info("No internal tariff found for type {}, setting cost to zero.", internalCallTypeId);
            callBuilder.pricePerMinute(BigDecimal.ZERO);
            callBuilder.initialPrice(BigDecimal.ZERO);
            callBuilder.billedAmount(BigDecimal.ZERO);
        }
    }

    public void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long operatorId = (Long) currentRateInfo.get("operator_id");
        Long bandId = (Long) currentRateInfo.get("band_id");

        List<Map<String, Object>> specialRatesMaps = specialRuleLookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );

        Optional<Map<String, Object>> applicableRateMapOpt = specialRatesMaps.stream()
                .filter(rateMap -> cdrEnrichmentHelper.isHourApplicable((String) rateMap.get("hours_specification"), callDateTime.getHour()))
                .findFirst(); // PHP logic implies taking the first most specific match

        if (applicableRateMapOpt.isPresent()) {
            Map<String, Object> rateMap = applicableRateMapOpt.get();
            log.info("Applying SpecialRateValue (ID: {})", rateMap.get("id"));
            BigDecimal originalRate = (BigDecimal) currentRateInfo.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) currentRateInfo.get("valor_minuto_iva");

            // PHP: Guardar_ValorInicial($infovalor);
            if (!currentRateInfo.containsKey("valor_inicial") || ((BigDecimal)currentRateInfo.get("valor_inicial")).compareTo(BigDecimal.ZERO) == 0) {
                currentRateInfo.put("valor_inicial", originalRate);
                currentRateInfo.put("valor_inicial_iva", originalVatIncluded);
            }

            Integer valueType = (Integer) rateMap.get("value_type"); // 0 = fixed value, 1 = percentage
            BigDecimal rateValueFromSpecial = (BigDecimal) rateMap.get("rate_value");
            Boolean includesVatInSpecial = (Boolean) rateMap.get("includes_vat");
            BigDecimal prefixVatValue = (BigDecimal) rateMap.get("prefix_vat_value"); // This is the VAT rate from associated prefix

            if (valueType != null && valueType == 1) { // Percentage discount
                BigDecimal discountPercentage = Optional.ofNullable(rateValueFromSpecial).orElse(BigDecimal.ZERO);
                // PHP: $valor_minuto = ValorSinIVA($infovalor);
                // $infovalor here is currentRateInfo *before* applying special rate
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
                currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                currentRateInfo.put("valor_minuto_iva", false); // Rate is now net, VAT will be added by calculateBilledAmount
                currentRateInfo.put("descuento_p", discountPercentage);
                log.info("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rateMap.get("id"));
            } else { // Fixed value
                currentRateInfo.put("valor_minuto", Optional.ofNullable(rateValueFromSpecial).orElse(BigDecimal.ZERO));
                currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(includesVatInSpecial).orElse(false));
                log.info("Applied fixed rate {} from SpecialRateValue {}", currentRateInfo.get("valor_minuto"), rateMap.get("id"));
            }
            currentRateInfo.put("iva", prefixVatValue); // Use the VAT rate from the associated prefix
            currentRateInfo.put("ensegundos", false); // Special rates in PHP are typically per minute

            String currentTypeName = (String) currentRateInfo.getOrDefault("telephony_type_name", "Unknown Type");
            if (!currentTypeName.contains("(xTarifaEsp)")) { // Avoid appending multiple times
                currentRateInfo.put("telephony_type_name", currentTypeName + " (xTarifaEsp)");
            }
        } else {
            log.info("No applicable special rate found, current rate info remains.");
            // Ensure initial price fields are set if not already (PHP's Guardar_ValorInicial behavior)
            currentRateInfo.putIfAbsent("valor_inicial", currentRateInfo.get("valor_minuto"));
            currentRateInfo.putIfAbsent("valor_inicial_iva", currentRateInfo.get("valor_minuto_iva"));
            currentRateInfo.putIfAbsent("ensegundos", false); // Default for non-special rates
        }
    }

    public void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
        BigDecimal pricePerMinute = Optional.ofNullable((BigDecimal) rateInfo.get("valor_minuto")).orElse(BigDecimal.ZERO);
        boolean vatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_minuto_iva")).orElse(false);
        BigDecimal vatPercentage = Optional.ofNullable((BigDecimal) rateInfo.get("iva")).orElse(BigDecimal.ZERO);
        boolean chargePerSecond = Optional.ofNullable((Boolean) rateInfo.get("ensegundos")).orElse(false);
        // PHP's $valor_original is $infovalor['valor_inicial'] after Guardar_ValorInicial
        BigDecimal initialPrice = Optional.ofNullable((BigDecimal) rateInfo.get("valor_inicial")).orElse(BigDecimal.ZERO);
        // boolean initialVatIncluded = Optional.ofNullable((Boolean) rateInfo.get("valor_inicial_iva")).orElse(false);

        BigDecimal calculatedBilledAmount = calculateBilledAmount(
                duration, pricePerMinute, vatIncluded, vatPercentage, chargePerSecond,
                initialPrice, (Boolean)rateInfo.getOrDefault("valor_inicial_iva", false) // Use the stored initial VAT flag
        );

        callBuilder.pricePerMinute(pricePerMinute.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(initialPrice.setScale(4, RoundingMode.HALF_UP)); // PHP stores this
        callBuilder.billedAmount(calculatedBilledAmount);
        log.info("Final pricing applied: Rate={}, Initial={}, Billed={}", pricePerMinute, initialPrice, calculatedBilledAmount);
    }

    public void applyTrunkRuleOverrides(Trunk trunk, Map<String, Object> currentRateInfo, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long indicatorId = (Long) currentRateInfo.get("indicator_id");
        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(trunk.getCommLocation());

        Optional<TrunkRule> trunkRuleOpt = trunkLookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);

        if (trunkRuleOpt.isPresent()) {
            TrunkRule rule = trunkRuleOpt.get();
            log.info("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());

            // PHP: Guardar_ValorInicial($infovalor, $infovalor_pre);
            if (!currentRateInfo.containsKey("valor_inicial") || ((BigDecimal)currentRateInfo.get("valor_inicial")).compareTo(BigDecimal.ZERO) == 0) {
                currentRateInfo.put("valor_inicial", currentRateInfo.get("valor_minuto"));
                currentRateInfo.put("valor_inicial_iva", currentRateInfo.get("valor_minuto_iva"));
            }

            currentRateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
            currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
            currentRateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);

            Long finalOperatorId = (Long) currentRateInfo.get("operator_id");
            Long finalTelephonyTypeId = telephonyTypeId;

            if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                finalOperatorId = rule.getNewOperatorId();
                callBuilder.operatorId(finalOperatorId);
                entityLookupService.findOperatorById(finalOperatorId).ifPresent(op -> currentRateInfo.put("operator_name", op.getName()));
            }
            if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                callBuilder.telephonyTypeId(finalTelephonyTypeId);
                entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> currentRateInfo.put("telephony_type_name", tt.getName()));
            }

            Long effectiveFinalTelephonyTypeId = finalTelephonyTypeId;
            Long effectiveFinalOperatorId = finalOperatorId;
            prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, finalOperatorId, originCountryId)
                    .ifPresentOrElse(
                            p -> currentRateInfo.put("iva", p.getVatValue()),
                            () -> {
                                log.info("No prefix found for rule-defined type {} / operator {}. Using default IVA 0.", effectiveFinalTelephonyTypeId, effectiveFinalOperatorId);
                                currentRateInfo.put("iva", BigDecimal.ZERO);
                            }
                    );
            String currentTypeName = (String) currentRateInfo.getOrDefault("telephony_type_name", "Unknown Type");
            if (!currentTypeName.contains("(xRegla)")) {
                currentRateInfo.put("telephony_type_name", currentTypeName + " (xRegla)");
            }
            currentRateInfo.put("applied_trunk_pricing_by_rule", true); // Flag that a trunk rule specifically set this rate
        } else {
            log.info("No TrunkRule found for trunk {}, type {}, indicator {}, originInd {}. Current rate info remains.",
                    trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);
        }
    }

    public BigDecimal calculateBilledAmount(int durationSeconds, BigDecimal rateValue, boolean rateVatIncluded, BigDecimal vatPercentage, boolean chargePerSecond, BigDecimal initialRateValue, boolean initialRateVatIncluded) {
        if (durationSeconds <= configService.getMinCallDurationForBilling()) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        BigDecimal effectiveRateValue = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
        BigDecimal durationUnits;

        if (chargePerSecond) {
            durationUnits = new BigDecimal(durationSeconds);
        } else {
            // PHP: $duracion_minuto = Duracion_Minuto($duracion, $ensegundos);
            // Duracion_Minuto rounds up to the next minute if any seconds exist.
            if (durationSeconds == 0) {
                durationUnits = BigDecimal.ZERO;
            } else {
                // Equivalent to ceil(durationSeconds / 60.0)
                durationUnits = new BigDecimal(durationSeconds)
                        .divide(SIXTY, 10, RoundingMode.CEILING) // Use high precision for division
                        .setScale(0, RoundingMode.CEILING); // Then round up to whole minute
                if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) {
                     durationUnits = BigDecimal.ONE; // Minimum 1 minute if any duration > 0
                }
            }
        }

        BigDecimal totalCost = effectiveRateValue.multiply(durationUnits);

        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }

        // PHP's PREFIJO_CARGO_BASICO logic is omitted as it's not in the new entities/flow.
        // The `initialRateValue` and `initialRateVatIncluded` are available if a different
        // first-minute charge logic were to be implemented, but current PHP `Calcular_Valor`
        // doesn't show a separate distinct initial charge application beyond the per-minute rate.

        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value.setScale(4, RoundingMode.HALF_UP); // Return as is, scaled
        }
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) {
            log.info("VAT divisor is zero, cannot remove VAT from {}", value);
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        return value.divide(vatDivisor, 4, RoundingMode.HALF_UP); // Scale result
    }
}