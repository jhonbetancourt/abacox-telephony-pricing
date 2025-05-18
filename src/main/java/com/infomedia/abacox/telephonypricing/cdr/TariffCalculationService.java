package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;


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

    public void calculateTariffs(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing() &&
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

        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
        }

        String numberForTariffing = cdrData.getEffectiveDestinationNumber();
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        // PHP's evaluarDestino: $info_destino_limpio = limpiar_numero($info_destino, $prefijo_salida_pbx, true);
        // if ($existe_troncal === false) { $telefono = $info_destino_limpio; }
        // This means if it's NOT a trunk call, the number is cleaned of PBX prefixes.
        // If it IS a trunk call, the raw (or PBX-rule-transformed) number is used for prefix lookup,
        // UNLESS the trunk itself has NOPREFIJOPBX, then it's cleaned.
        if (trunkInfoOpt.isEmpty()) {
            numberForTariffing = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
        } else { // It is a trunk call
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                numberForTariffing = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
            } else {
                // Use number as is (already effectiveDestinationNumber) for prefix lookup if trunk and not NoPbxPrefix
                numberForTariffing = cdrData.getEffectiveDestinationNumber();
            }
        }
        
        List<Long> trunkTelephonyTypeIds = null;
        if (trunkInfoOpt.isPresent()) {
            trunkTelephonyTypeIds = trunkInfoOpt.get().getAllowedTelephonyTypeIds();
        }

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
            String numberWithoutPrefix = numberForTariffing; // This is after _esCelular_fijo in prefixLookup
            boolean stripPrefixForDestLookup = true; // Default

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
            finalNumberUsedForDestLookup = numberWithoutPrefix;
            
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
                if (bestDestInfo == null || !bestDestInfo.isApproximateMatch() || (destInfoOpt.get().isApproximateMatch() == bestDestInfo.isApproximateMatch() && destInfoOpt.get().getNdc().length() > bestDestInfo.getNdc().length())) {
                     if (!destInfoOpt.get().isApproximateMatch()) { // Exact match found
                        bestDestInfo = destInfoOpt.get();
                        bestPrefixInfo = prefixInfo;
                        break; 
                     } else if (bestDestInfo == null || bestDestInfo.isApproximateMatch()) { // Current best is null or also approx
                        bestDestInfo = destInfoOpt.get();
                        bestPrefixInfo = prefixInfo;
                        // Don't break on approximate, keep looking for exact
                     }
                }
            }
        }
        
        // PHP Normalization: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor))
        boolean initialResultIsInvalidOrAssumed = bestDestInfo == null ||
                                                  bestPrefixInfo == null ||
                                                  bestPrefixInfo.telephonyTypeId == null ||
                                                  bestPrefixInfo.telephonyTypeId <= 0 ||
                                                  bestPrefixInfo.telephonyTypeId == TelephonyTypeEnum.ERRORS.getValue() ||
                                                  (bestDestInfo.getDestinationDescription() != null && bestDestInfo.getDestinationDescription().contains(appConfigService.getAssumedText())) ||
                                                  (bestPrefixInfo.telephonyTypeName != null && bestPrefixInfo.telephonyTypeName.contains(appConfigService.getAssumedText()));

        if (trunkInfoOpt.isPresent() && initialResultIsInvalidOrAssumed) {
            log.debug("Trunk call destination not definitively found or was assumed. Attempting normalization for: {}", cdrData.getEffectiveDestinationNumber());
            // PHP: $prefijo_salida_pbx = ''; if ($existe_troncal['noprefijopbx']) { $prefijo_salida_pbx = ''; }
            // For normalization, PHP effectively re-processes as non-trunk, so PBX prefixes are stripped if not NoPbxPrefix.
            // However, the example shows it calls evaluarDestino_pos(false, ...), which means it will use the default PBX prefix handling for non-trunk.
            String normalizedNumberForLookup = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
            
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
                if (normDestInfoOpt.isPresent() && !normDestInfoOpt.get().isApproximateMatch()) { // Prefer exact normalized match
                    normalizedBestDestInfo = normDestInfoOpt.get();
                    normalizedBestPrefixInfo = normPrefixInfo;
                    break;
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
            cdrData.setEffectiveDestinationNumber(bestDestInfo.getMatchedPhoneNumber()); // Number as matched by series

            // PHP: Valida local extendido
            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(bestDestInfo.getNdc(), commLocation.getIndicatorId(), bestDestInfo.getIndicatorId())) {
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
                // PHP: AsignarLocalExtendida also updates operator and prefix if local_ext has specific ones
                PrefixInfo localExtPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId());
                if (localExtPrefixInfo != null) {
                    cdrData.setOperatorId(localExtPrefixInfo.getOperatorId());
                    cdrData.setOperatorName(localExtPrefixInfo.getOperatorName());
                    bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId(); // Update prefixId for tariff lookup
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

            if (trunkInfoOpt.isPresent()) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetailsOpt = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetailsOpt.isPresent()) {
                    TrunkRateDetails rd = rateDetailsOpt.get();
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
            
            cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
            cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

            Optional<SpecialRateInfo> specialRateOpt =
                specialRateValueLookupService.getApplicableSpecialRate(
                    cdrData.getDateTimeOrigination(),
                    commLocation.getIndicatorId(),
                    cdrData.getTelephonyTypeId(),
                    cdrData.getOperatorId(),
                    bestDestInfo.getBandId() // Pass bandId from DestinationInfo if available
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                if (sr.valueType == 0) { // Absolute value
                    cdrData.setPricePerMinute(sr.rateValue);
                    cdrData.setPriceIncludesVat(sr.includesVat);
                } else { // Percentage adjustment
                    BigDecimal currentRateNoVat = cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0 ?
                        cdrData.getPricePerMinute().divide(BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)), 8, RoundingMode.HALF_UP) :
                        cdrData.getPricePerMinute();
                    
                    BigDecimal discountPercentage = sr.rateValue;
                    BigDecimal discountFactor = BigDecimal.ONE.subtract(discountPercentage.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
                    cdrData.setPricePerMinute(currentRateNoVat.multiply(discountFactor));
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