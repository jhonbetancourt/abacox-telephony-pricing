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
        if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing() && // Use <= to match PHP's $tiempo <= 0
            cdrData.getTelephonyTypeId() != null &&
            cdrData.getTelephonyTypeId() > 0 && // Must have a type
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) { // Special services might have cost even for 0 duration
            cdrData.setBilledAmount(BigDecimal.ZERO);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
            return;
        }
        
        // Handle special services first as they have their own tariff logic
        if (cdrData.getTelephonyTypeId() != null && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            SpecialServiceInfo ssi = cdrData.getSpecialServiceTariff();
            if (ssi != null) {
                cdrData.setPricePerMinute(ssi.value);
                cdrData.setPriceIncludesVat(ssi.vatIncluded);
                cdrData.setVatRate(ssi.vatRate != null ? ssi.vatRate : BigDecimal.ZERO);
                cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
                return;
            } else { // Should not happen if type is SPECIAL_SERVICES, but as a fallback
                log.warn("TelephonyType is SPECIAL_SERVICES but no SpecialServiceInfo found for CDR: {}", cdrData.getRawCdrLine());
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.ERRORS.getValue()));
                cdrData.setBilledAmount(BigDecimal.ZERO);
                return;
            }
        }
        
        // Handle internal IP calls
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
        // PHP: $existe_troncal  = buscarTroncal($info_co, $comubicacion_id, $link);
        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
        }

        String numberForTariffing = cdrData.getEffectiveDestinationNumber();
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        // PHP: $info_destino_limpio = limpiar_numero($info_destino, $prefijo_salida_pbx, true);
        // PHP: if ($existe_troncal === false) { $telefono = $info_destino_limpio; }
        // PHP: else { // (trunk exists) ... if (!$noprefijopbx) { $telefono = $info_destino_limpio; } }
        boolean cleanNumberDueToTrunkConfig = false;
        if (trunkInfoOpt.isPresent()) {
            TrunkInfo ti = trunkInfoOpt.get();
            boolean trunkExpectsNoPbxPrefix = ti.noPbxPrefix != null && ti.noPbxPrefix;
            
            // Check specific rate details for NoPbxPrefix (PHP: $noprefijopbx from TARIFATRONCAL)
            if (cdrData.getTelephonyTypeId() != null && cdrData.getOperatorId() != null) {
                 Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetails.isPresent() && rateDetails.get().noPbxPrefix != null) {
                    trunkExpectsNoPbxPrefix = rateDetails.get().noPbxPrefix;
                }
            }
            if (!trunkExpectsNoPbxPrefix) {
                cleanNumberDueToTrunkConfig = true;
            }
        } else { // No trunk
            cleanNumberDueToTrunkConfig = true;
        }

        if (cleanNumberDueToTrunkConfig) {
            numberForTariffing = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
        }
        // If !cleanNumberDueToTrunkConfig, numberForTariffing remains cdrData.getEffectiveDestinationNumber()
        
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
        String finalNumberUsedForDestLookup = numberForTariffing;

        for (PrefixInfo prefixInfo : prefixes) {
            String numberWithoutPrefix = numberForTariffing;
            boolean stripPrefixForDestLookup = false; // PHP: $reducir

            if (trunkInfoOpt.isPresent()) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, prefixInfo.telephonyTypeId, prefixInfo.operatorId
                );
                if (rateDetails.isPresent() && rateDetails.get().noPrefix != null) {
                    stripPrefixForDestLookup = rateDetails.get().noPrefix;
                }
            } else { // Not a trunk call, prefix is generally stripped for destination lookup
                stripPrefixForDestLookup = true;
            }

            if (stripPrefixForDestLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForTariffing.startsWith(prefixInfo.getPrefixCode())) {
                numberWithoutPrefix = numberForTariffing.substring(prefixInfo.getPrefixCode().length());
            }
            finalNumberUsedForDestLookup = numberWithoutPrefix;
            
            // PHP: $arr_destino_pre = buscarDestino(...)
            Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                numberWithoutPrefix,
                prefixInfo.telephonyTypeId,
                prefixInfo.telephonyTypeMinLength != null ? prefixInfo.telephonyTypeMinLength : 0,
                commLocation.getIndicatorId(),
                prefixInfo.prefixId,
                commLocation.getIndicator().getOriginCountryId(),
                prefixInfo.bandsAssociatedCount > 0
            );

            if (destInfoOpt.isPresent()) {
                 DestinationInfo currentDestInfo = destInfoOpt.get();
                 // PHP's logic for choosing the best match is complex, involving series length and order.
                 // Simplified here: prefer non-approximate, then longer NDC.
                 if (bestDestInfo == null ||
                     (!bestDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch()) || // Current best is exact, new is approx (keep current)
                     (currentDestInfo.isApproximateMatch() == bestDestInfo.isApproximateMatch() &&
                         currentDestInfo.getNdc() != null && bestDestInfo.getNdc() != null &&
                         currentDestInfo.getNdc().length() > bestDestInfo.getNdc().length()) ||
                     (!currentDestInfo.isApproximateMatch() && bestDestInfo.isApproximateMatch()) // New is exact, current was approx
                 ) {
                    bestDestInfo = currentDestInfo;
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
            List<String> prefixesForNormalization = pbxPrefixes;
            // PHP: if ($existe_troncal['noprefijopbx']) { $prefijo_salida_pbx = ''; }
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                prefixesForNormalization = Collections.emptyList();
            }
            String normalizedNumberForLookup = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), prefixesForNormalization, true);
            
            List<PrefixInfo> normalizedPrefixes = prefixLookupService.findMatchingPrefixes(
                normalizedNumberForLookup, commLocation, false, null // false for isTrunkCall during normalization
            );
            DestinationInfo normalizedBestDestInfo = null;
            PrefixInfo normalizedBestPrefixInfo = null;
            String finalNormalizedNumberUsedForDestLookup = normalizedNumberForLookup;

            for (PrefixInfo normPrefixInfo : normalizedPrefixes) {
                String normNumberWithoutPrefix = normalizedNumberForLookup;
                 // For normalization, always strip prefix if present
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
                if (normDestInfoOpt.isPresent() && !normDestInfoOpt.get().isApproximateMatch()) {
                    normalizedBestDestInfo = normDestInfoOpt.get();
                    normalizedBestPrefixInfo = normPrefixInfo;
                    break;
                } else if (normDestInfoOpt.isPresent() && normalizedBestDestInfo == null) { // Keep first approximate if no exact found
                    normalizedBestDestInfo = normDestInfoOpt.get();
                    normalizedBestPrefixInfo = normPrefixInfo;
                }
            }

            // PHP: if (($infovalor['tipotele'] != $infovalor_pos['tipotele'] || ...) && $infovalor_pos['tipotele'] > 0 ...)
            if (normalizedBestDestInfo != null && normalizedBestPrefixInfo != null &&
                normalizedBestPrefixInfo.telephonyTypeId > 0 && normalizedBestPrefixInfo.telephonyTypeId != TelephonyTypeEnum.ERRORS.getValue()) {
                boolean useNormalized = true;
                if (bestDestInfo != null && bestPrefixInfo != null) { // If there was an initial (even if assumed) result
                    if (Objects.equals(bestPrefixInfo.telephonyTypeId, normalizedBestPrefixInfo.telephonyTypeId) &&
                        Objects.equals(bestDestInfo.getIndicatorId(), normalizedBestDestInfo.getIndicatorId())) {
                        // If normalized result is same type and indicator as an "assumed" initial, prefer initial (PHP behavior)
                        useNormalized = false;
                    }
                }
                if (useNormalized) {
                    log.debug("Using normalized tariffing result for trunk call.");
                    bestDestInfo = normalizedBestDestInfo;
                    bestPrefixInfo = normalizedBestPrefixInfo;
                    finalNumberUsedForDestLookup = finalNormalizedNumberUsedForDestLookup;
                    trunkInfoOpt = Optional.empty(); // Treat as non-trunk for tariff application
                    cdrData.setNormalizedTariffApplied(true);
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
            cdrData.setEffectiveDestinationNumber(bestDestInfo.getMatchedPhoneNumber()); // Number used for successful lookup

            // PHP: if ($tipotele_id == _TIPOTELE_LOCAL && BuscarLocalExtendida(...))
            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(bestDestInfo.getNdc(), commLocation.getIndicatorId(), bestDestInfo.getIndicatorId())) {
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
                // PHP: AsignarLocalExtendida - updates operator and prefix ID from local_ext definition
                PrefixInfo localExtPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId());
                if (localExtPrefixInfo != null) {
                    cdrData.setOperatorId(localExtPrefixInfo.getOperatorId());
                    cdrData.setOperatorName(localExtPrefixInfo.getOperatorName());
                    bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId(); // Update prefixId for subsequent tariff lookups
                }
            }

            // PHP: $infovalor = buscarValor(...)
            TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(
                bestPrefixInfo.prefixId,
                bestDestInfo.getIndicatorId(),
                commLocation.getId(),
                commLocation.getIndicatorId()
            );
            
            cdrData.setPricePerMinute(baseTariff.getRateValue());
            cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
            cdrData.setVatRate(baseTariff.getVatRate());

            // PHP: if ($existe_troncal !== false) { ... }
            if (trunkInfoOpt.isPresent() && !cdrData.isNormalizedTariffApplied()) {
                TrunkInfo ti = trunkInfoOpt.get();
                // PHP: $operador_troncal = buscarOperador_Troncal(...)
                // This logic is implicitly handled by getRateDetailsForTrunk which checks specific operator then generic
                Optional<TrunkRateDetails> rateDetailsOpt = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetailsOpt.isPresent()) {
                    TrunkRateDetails rd = rateDetailsOpt.get();
                    // PHP: Guardar_ValorInicial($infovalor, $infovalor_pre);
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
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
            
            // Ensure initial price is set if not already by trunk logic
            if (cdrData.getInitialPricePerMinute() == null || cdrData.getInitialPricePerMinute().compareTo(BigDecimal.ZERO) == 0) {
                 cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                 cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
            }

            // PHP: Obtener_ValorEspecial(...)
            Optional<SpecialRateInfo> specialRateOpt =
                specialRateValueLookupService.getApplicableSpecialRate(
                    cdrData.getDateTimeOrigination(),
                    commLocation.getIndicatorId(), // Origin indicator for special rate context
                    cdrData.getTelephonyTypeId(),
                    cdrData.getOperatorId(),
                    bestDestInfo.getBandId() // Band ID from the matched destination
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                // PHP: Guardar_ValorInicial($infovalor); (already done or will be done if initial is still zero)
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
                    cdrData.setPriceIncludesVat(false); // Discount is applied on net value
                    cdrData.setSpecialRateDiscountPercentage(discountPercentage);
                }
                cdrData.setVatRate(sr.vatRate); // VAT rate from the prefix associated with the special rate's context
                cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Special Rate)");
            }

            // PHP: Calcular_Valor_Reglas(...)
            if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
                 Optional<AppliedTrunkRuleInfo> ruleInfoOpt =
                    trunkRuleLookupService.getAppliedTrunkRule(
                        cdrData.getDestDeviceName(), // Trunk name from CDR
                        cdrData.getTelephonyTypeId(), 
                        cdrData.getIndicatorId(), // Destination indicator ID
                        commLocation.getIndicatorId() // Origin indicator ID
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
            // Rate is per minute, so divide by 60 for per-second rate
            ratePerUnit = cdrData.getPricePerMinute().divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
        } else { // Charge by minute
            billableDurationUnits = (long) Math.ceil((double) cdrData.getDurationSeconds() / 60.0);
            // PHP: if ($duracion_minuto == 0 && $duracion > 0) $duracion_minuto = 1;
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1;
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
        return totalCost.setScale(4, RoundingMode.HALF_UP); // PHP often uses 4 decimal places for currency
    }
}