package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


@Service
@Log4j2
@RequiredArgsConstructor
public class TariffCalculationService {
    private final PrefixLookupService prefixLookupService;
    private final IndicatorLookupService indicatorLookupService;
    private final TrunkLookupService trunkLookupService;
    private final SpecialRateValueLookupService specialRateValueLookupService;
    private final TrunkRuleLookupService trunkRuleLookupService;
    private final CdrConfigService appConfigService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    /**
     * PHP equivalent: Main logic of `evaluarDestino` and its sub-functions like `evaluarDestino_pos`,
     * `buscarValor`, `Calcular_Valor`, `Obtener_ValorEspecial`, `Calcular_Valor_Reglas`.
     */
    public void calculateTariffs(CdrData cdrData, CommunicationLocation commLocation) {
        // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) $tipotele_id = _TIPOTELE_SINCONSUMO;
        if (cdrData.getDurationSeconds() < appConfigService.getMinCallDurationForTariffing() &&
            cdrData.getTelephonyTypeId() != null &&
            cdrData.getTelephonyTypeId() > 0 &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) { // Special services might have flat rates
            cdrData.setBilledAmount(BigDecimal.ZERO);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
            return;
        }
        
        // If it's a special service, tariff was already set during call type determination
        if (cdrData.getTelephonyTypeId() != null && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            SpecialServiceInfo ssi = (SpecialServiceInfo) cdrData.getAdditionalData().get("specialServiceTariff");
            if (ssi != null) {
                cdrData.setPricePerMinute(ssi.value);
                cdrData.setPriceIncludesVat(ssi.vatIncluded);
                cdrData.setVatRate(ssi.vatRate != null ? ssi.vatRate : BigDecimal.ZERO);
                cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData)); // Recalculate based on duration
                return;
            }
        }
        
        // Handle internal call tariffs (PHP: TarifasInternas)
        if (cdrData.isInternalCall() && telephonyTypeLookupService.isInternalIpType(cdrData.getTelephonyTypeId())) {
            TariffValue internalTariff = telephonyTypeLookupService.getInternalTariffValue(
                cdrData.getTelephonyTypeId(), commLocation.getIndicator().getOriginCountryId()
            );
            cdrData.setPricePerMinute(internalTariff.getRateValue());
            cdrData.setPriceIncludesVat(internalTariff.isIncludesVat());
            cdrData.setVatRate(internalTariff.getVatRate());
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
            return;
        }

        // --- Start of PHP's `evaluarDestino` logic ---
        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
        }

        String numberForTariffing = cdrData.getEffectiveDestinationNumber();
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        // PHP: $info_destino_limpio = limpiar_numero($info_destino, $prefijo_salida_pbx, true);
        // PHP: if ($existe_troncal === false) { $telefono = $info_destino_limpio; }
        if (trunkInfoOpt.isEmpty()) {
            numberForTariffing = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
        } else {
            TrunkInfo ti = trunkInfoOpt.get();
            boolean cleanDueToTrunkNoPbx = ti.noPbxPrefix != null && ti.noPbxPrefix;
            
            // Check specific rate details for NoPbxPrefix (PHP: $noprefijopbx from TARIFATRONCAL)
            if (cdrData.getTelephonyTypeId() != null && cdrData.getOperatorId() != null) {
                 Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetails.isPresent() && rateDetails.get().noPbxPrefix != null) {
                    cleanDueToTrunkNoPbx = rateDetails.get().noPbxPrefix;
                }
            }

            if (cleanDueToTrunkNoPbx) {
                 numberForTariffing = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
            }
            // else numberForTariffing remains cdrData.getEffectiveDestinationNumber()
        }
        
        List<Long> trunkTelephonyTypeIds = null;
        if (trunkInfoOpt.isPresent()) {
            trunkTelephonyTypeIds = trunkInfoOpt.get().getAllowedTelephonyTypeIds();
        }

        // PHP: $tipoteles_arr = buscarPrefijo($telefono, $existe_troncal, $mporigen_id, $link);
        List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
            numberForTariffing,
            commLocation,
            trunkInfoOpt.isPresent(),
            trunkTelephonyTypeIds
        );

        DestinationInfo bestDestInfo = null;
        PrefixInfo bestPrefixInfo = null;
        String finalNumberUsedForDestLookup = numberForTariffing; // Will be updated if prefix is stripped

        for (PrefixInfo prefixInfo : prefixes) {
            String numberWithoutPrefix = numberForTariffing;
            boolean stripPrefixForDestLookup = false; // PHP: $reducir = false;

            if (trunkInfoOpt.isPresent()) {
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    trunkInfoOpt.get().id, prefixInfo.telephonyTypeId, prefixInfo.operatorId
                );
                if (rateDetails.isPresent() && rateDetails.get().noPrefix != null) {
                    stripPrefixForDestLookup = rateDetails.get().noPrefix;
                }
            }

            if (stripPrefixForDestLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForTariffing.startsWith(prefixInfo.getPrefixCode())) {
                numberWithoutPrefix = numberForTariffing.substring(prefixInfo.getPrefixCode().length());
            }
            finalNumberUsedForDestLookup = numberWithoutPrefix; // Update for this iteration
            
            // PHP: $arr_destino_pre = buscarDestino(...)
            Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                numberWithoutPrefix,
                prefixInfo.telephonyTypeId,
                prefixInfo.telephonyTypeMinLength != null ? prefixInfo.telephonyTypeMinLength : 0,
                commLocation.getIndicatorId(),
                prefixInfo.prefixId,
                commLocation.getIndicator().getOriginCountryId(),
                prefixInfo.bandsAssociatedCount > 0 // PHP: $bandas_ok
            );

            if (destInfoOpt.isPresent()) {
                 // PHP logic for selecting the best match: prefer exact, then longer NDC.
                 // This is simplified here; PHP's $arr_destino_aprox adds complexity.
                 // We prioritize non-approximate matches.
                 if (bestDestInfo == null || (!bestDestInfo.isApproximateMatch() && destInfoOpt.get().isApproximateMatch())) {
                     // Keep current best if it's exact and new one is approximate
                 } else if (destInfoOpt.get().isApproximateMatch() && !bestDestInfo.isApproximateMatch()) {
                     // New is approximate, current is exact, keep current
                 }
                 else if (destInfoOpt.get().isApproximateMatch() == bestDestInfo.isApproximateMatch()) {
                    // Both same approx status, prefer longer NDC
                    if (destInfoOpt.get().getNdc() != null && (bestDestInfo.getNdc() == null || destInfoOpt.get().getNdc().length() > bestDestInfo.getNdc().length())) {
                        bestDestInfo = destInfoOpt.get();
                        bestPrefixInfo = prefixInfo;
                    }
                 } else { // New is exact, current was approx OR new is better exact
                    bestDestInfo = destInfoOpt.get();
                    bestPrefixInfo = prefixInfo;
                 }
                 if (bestDestInfo != null && !bestDestInfo.isApproximateMatch()) break; // Found an exact match, stop
            }
        }
        
        // PHP: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor))
        boolean initialResultIsInvalidOrAssumed = bestDestInfo == null ||
                                                  bestPrefixInfo == null ||
                                                  bestPrefixInfo.telephonyTypeId == null ||
                                                  bestPrefixInfo.telephonyTypeId <= 0 ||
                                                  bestPrefixInfo.telephonyTypeId == TelephonyTypeEnum.ERRORS.getValue() ||
                                                  (bestDestInfo.getDestinationDescription() != null && bestDestInfo.getDestinationDescription().contains(appConfigService.getAssumedText())) ||
                                                  (bestPrefixInfo.telephonyTypeName != null && bestPrefixInfo.telephonyTypeName.contains(appConfigService.getAssumedText()));

        if (trunkInfoOpt.isPresent() && initialResultIsInvalidOrAssumed) {
            log.debug("Trunk call destination not definitively found or was assumed. Attempting normalization for: {}", cdrData.getEffectiveDestinationNumber());
            // PHP: $prefijo_salida_pbx = ''; if ($existe_troncal['noprefijopbx']) ...
            // For normalization, PHP effectively re-evaluates as if it's not a trunk call,
            // potentially cleaning the PBX prefix if the trunk was *not* set to NoPbxPrefix.
            List<String> prefixesForNormalization = pbxPrefixes;
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                prefixesForNormalization = Collections.emptyList(); // Don't clean if trunk already expected no prefix
            }
            String normalizedNumberForLookup = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), prefixesForNormalization, true);
            
            List<PrefixInfo> normalizedPrefixes = prefixLookupService.findMatchingPrefixes(
                normalizedNumberForLookup, commLocation, false, null // false for isTrunkCall
            );
            DestinationInfo normalizedBestDestInfo = null;
            PrefixInfo normalizedBestPrefixInfo = null;
            String finalNormalizedNumberUsedForDestLookup = normalizedNumberForLookup;

            for (PrefixInfo normPrefixInfo : normalizedPrefixes) {
                String normNumberWithoutPrefix = normalizedNumberForLookup;
                 // For non-trunk (normalized) lookup, prefix is generally stripped
                 if (normPrefixInfo.getPrefixCode() != null && !normPrefixInfo.getPrefixCode().isEmpty() && normalizedNumberForLookup.startsWith(normPrefixInfo.getPrefixCode())) {
                    normNumberWithoutPrefix = normalizedNumberForLookup.substring(normPrefixInfo.getPrefixCode().length());
                }
                finalNormalizedNumberUsedForDestLookup = normNumberWithoutPrefix;

                Optional<DestinationInfo> normDestInfoOpt = indicatorLookupService.findDestinationIndicator(
                    normNumberWithoutPrefix, normPrefixInfo.telephonyTypeId,
                    normPrefixInfo.telephonyTypeMinLength != null ? normPrefixInfo.telephonyTypeMinLength : 0,
                    commLocation.getIndicatorId(), normPrefixInfo.prefixId,
                    commLocation.getIndicator().getOriginCountryId(), normPrefixInfo.bandsAssociatedCount > 0
                );
                if (normDestInfoOpt.isPresent() && !normDestInfoOpt.get().isApproximateMatch()) { // Prioritize exact normalized match
                    normalizedBestDestInfo = normDestInfoOpt.get();
                    normalizedBestPrefixInfo = normPrefixInfo;
                    break;
                } else if (normDestInfoOpt.isPresent() && normalizedBestDestInfo == null) { // First approximate normalized match
                    normalizedBestDestInfo = normDestInfoOpt.get();
                    normalizedBestPrefixInfo = normPrefixInfo;
                }
            }

            if (normalizedBestDestInfo != null && normalizedBestPrefixInfo != null) {
                boolean useNormalized = true;
                // PHP: if (($infovalor['tipotele'] != $infovalor_pos['tipotele'] || $infovalor['indicativo'] != $infovalor_pos['indicativo']) ... )
                if (bestDestInfo != null && bestPrefixInfo != null) {
                    if (Objects.equals(bestPrefixInfo.telephonyTypeId, normalizedBestPrefixInfo.telephonyTypeId) &&
                        Objects.equals(bestDestInfo.getIndicatorId(), normalizedBestDestInfo.getIndicatorId())) {
                        useNormalized = false; // Don't switch if type and indicator are the same
                    }
                }
                if (useNormalized && normalizedBestPrefixInfo.telephonyTypeId > 0 && normalizedBestPrefixInfo.telephonyTypeId != TelephonyTypeEnum.ERRORS.getValue()) {
                    log.debug("Using normalized tariffing result for trunk call.");
                    bestDestInfo = normalizedBestDestInfo;
                    bestPrefixInfo = normalizedBestPrefixInfo;
                    finalNumberUsedForDestLookup = finalNormalizedNumberUsedForDestLookup; // Use the number that led to this match
                    trunkInfoOpt = Optional.empty(); // Treat as non-trunk for tariff application
                    cdrData.getAdditionalData().put("normalizedTariffApplied", true);
                }
            }
        }


        if (bestDestInfo != null && bestPrefixInfo != null) {
            cdrData.setTelephonyTypeId(bestPrefixInfo.telephonyTypeId);
            cdrData.setTelephonyTypeName(bestPrefixInfo.telephonyTypeName);
            cdrData.setOperatorId(bestPrefixInfo.operatorId);
            cdrData.setOperatorName(bestPrefixInfo.operatorName);
            cdrData.setIndicatorId(bestDestInfo.getIndicatorId());
            cdrData.setDestinationCityName(bestDestInfo.getDestinationDescription());
            cdrData.setEffectiveDestinationNumber(bestDestInfo.getMatchedPhoneNumber()); // The number that matched

            // PHP: if ($tipotele_id == _TIPOTELE_LOCAL && BuscarLocalExtendida(...))
            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(bestDestInfo.getNdc(), commLocation.getIndicatorId(), bestDestInfo.getIndicatorId())) {
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
                // PHP: AsignarLocalExtendida updates operator and prefix from _lista_Prefijos['local_ext']
                PrefixInfo localExtPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId());
                if (localExtPrefixInfo != null) {
                    cdrData.setOperatorId(localExtPrefixInfo.getOperatorId());
                    cdrData.setOperatorName(localExtPrefixInfo.getOperatorName());
                    bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId(); // Update prefixId for buscarValor
                }
            }

            // PHP: $infovalor = buscarValor($tipotele_id, $prefijo_id, $indicadestino, $comubicacion_id, $link);
            TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(
                bestPrefixInfo.prefixId,
                bestDestInfo.getIndicatorId(),
                commLocation.getId(),
                commLocation.getIndicatorId() // originIndicatorIdForBand
            );
            
            cdrData.setPricePerMinute(baseTariff.getRateValue());
            cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
            cdrData.setVatRate(baseTariff.getVatRate());

            // PHP: if ($existe_troncal !== false) { ... }
            if (trunkInfoOpt.isPresent() && !Boolean.TRUE.equals(cdrData.getAdditionalData().get("normalizedTariffApplied"))) {
                TrunkInfo ti = trunkInfoOpt.get();
                // PHP: $operador_troncal = buscarOperador_Troncal($existe_troncal, $tipotele_id, $prefijo_actual, $operador_id);
                // The operatorId in bestPrefixInfo is the one determined by the prefix.
                // We need to check if this operator (or a generic one '0') is configured for the trunk rate.
                Optional<TrunkRateDetails> rateDetailsOpt = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId() // Use operator from prefix match
                );
                if (rateDetailsOpt.isPresent()) {
                    TrunkRateDetails rd = rateDetailsOpt.get();
                    // PHP: Guardar_ValorInicial($infovalor, $infovalor_pre);
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute()); // Store current before overriding
                    cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

                    cdrData.setPricePerMinute(rd.rateValue);
                    cdrData.setPriceIncludesVat(rd.includesVat);
                    cdrData.setChargeBySecond(rd.seconds != null && rd.seconds > 0);
                    // PHP: $infovalor['iva'] = IVA_Troncal($tipotele_id, $operador_troncal);
                    cdrData.setVatRate(telephonyTypeLookupService.getVatForPrefix(rd.telephonyTypeId, rd.operatorId, commLocation.getIndicator().getOriginCountryId()));
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Trunk: " + ti.description + ")");

                    // PHP: if ($infovalor['celufijo'] && $tipotele_id == _TIPOTELE_CELULAR)
                    if (ti.isCelufijo() && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue()) {
                        cdrData.setTelephonyTypeId(TelephonyTypeEnum.CELUFIJO.getValue());
                        cdrData.setTelephonyTypeName("Celufijo (Trunk: " + ti.description + ")");
                    }
                }
            }
            
            // Store initial price if not already set (e.g. by trunk logic)
            if (cdrData.getInitialPricePerMinute() == null || cdrData.getInitialPricePerMinute().compareTo(BigDecimal.ZERO) == 0) {
                 cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                 cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
            }


            // PHP: Obtener_ValorEspecial($link, $fecha, $tiempo, $indicaorigen, &$infovalor)
            Optional<SpecialRateInfo> specialRateOpt =
                specialRateValueLookupService.getApplicableSpecialRate(
                    cdrData.getDateTimeOrigination(),
                    commLocation.getIndicatorId(), // indicaorigen
                    cdrData.getTelephonyTypeId(),
                    cdrData.getOperatorId(),
                    bestDestInfo.getBandId()
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                // PHP: Guardar_ValorInicial($infovalor); (already done if trunk, or now if not trunk)
                if (cdrData.getInitialPricePerMinute() == null || cdrData.getInitialPricePerMinute().compareTo(BigDecimal.ZERO) == 0) {
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                    cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
                }

                if (sr.valueType == 0) { // Absolute value
                    cdrData.setPricePerMinute(sr.rateValue);
                    cdrData.setPriceIncludesVat(sr.includesVat);
                } else { // Percentage discount
                    BigDecimal currentRateNoVat = cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0 ?
                        cdrData.getPricePerMinute().divide(BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)), 8, RoundingMode.HALF_UP) :
                        cdrData.getPricePerMinute();
                    
                    BigDecimal discountPercentage = sr.rateValue; // rateValue stores the percentage
                    BigDecimal discountFactor = BigDecimal.ONE.subtract(discountPercentage.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
                    cdrData.setPricePerMinute(currentRateNoVat.multiply(discountFactor));
                    // Price is now effectively without VAT, so includesVat should be false for this step
                    cdrData.setPriceIncludesVat(false);
                    cdrData.getAdditionalData().put("specialRateDiscountPercentage", discountPercentage);
                }
                cdrData.setVatRate(sr.vatRate); // Use VAT from the special rate's context
                cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Special Rate)");
            }

            // PHP: Calcular_Valor_Reglas($link, $info_co, $duracion, $valor_facturado, $resultado_directorio, &$infovalor)
            if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
                 Optional<AppliedTrunkRuleInfo> ruleInfoOpt =
                    trunkRuleLookupService.getAppliedTrunkRule(
                        cdrData.getDestDeviceName(), // info_co
                        cdrData.getTelephonyTypeId(), 
                        cdrData.getIndicatorId(), // indicadestino
                        commLocation.getIndicatorId() // indicaorigen
                    );
                if (ruleInfoOpt.isPresent()) {
                    AppliedTrunkRuleInfo rule = ruleInfoOpt.get();
                    // PHP: Guardar_ValorInicial($infovalor);
                    if (cdrData.getInitialPricePerMinute() == null || cdrData.getInitialPricePerMinute().compareTo(BigDecimal.ZERO) == 0) {
                         cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                         cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
                    }

                    cdrData.setPricePerMinute(rule.rateValue);
                    cdrData.setPriceIncludesVat(rule.includesVat);
                    cdrData.setChargeBySecond(rule.seconds != null && rule.seconds > 0);
                    
                    if (rule.newTelephonyTypeId != null && rule.newTelephonyTypeId != 0L) {
                        cdrData.setTelephonyTypeId(rule.newTelephonyTypeId);
                        cdrData.setTelephonyTypeName(rule.newTelephonyTypeName != null ? rule.newTelephonyTypeName : telephonyTypeLookupService.getTelephonyTypeName(rule.newTelephonyTypeId));
                    }
                    if (rule.newOperatorId != null && rule.newOperatorId != 0L) {
                        cdrData.setOperatorId(rule.newOperatorId);
                        cdrData.setOperatorName(rule.newOperatorName != null ? rule.newOperatorName : "OperatorID:" + rule.newOperatorId);
                    }
                    cdrData.setVatRate(rule.vatRate);
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Rule Applied)");
                }
            }
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        } else {
            log.warn("Could not determine destination or tariff for: {} (original: {})", finalNumberUsedForDestLookup, numberForTariffing);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.ERRORS.getValue()));
            cdrData.setBilledAmount(BigDecimal.ZERO);
        }
    }

    /**
     * PHP equivalent: Calcular_Valor
     */
    private BigDecimal calculateFinalBilledAmount(CdrData cdrData) {
        if (cdrData.getPricePerMinute() == null || cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() < 0) {
            return BigDecimal.ZERO;
        }

        long billableDurationUnits;
        BigDecimal ratePerUnit;

        // PHP: $duracion_minuto = Duracion_Minuto($duracion, $ensegundos);
        if (cdrData.isChargeBySecond()) {
            billableDurationUnits = cdrData.getDurationSeconds();
            ratePerUnit = cdrData.getPricePerMinute().divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
        } else {
            billableDurationUnits = (long) Math.ceil((double) cdrData.getDurationSeconds() / 60.0);
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1; // Min 1 minute if any duration
            ratePerUnit = cdrData.getPricePerMinute();
        }
        
        if (billableDurationUnits == 0) return BigDecimal.ZERO;

        BigDecimal totalCost = ratePerUnit.multiply(BigDecimal.valueOf(billableDurationUnits));

        // PHP: if (!$infovalor['valor_minuto_iva']) { $valor_minuto = $valor_minuto * $valor_iva; }
        if (!cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }
        
        // PHP: $valor_facturado = ( $valor_minuto + $cargo_basico ); (cargo_basico is removed)
        return totalCost.setScale(4, RoundingMode.HALF_UP); // PHP uses 4 decimal places for VALOR_FACTURADO
    }
}