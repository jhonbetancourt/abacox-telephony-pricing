// File: com/infomedia/abacox/telephonypricing/cdr/TariffCalculationService.java
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
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            cdrData.setBilledAmount(BigDecimal.ZERO);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
            return;
        }
        
        if (cdrData.getTelephonyTypeId() != null && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            SpecialServiceInfo ssi = (SpecialServiceInfo) cdrData.getAdditionalData().get("specialServiceTariff");
            if (ssi != null) {
                cdrData.setPricePerMinute(ssi.value);
                cdrData.setPriceIncludesVat(ssi.vatIncluded);
                cdrData.setVatRate(ssi.vatRate != null ? ssi.vatRate : BigDecimal.ZERO);
                cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
                return;
            }
        }
        
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
            // If trunk *does not* expect "no pbx prefix" (i.e., it expects the prefix to be there),
            // then we should clean the number as if the prefix was dialed.
            // If it *does* expect "no pbx prefix", then the number should already be clean or not have it.
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
                 if (bestDestInfo == null ||
                     (!bestDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch()) ||
                     (currentDestInfo.isApproximateMatch() == bestDestInfo.isApproximateMatch() &&
                         currentDestInfo.getNdc() != null && bestDestInfo.getNdc() != null &&
                         currentDestInfo.getNdc().length() > bestDestInfo.getNdc().length()) ||
                     (!currentDestInfo.isApproximateMatch() && bestDestInfo.isApproximateMatch())
                 ) {
                    bestDestInfo = currentDestInfo;
                    bestPrefixInfo = prefixInfo;
                 }
                 if (bestDestInfo != null && !bestDestInfo.isApproximateMatch()) break;
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
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                prefixesForNormalization = Collections.emptyList();
            }
            String normalizedNumberForLookup = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), prefixesForNormalization, true);
            
            List<PrefixInfo> normalizedPrefixes = prefixLookupService.findMatchingPrefixes(
                normalizedNumberForLookup, commLocation, false, null
            );
            DestinationInfo normalizedBestDestInfo = null;
            PrefixInfo normalizedBestPrefixInfo = null;
            String finalNormalizedNumberUsedForDestLookup = normalizedNumberForLookup;

            for (PrefixInfo normPrefixInfo : normalizedPrefixes) {
                String normNumberWithoutPrefix = normalizedNumberForLookup;
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
                } else if (normDestInfoOpt.isPresent() && normalizedBestDestInfo == null) {
                    normalizedBestDestInfo = normDestInfoOpt.get();
                    normalizedBestPrefixInfo = normPrefixInfo;
                }
            }

            if (normalizedBestDestInfo != null && normalizedBestPrefixInfo != null) {
                boolean useNormalized = true;
                if (bestDestInfo != null && bestPrefixInfo != null) {
                    if (Objects.equals(bestPrefixInfo.telephonyTypeId, normalizedBestPrefixInfo.telephonyTypeId) &&
                        Objects.equals(bestDestInfo.getIndicatorId(), normalizedBestDestInfo.getIndicatorId())) {
                        useNormalized = false;
                    }
                }
                if (useNormalized && normalizedBestPrefixInfo.telephonyTypeId > 0 && normalizedBestPrefixInfo.telephonyTypeId != TelephonyTypeEnum.ERRORS.getValue()) {
                    log.debug("Using normalized tariffing result for trunk call.");
                    bestDestInfo = normalizedBestDestInfo;
                    bestPrefixInfo = normalizedBestPrefixInfo;
                    finalNumberUsedForDestLookup = finalNormalizedNumberUsedForDestLookup;
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
            cdrData.setEffectiveDestinationNumber(bestDestInfo.getMatchedPhoneNumber());

            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(bestDestInfo.getNdc(), commLocation.getIndicatorId(), bestDestInfo.getIndicatorId())) {
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
                PrefixInfo localExtPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId());
                if (localExtPrefixInfo != null) {
                    cdrData.setOperatorId(localExtPrefixInfo.getOperatorId());
                    cdrData.setOperatorName(localExtPrefixInfo.getOperatorName());
                    bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId();
                }
            }

            TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(
                bestPrefixInfo.prefixId,
                bestDestInfo.getIndicatorId(),
                commLocation.getId(),
                commLocation.getIndicatorId()
            );
            
            cdrData.setPricePerMinute(baseTariff.getRateValue());
            cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
            cdrData.setVatRate(baseTariff.getVatRate());

            if (trunkInfoOpt.isPresent() && !Boolean.TRUE.equals(cdrData.getAdditionalData().get("normalizedTariffApplied"))) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetailsOpt = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetailsOpt.isPresent()) {
                    TrunkRateDetails rd = rateDetailsOpt.get();
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                    cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

                    cdrData.setPricePerMinute(rd.rateValue);
                    cdrData.setPriceIncludesVat(rd.includesVat);
                    cdrData.setChargeBySecond(rd.seconds != null && rd.seconds > 0);
                    cdrData.setVatRate(telephonyTypeLookupService.getVatForPrefix(rd.telephonyTypeId, rd.operatorId, commLocation.getIndicator().getOriginCountryId()));
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Trunk: " + ti.description + ")");

                    if (ti.isCelufijo() && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue()) {
                        cdrData.setTelephonyTypeId(TelephonyTypeEnum.CELUFIJO.getValue());
                        cdrData.setTelephonyTypeName("Celufijo (Trunk: " + ti.description + ")");
                    }
                }
            }
            
            if (cdrData.getInitialPricePerMinute() == null || cdrData.getInitialPricePerMinute().compareTo(BigDecimal.ZERO) == 0) {
                 cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                 cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
            }

            Optional<SpecialRateInfo> specialRateOpt =
                specialRateValueLookupService.getApplicableSpecialRate(
                    cdrData.getDateTimeOrigination(),
                    commLocation.getIndicatorId(),
                    cdrData.getTelephonyTypeId(),
                    cdrData.getOperatorId(),
                    bestDestInfo.getBandId()
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                if (cdrData.getInitialPricePerMinute() == null || cdrData.getInitialPricePerMinute().compareTo(BigDecimal.ZERO) == 0) {
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                    cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
                }

                if (sr.valueType == 0) {
                    cdrData.setPricePerMinute(sr.rateValue);
                    cdrData.setPriceIncludesVat(sr.includesVat);
                } else {
                    BigDecimal currentRateNoVat = cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0 ?
                        cdrData.getPricePerMinute().divide(BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)), 8, RoundingMode.HALF_UP) :
                        cdrData.getPricePerMinute();
                    
                    BigDecimal discountPercentage = sr.rateValue;
                    BigDecimal discountFactor = BigDecimal.ONE.subtract(discountPercentage.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
                    cdrData.setPricePerMinute(currentRateNoVat.multiply(discountFactor));
                    cdrData.setPriceIncludesVat(false);
                    cdrData.getAdditionalData().put("specialRateDiscountPercentage", discountPercentage);
                }
                cdrData.setVatRate(sr.vatRate);
                cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Special Rate)");
            }

            if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
                 Optional<AppliedTrunkRuleInfo> ruleInfoOpt =
                    trunkRuleLookupService.getAppliedTrunkRule(
                        cdrData.getDestDeviceName(),
                        cdrData.getTelephonyTypeId(), 
                        cdrData.getIndicatorId(),
                        commLocation.getIndicatorId()
                    );
                if (ruleInfoOpt.isPresent()) {
                    AppliedTrunkRuleInfo rule = ruleInfoOpt.get();
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

        if (cdrData.isChargeBySecond()) {
            billableDurationUnits = cdrData.getDurationSeconds();
            ratePerUnit = cdrData.getPricePerMinute().divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
        } else {
            billableDurationUnits = (long) Math.ceil((double) cdrData.getDurationSeconds() / 60.0);
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1;
            ratePerUnit = cdrData.getPricePerMinute();
        }
        
        if (billableDurationUnits == 0) return BigDecimal.ZERO;

        BigDecimal totalCost = ratePerUnit.multiply(BigDecimal.valueOf(billableDurationUnits));

        if (!cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }
        
        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }
}