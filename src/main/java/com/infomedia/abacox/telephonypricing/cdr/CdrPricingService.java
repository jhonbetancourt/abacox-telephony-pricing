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
public class CdrPricingService {

    private final PrefixInfoLookupService prefixInfoLookupService;
    private final ConfigurationLookupService configurationLookupService;
    private final SpecialRuleLookupService specialRuleLookupService;
    private final TrunkLookupService trunkLookupService;
    private final EntityLookupService entityLookupService;
    private final CdrProcessingConfig configService;
    private final CdrEnrichmentHelper cdrEnrichmentHelper;


    private static final BigDecimal SIXTY = new BigDecimal("60");

    public Optional<Map<String, Object>> findRateInfoForLocal(CommunicationLocation commLocation, String effectiveNumber) {
        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(commLocation);
        Long originIndicatorId = commLocation.getIndicatorId();
        Long localType = CdrProcessingConfig.TIPOTELE_LOCAL;

        if (originCountryId == null || originIndicatorId == null) {
            log.error("Cannot find LOCAL rate: Missing Origin Country ({}) or Comm Location Indicator ID ({}) for Location {}",
                    originCountryId, originIndicatorId, commLocation.getId());
            return Optional.empty();
        }

        Optional<Operator> internalOpOpt = configService.getOperatorInternal(localType, originCountryId);
        if (internalOpOpt.isEmpty()) {
            log.warn("Cannot find internal operator for LOCAL type ({}) in country {}", localType, originCountryId);
            return Optional.empty();
        }
        Long internalOperatorId = internalOpOpt.get().getId();

        Optional<Prefix> localPrefixOpt = prefixInfoLookupService.findPrefixByTypeOperatorOrigin(localType, internalOperatorId, originCountryId);
        if (localPrefixOpt.isEmpty()) {
            log.warn("Cannot find Prefix entity for LOCAL type ({}) and Operator {} in Country {}", localType, internalOperatorId, originCountryId);
            return Optional.empty();
        }
        Prefix localPrefix = localPrefixOpt.get();

        // PHP: $telefono = $indicativo_origen.$telefono; (when _esLocal)
        // For local calls, the number passed to findIndicatorByNumber should be the subscriber part,
        // and the NDC is implicitly the local area's NDC.
        // However, findIndicatorByNumber expects the full number (NDC + subscriber) if NDC is part of the series definition.
        // If local series are defined with NDC=0 or null, then effectiveNumber should be just subscriber.
        // If local series are defined with the actual local NDC, then effectiveNumber should be NDC+subscriber.
        String numberForIndicatorLookup = effectiveNumber;
        Optional<Integer> localNdcOpt = prefixInfoLookupService.findLocalNdcForIndicator(originIndicatorId);
        if (localNdcOpt.isPresent() && !effectiveNumber.startsWith(String.valueOf(localNdcOpt.get()))) {
             // This implies effectiveNumber is just the subscriber part, and we need to prepend the local NDC
             // if series are defined with NDCs.
             // However, if series are defined with NDC=0 for local, this isn't needed.
             // The findIndicatorByNumber logic handles iterating NDC lengths, so passing the subscriber part might be fine if NDC is 0.
             // For safety and to match PHP's implicit behavior of $telefono = $indicativo_origen.$telefono;
             // we should ensure the number passed to findIndicatorByNumber is what it expects.
             // If local series have NDC=0, effectiveNumber is fine. If they have actual NDCs, it needs prefixing.
             // The current findIndicatorByNumber logic iterates NDC lengths, so it should handle both.
             // We will assume effectiveNumber is the subscriber part for local calls if no NDC is prefixed.
        }


        Optional<Map<String, Object>> indicatorInfoOpt = prefixInfoLookupService.findIndicatorByNumber(
                numberForIndicatorLookup, localType, originCountryId,
                localPrefix.isBandOk(), localPrefix.getId(), originIndicatorId
        );

        boolean considerMatch = indicatorInfoOpt.isPresent();
        if (!considerMatch) {
            Map<String, Integer> typeMinMax = configService.getTelephonyTypeMinMax(localType, originCountryId);
            int typeMaxLength = typeMinMax.getOrDefault("max", 0);
            // PHP: ($arr_destino_pre['INDICA_MAX'] <= 0 && strlen($info_destino) == $tipotelemax)
            // This means if no specific indicator series matched, but the number length perfectly matches the type's max length,
            // AND there are no NDC definitions for this type (maxNdcLength=0), consider it a match for rating purposes (assumed destination).
            if (cdrEnrichmentHelper.maxNdcLength(localType, originCountryId) == 0 && effectiveNumber.length() == typeMaxLength) {
                considerMatch = true;
                log.debug("LOCAL fallback: No specific indicator for {}, but length matches type max. Considering match.", effectiveNumber);
            }
        }

        if (!considerMatch) {
             log.warn("LOCAL fallback: Could not find LOCAL indicator for number {} and length does not match type max.", effectiveNumber);
             return Optional.empty();
        }

        Long destinationIndicatorId = indicatorInfoOpt
            .map(ind -> (Long) ind.get("indicator_id"))
            .filter(id -> id != null && id > 0)
            .orElse(null);
        // If no specific destination indicator was found (e.g., length match only for types with no NDCs),
        // and the prefix code for local is empty (meaning it's a direct local call),
        // then the destination indicator is effectively the origin indicator.
        if (destinationIndicatorId == null && (localPrefix.getCode() == null || localPrefix.getCode().isEmpty())) {
            destinationIndicatorId = originIndicatorId;
            log.trace("LOCAL fallback: Using originIndicatorId {} as destinationIndicatorId for number {}", originIndicatorId, effectiveNumber);
        }

        Integer destinationNdc = indicatorInfoOpt.map(ind -> (ind.get("series_ndc") instanceof Number ? ((Number)ind.get("series_ndc")).intValue() : (ind.get("series_ndc") != null ? Integer.parseInt(String.valueOf(ind.get("series_ndc"))) : null))).orElse(null);


        Long finalTelephonyTypeId = localType;
        Long finalPrefixId = localPrefix.getId();
        boolean finalBandOk = localPrefix.isBandOk();
        String finalTelephonyTypeName = configService.getTelephonyTypeById(localType).map(TelephonyType::getName).orElse("Local");

        if (destinationNdc != null) { // Only check for local extended if an NDC was actually found
            boolean isExtended = prefixInfoLookupService.isLocalExtended(destinationNdc, originIndicatorId);
            if (isExtended) {
                finalTelephonyTypeId = CdrProcessingConfig.TIPOTELE_LOCAL_EXT;
                finalTelephonyTypeName = configService.getTelephonyTypeById(finalTelephonyTypeId).map(TelephonyType::getName).orElse("Local Extended");
                log.debug("Reclassified LOCAL fallback call to {} as LOCAL_EXTENDED based on NDC {} and origin {}", effectiveNumber, destinationNdc, originIndicatorId);
                Optional<Operator> localExtOpOpt = configService.getOperatorInternal(finalTelephonyTypeId, originCountryId);
                if (localExtOpOpt.isPresent()) {
                    Optional<Prefix> localExtPrefixOpt = prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, localExtOpOpt.get().getId(), originCountryId);
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
            rateInfo.put("operator_id", internalOperatorId);
            rateInfo.put("indicator_id", destinationIndicatorId);
            rateInfo.put("telephony_type_name", finalTelephonyTypeName);
            rateInfo.put("operator_name", internalOpOpt.get().getName());
            rateInfo.put("destination_name", indicatorInfoOpt.map(cdrEnrichmentHelper::formatDestinationName).orElse(cdrEnrichmentHelper.formatDestinationName(Map.of("city_name", "Local", "department_country", ""))));
            return Optional.of(rateInfo);
        } else {
            log.warn("Rate info not found for LOCAL fallback (Type: {}, Prefix: {}, Indicator: {})", finalTelephonyTypeId, finalPrefixId, destinationIndicatorId);
        }
        return Optional.empty();
    }

    public Optional<Map<String, Object>> findRateInfo(Long prefixId, Long indicatorId, Long originIndicatorId, boolean bandOk) {
        Optional<Map<String, Object>> baseRateOpt = prefixInfoLookupService.findBaseRateForPrefix(prefixId);
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

        Long telephonyTypeIdFromPrefix = (Long) rateInfo.get("telephony_type_id"); // Get from baseRateOpt content
        boolean isLocalTypeForBandCheck = (telephonyTypeIdFromPrefix != null &&
                telephonyTypeIdFromPrefix.equals(CdrProcessingConfig.TIPOTELE_LOCAL));

        // PHP: $usar_bandas = (1 * $info_indica['PREFIJO_BANDAOK'] > 0);
        // PHP: if ($usar_bandas && ($indicadestino > 0 || $es_local))
        boolean useEffectiveBandLookup = bandOk && ( (indicatorId != null && indicatorId > 0) || isLocalTypeForBandCheck );

        log.trace("findRateInfo: prefixId={}, indicatorId={}, originIndicatorId={}, bandOk={}, isLocalType={}, useEffectiveBandLookup={}",
                prefixId, indicatorId, originIndicatorId, bandOk, isLocalTypeForBandCheck, useEffectiveBandLookup);

        if (useEffectiveBandLookup) {
            // For local calls where no specific destination indicator is found (indicatorId is null or 0),
            // the band lookup should use the originIndicatorId as the effective "destination" for bands defined at origin.
            // PHP: $adcondicion = " AND BANDA_ID = BANDAINDICA_BANDA_ID AND BANDAINDICA_INDICATIVO_ID = $indicadestino";
            // PHP: if (!$es_local) { ... } else { // for local, bandaindica is not joined }
            Long indicatorForBandLookup = (isLocalTypeForBandCheck && (indicatorId == null || indicatorId <= 0))
                                          ? originIndicatorId
                                          : indicatorId;
            // If it's local and no specific dest indicator, bands are typically defined against origin or globally for that prefix.
            // If it's not local, indicatorId must be > 0 for band_indicator join.
            // The query in findBandByPrefixAndIndicator handles the join conditionally.

            Optional<Map<String, Object>> bandOpt = prefixInfoLookupService.findBandByPrefixAndIndicator(prefixId, indicatorForBandLookup, originIndicatorId);
            if (bandOpt.isPresent()) {
                 Map<String, Object> bandInfo = bandOpt.get();
                rateInfo.put("base_value", bandInfo.get("band_value")); // Band value overrides prefix base_value
                rateInfo.put("vat_included", bandInfo.get("band_vat_included"));
                rateInfo.put("band_id", bandInfo.get("band_id"));
                rateInfo.put("band_name", bandInfo.get("band_name"));
                log.trace("Using band rate for prefix {}, effective indicator {}: BandID={}, Value={}, VatIncluded={}",
                        prefixId, indicatorForBandLookup, bandInfo.get("band_id"), bandInfo.get("band_value"), bandInfo.get("band_vat_included"));
            } else {
                log.trace("Band lookup enabled for prefix {} but no matching band found for effective indicator {}", prefixId, indicatorForBandLookup);
            }
        } else {
            log.trace("Using base rate for prefix {} (Bands not applicable or indicator missing/null for non-local)", prefixId);
        }
        // Ensure these fields are populated from either band or prefix base rate
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
        // In PHP, SERVESPECIAL_IVA was the VAT *amount*, not percentage.
        // If it's percentage, logic is fine. If it's amount, pricing needs adjustment.
        // Assuming SERVESPECIAL_IVA is percentage for now, matching the structure of prefix.vat_value.
        BigDecimal vatPercentage = Optional.ofNullable(specialService.getVatAmount()).orElse(BigDecimal.ZERO);

        BigDecimal calculatedBilledAmount = calculateBilledAmount(duration, price, vatIncluded, vatPercentage, false, BigDecimal.ZERO, false);

        callBuilder.pricePerMinute(price.setScale(4, RoundingMode.HALF_UP));
        callBuilder.initialPrice(BigDecimal.ZERO);
        callBuilder.billedAmount(calculatedBilledAmount.setScale(4, RoundingMode.HALF_UP));
        log.debug("Applied Special Service pricing: Rate={}, Billed={}", price, calculatedBilledAmount);
    }

    public void applyInternalPricing(Long internalCallTypeId, CallRecord.CallRecordBuilder callBuilder, int duration) {
        Optional<Map<String, Object>> tariffOpt = configurationLookupService.findInternalTariff(internalCallTypeId);
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

    public void applySpecialPricing(Map<String, Object> currentRateInfo, LocalDateTime callDateTime, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long operatorId = (Long) currentRateInfo.get("operator_id");
        Long bandId = (Long) currentRateInfo.get("band_id"); // band_id is already in currentRateInfo from findRateInfo

        List<Map<String, Object>> specialRatesMaps = specialRuleLookupService.findSpecialRateValues(
                telephonyTypeId, operatorId, bandId, originIndicatorId, callDateTime
        );

        Optional<Map<String, Object>> applicableRateMapOpt = specialRatesMaps.stream()
                .filter(rateMap -> cdrEnrichmentHelper.isHourApplicable((String) rateMap.get("hours_specification"), callDateTime.getHour()))
                .findFirst();

        if (applicableRateMapOpt.isPresent()) {
            Map<String, Object> rateMap = applicableRateMapOpt.get();
            log.debug("Applying SpecialRateValue (ID: {})", rateMap.get("id"));
            BigDecimal originalRate = (BigDecimal) currentRateInfo.get("valor_minuto");
            boolean originalVatIncluded = (Boolean) currentRateInfo.get("valor_minuto_iva");

            // PHP: Guardar_ValorInicial($infovalor);
            if (!currentRateInfo.containsKey("valor_inicial") || ((BigDecimal)currentRateInfo.get("valor_inicial")).compareTo(BigDecimal.ZERO) == 0) {
                currentRateInfo.put("valor_inicial", originalRate);
                currentRateInfo.put("valor_inicial_iva", originalVatIncluded);
            }

            Integer valueType = (Integer) rateMap.get("value_type"); // 0 = fixed value, 1 = percentage
            BigDecimal rateValue = (BigDecimal) rateMap.get("rate_value");
            Boolean includesVat = (Boolean) rateMap.get("includes_vat");
            BigDecimal prefixVatValue = (BigDecimal) rateMap.get("prefix_vat_value"); // This is the VAT rate

            if (valueType != null && valueType == 1) { // Percentage discount
                BigDecimal discountPercentage = Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO);
                // PHP: $valor_minuto = ValorSinIVA($infovalor);
                BigDecimal currentRateNoVat = calculateValueWithoutVat(originalRate, (BigDecimal) currentRateInfo.get("iva"), originalVatIncluded);
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(discountPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
                currentRateInfo.put("valor_minuto", currentRateNoVat.multiply(discountMultiplier));
                currentRateInfo.put("valor_minuto_iva", false); // Rate is now net, VAT will be added by calculateBilledAmount
                currentRateInfo.put("descuento_p", discountPercentage); // Store for info
                log.trace("Applied percentage discount {}% from SpecialRateValue {}", discountPercentage, rateMap.get("id"));
            } else { // Fixed value
                currentRateInfo.put("valor_minuto", Optional.ofNullable(rateValue).orElse(BigDecimal.ZERO));
                currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(includesVat).orElse(false));
                log.trace("Applied fixed rate {} from SpecialRateValue {}", currentRateInfo.get("valor_minuto"), rateMap.get("id"));
            }
            currentRateInfo.put("iva", prefixVatValue); // Use the VAT rate from the associated prefix
            currentRateInfo.put("ensegundos", false); // Special rates are typically per minute

            String currentTypeName = (String) currentRateInfo.getOrDefault("telephony_type_name", "Unknown Type");
            if (!currentTypeName.contains("(xTarifaEsp)")) {
                currentRateInfo.put("telephony_type_name", currentTypeName + " (xTarifaEsp)");
            }
        } else {
            log.debug("No applicable special rate found, current rate info remains.");
            // Ensure initial price fields are set if not already
            currentRateInfo.putIfAbsent("valor_inicial", currentRateInfo.get("valor_minuto"));
            currentRateInfo.putIfAbsent("valor_inicial_iva", currentRateInfo.get("valor_minuto_iva"));
            currentRateInfo.putIfAbsent("ensegundos", false);
        }
    }

    public void applyFinalPricing(Map<String, Object> rateInfo, int duration, CallRecord.CallRecordBuilder callBuilder) {
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
        callBuilder.billedAmount(calculatedBilledAmount); // Already scaled by calculateBilledAmount
        log.trace("Final pricing applied: Rate={}, Initial={}, Billed={}", pricePerMinute, initialPrice, calculatedBilledAmount);
    }

    public void applyTrunkRuleOverrides(Trunk trunk, Map<String, Object> currentRateInfo, int duration, Long originIndicatorId, CallRecord.CallRecordBuilder callBuilder) {
        Long telephonyTypeId = (Long) currentRateInfo.get("telephony_type_id");
        Long indicatorId = (Long) currentRateInfo.get("indicator_id");
        Long originCountryId = cdrEnrichmentHelper.getOriginCountryId(trunk.getCommLocation());

        Optional<TrunkRule> trunkRuleOpt = trunkLookupService.findTrunkRule(trunk.getName(), telephonyTypeId, indicatorId, originIndicatorId);

        if (trunkRuleOpt.isPresent()) {
            TrunkRule rule = trunkRuleOpt.get();
            log.debug("Applying TrunkRule {} for trunk {}", rule.getId(), trunk.getName());

            // PHP: Guardar_ValorInicial($infovalor, $infovalor_pre);
            // If valor_inicial is not set or zero, set it to the current valor_minuto before overriding.
            if (!currentRateInfo.containsKey("valor_inicial") || ((BigDecimal)currentRateInfo.get("valor_inicial")).compareTo(BigDecimal.ZERO) == 0) {
                currentRateInfo.put("valor_inicial", currentRateInfo.get("valor_minuto"));
                currentRateInfo.put("valor_inicial_iva", currentRateInfo.get("valor_minuto_iva"));
            }

            currentRateInfo.put("valor_minuto", Optional.ofNullable(rule.getRateValue()).orElse(BigDecimal.ZERO));
            currentRateInfo.put("valor_minuto_iva", Optional.ofNullable(rule.getIncludesVat()).orElse(false));
            currentRateInfo.put("ensegundos", rule.getSeconds() != null && rule.getSeconds() > 0);

            Long finalOperatorId = (Long) currentRateInfo.get("operator_id"); // Start with current
            Long finalTelephonyTypeId = telephonyTypeId; // Start with current

            if (rule.getNewOperatorId() != null && rule.getNewOperatorId() > 0) {
                finalOperatorId = rule.getNewOperatorId();
                callBuilder.operatorId(finalOperatorId); // Update CallRecord
                entityLookupService.findOperatorById(finalOperatorId).ifPresent(op -> currentRateInfo.put("operator_name", op.getName()));
            }
            if (rule.getNewTelephonyTypeId() != null && rule.getNewTelephonyTypeId() > 0) {
                finalTelephonyTypeId = rule.getNewTelephonyTypeId();
                callBuilder.telephonyTypeId(finalTelephonyTypeId); // Update CallRecord
                entityLookupService.findTelephonyTypeById(finalTelephonyTypeId).ifPresent(tt -> currentRateInfo.put("telephony_type_name", tt.getName()));
            }

            // Fetch VAT for the (potentially new) operator and telephony type
            Long finalTelephonyTypeId1 = finalTelephonyTypeId; // effectively final for lambda
            Long finalOperatorId1 = finalOperatorId; // effectively final for lambda
            prefixInfoLookupService.findPrefixByTypeOperatorOrigin(finalTelephonyTypeId, finalOperatorId, originCountryId)
                    .ifPresentOrElse(
                            p -> currentRateInfo.put("iva", p.getVatValue()),
                            () -> {
                                log.warn("No prefix found for rule-defined type {} / operator {}. Using default IVA 0.", finalTelephonyTypeId1, finalOperatorId1);
                                currentRateInfo.put("iva", BigDecimal.ZERO);
                            }
                    );
            String currentTypeName = (String) currentRateInfo.getOrDefault("telephony_type_name", "Unknown Type");
            if (!currentTypeName.contains("(xRegla)")) { // Avoid appending multiple times
                currentRateInfo.put("telephony_type_name", currentTypeName + " (xRegla)");
            }
            currentRateInfo.put("applied_trunk_pricing", true); // Indicate that trunk logic (rule) has set the rate
        } else {
            log.trace("No TrunkRule found for trunk {}, type {}, indicator {}, originInd {}. Current rate info remains.",
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
            durationUnits = new BigDecimal(durationSeconds).divide(SIXTY, 10, RoundingMode.CEILING); // Use high precision for division
            durationUnits = durationUnits.setScale(0, RoundingMode.CEILING); // Then round up to whole minute
            if (durationUnits.compareTo(BigDecimal.ZERO) == 0 && durationSeconds > 0) {
                durationUnits = BigDecimal.ONE; // Minimum 1 minute if any duration
            }
        }

        BigDecimal totalCost = effectiveRateValue.multiply(durationUnits);

        if (!rateVatIncluded && vatPercentage != null && vatPercentage.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }

        // PHP logic for cargo_basico is omitted as it was removed from entities.
        // If initialRateValue is used (e.g. first minute different rate), it would be handled here.
        // The current PHP logic doesn't show a distinct initial charge separate from per-minute.
        // If `valor_inicial` was meant to be a fixed first-minute charge, this logic would need adjustment.
        // For now, assuming `valor_minuto` applies to all units.

        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateValueWithoutVat(BigDecimal value, BigDecimal vatPercentage, boolean vatIncluded) {
        if (value == null) return BigDecimal.ZERO;
        if (!vatIncluded || vatPercentage == null || vatPercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal vatDivisor = BigDecimal.ONE.add(vatPercentage.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)); // Increased precision for divisor
        if (vatDivisor.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("VAT divisor is zero, cannot remove VAT from {}", value);
            return value.setScale(4, RoundingMode.HALF_UP); // Return original if divisor is zero
        }
        return value.divide(vatDivisor, 4, RoundingMode.HALF_UP);
    }
}