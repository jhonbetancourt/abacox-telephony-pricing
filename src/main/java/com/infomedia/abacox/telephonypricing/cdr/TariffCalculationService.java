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
        log.debug("Calculating tariffs for CDR: {}, CommLocation: {}", cdrData.getRawCdrLine(), commLocation.getDirectory());

        if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing() &&
            cdrData.getTelephonyTypeId() != null &&
            cdrData.getTelephonyTypeId() > 0 &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            log.info("Call duration {}s <= min duration {}s. Setting type to NO_CONSUMPTION.", cdrData.getDurationSeconds(), appConfigService.getMinCallDurationForTariffing());
            cdrData.setBilledAmount(BigDecimal.ZERO);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
            return;
        }
        
        if (cdrData.getTelephonyTypeId() != null && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            SpecialServiceInfo ssi = cdrData.getSpecialServiceTariff();
            if (ssi != null) {
                log.debug("Applying special service tariff: {}", ssi);
                cdrData.setPricePerMinute(ssi.value);
                cdrData.setPriceIncludesVat(ssi.vatIncluded);
                cdrData.setVatRate(ssi.vatRate != null ? ssi.vatRate : BigDecimal.ZERO);
                cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
                return;
            } else {
                log.warn("TelephonyType is SPECIAL_SERVICES but no SpecialServiceInfo found for CDR: {}", cdrData.getRawCdrLine());
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.ERRORS.getValue()));
                cdrData.setBilledAmount(BigDecimal.ZERO);
                return;
            }
        }
        
        if (cdrData.isInternalCall() && telephonyTypeLookupService.isInternalIpType(cdrData.getTelephonyTypeId())) {
            log.debug("Applying internal IP call tariff for type: {}", cdrData.getTelephonyTypeId());
            TariffValue internalTariff = telephonyTypeLookupService.getInternalTariffValue(
                cdrData.getTelephonyTypeId(), commLocation.getIndicator().getOriginCountryId()
            );
            cdrData.setPricePerMinute(internalTariff.getRateValue());
            cdrData.setPriceIncludesVat(internalTariff.isIncludesVat());
            cdrData.setVatRate(internalTariff.getVatRate());
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
            return;
        }

        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
            log.debug("Trunk lookup for '{}': {}", cdrData.getDestDeviceName(), trunkInfoOpt.isPresent() ? "Found" : "Not Found");
        }

        String numberForTariffing = cdrData.getEffectiveDestinationNumber();
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        boolean cleanNumberDueToTrunkConfig = false;
        if (trunkInfoOpt.isPresent()) {
            TrunkInfo ti = trunkInfoOpt.get();
            boolean trunkExpectsNoPbxPrefix = ti.noPbxPrefix != null && ti.noPbxPrefix;
            
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
            log.debug("Trunk call. NoPbxPrefix (effective): {}. Clean number for tariffing: {}", !cleanNumberDueToTrunkConfig, cleanNumberDueToTrunkConfig);
        } else {
            cleanNumberDueToTrunkConfig = true;
            log.debug("Non-trunk call. Number will be cleaned for tariffing.");
        }

        if (cleanNumberDueToTrunkConfig) {
            String cleaned = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
            if (!Objects.equals(cleaned, numberForTariffing)) {
                log.debug("Number '{}' cleaned to '{}' for tariffing (due to trunk/no-trunk config)", numberForTariffing, cleaned);
                numberForTariffing = cleaned;
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
        log.debug("Found {} potential prefixes for number '{}'", prefixes.size(), numberForTariffing);

        DestinationInfo bestDestInfo = null;
        PrefixInfo bestPrefixInfo = null;
        String finalNumberUsedForDestLookup = numberForTariffing;

        for (PrefixInfo prefixInfo : prefixes) {
            log.debug("Evaluating prefix: {}", prefixInfo.getPrefixCode());
            String numberWithoutPrefix = numberForTariffing;
            boolean stripPrefixForDestLookup = false;

            if (trunkInfoOpt.isPresent()) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, prefixInfo.telephonyTypeId, prefixInfo.operatorId
                );
                if (rateDetails.isPresent() && rateDetails.get().noPrefix != null) {
                    stripPrefixForDestLookup = rateDetails.get().noPrefix;
                }
                log.debug("Trunk call, prefix '{}'. Strip for dest lookup: {}", prefixInfo.getPrefixCode(), stripPrefixForDestLookup);
            } else {
                stripPrefixForDestLookup = true;
                log.debug("Non-trunk call, prefix '{}'. Strip for dest lookup: {}", prefixInfo.getPrefixCode(), stripPrefixForDestLookup);
            }

            if (stripPrefixForDestLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForTariffing.startsWith(prefixInfo.getPrefixCode())) {
                numberWithoutPrefix = numberForTariffing.substring(prefixInfo.getPrefixCode().length());
                log.debug("Stripped prefix '{}'. Number for dest lookup: {}", prefixInfo.getPrefixCode(), numberWithoutPrefix);
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
            log.debug("Destination lookup for '{}' (type {}): {}", numberWithoutPrefix, prefixInfo.telephonyTypeId, destInfoOpt.isPresent() ? destInfoOpt.get() : "Not Found");

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
                    log.debug("New best destination match: {}, with prefix: {}", bestDestInfo, bestPrefixInfo.getPrefixCode());
                 }
                 if (bestDestInfo != null && !bestDestInfo.isApproximateMatch()) {
                    log.debug("Exact destination match found. Stopping prefix iteration.");
                    break;
                 }
            }
        }
        
        boolean initialResultIsInvalidOrAssumed = bestDestInfo == null ||
                                                  bestPrefixInfo == null ||
                                                  bestPrefixInfo.telephonyTypeId == null ||
                                                  bestPrefixInfo.telephonyTypeId <= 0 ||
                                                  bestPrefixInfo.telephonyTypeId == TelephonyTypeEnum.ERRORS.getValue() ||
                                                  (bestDestInfo.getDestinationDescription() != null && bestDestInfo.getDestinationDescription().contains(appConfigService.getAssumedText())) ||
                                                  (bestPrefixInfo.telephonyTypeName != null && bestPrefixInfo.telephonyTypeName.contains(appConfigService.getAssumedText()));

        if (trunkInfoOpt.isPresent() && initialResultIsInvalidOrAssumed) {
            log.warn("Trunk call destination not definitively found or was assumed. Attempting normalization for: {}", cdrData.getEffectiveDestinationNumber());
            List<String> prefixesForNormalization = pbxPrefixes;
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                prefixesForNormalization = Collections.emptyList();
            }
            String normalizedNumberForLookup = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), prefixesForNormalization, true);
            log.debug("Normalized number for lookup: {}", normalizedNumberForLookup);
            
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
                    log.debug("Exact normalized destination match found: {}", normalizedBestDestInfo);
                    break;
                } else if (normDestInfoOpt.isPresent() && normalizedBestDestInfo == null) {
                    normalizedBestDestInfo = normDestInfoOpt.get();
                    normalizedBestPrefixInfo = normPrefixInfo;
                    log.debug("Approximate normalized destination match: {}", normalizedBestDestInfo);
                }
            }

            if (normalizedBestDestInfo != null && normalizedBestPrefixInfo != null &&
                normalizedBestPrefixInfo.telephonyTypeId > 0 && normalizedBestPrefixInfo.telephonyTypeId != TelephonyTypeEnum.ERRORS.getValue()) {
                boolean useNormalized = true;
                if (bestDestInfo != null && bestPrefixInfo != null) {
                    if (Objects.equals(bestPrefixInfo.telephonyTypeId, normalizedBestPrefixInfo.telephonyTypeId) &&
                        Objects.equals(bestDestInfo.getIndicatorId(), normalizedBestDestInfo.getIndicatorId())) {
                        useNormalized = false;
                        log.debug("Normalized result is same type/indicator as initial assumed result. Preferring initial.");
                    }
                }
                if (useNormalized) {
                    log.info("Using normalized tariffing result for trunk call. Original number: {}", cdrData.getEffectiveDestinationNumber());
                    bestDestInfo = normalizedBestDestInfo;
                    bestPrefixInfo = normalizedBestPrefixInfo;
                    finalNumberUsedForDestLookup = finalNormalizedNumberUsedForDestLookup;
                    trunkInfoOpt = Optional.empty();
                    cdrData.setNormalizedTariffApplied(true);
                }
            } else {
                log.warn("Normalization did not yield a better result for trunk call.");
            }
        }


        if (bestDestInfo != null && bestPrefixInfo != null) {
            log.info("Final best match for tariffing. Destination: {}, Prefix: {}", bestDestInfo.getDestinationDescription(), bestPrefixInfo.getPrefixCode());
            cdrData.setTelephonyTypeId(bestPrefixInfo.telephonyTypeId);
            cdrData.setTelephonyTypeName(bestPrefixInfo.telephonyTypeName);
            cdrData.setOperatorId(bestPrefixInfo.operatorId);
            cdrData.setOperatorName(bestPrefixInfo.operatorName);
            cdrData.setIndicatorId(bestDestInfo.getIndicatorId());
            cdrData.setDestinationCityName(bestDestInfo.getDestinationDescription());
            cdrData.setEffectiveDestinationNumber(bestDestInfo.getMatchedPhoneNumber());

            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(bestDestInfo.getNdc(), commLocation.getIndicatorId(), bestDestInfo.getIndicatorId())) {
                log.debug("Call to {} identified as LOCAL_EXTENDED.", bestDestInfo.getDestinationDescription());
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
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
            log.debug("Base tariff for prefixId {}: {}", bestPrefixInfo.prefixId, baseTariff);
            
            cdrData.setPricePerMinute(baseTariff.getRateValue());
            cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
            cdrData.setVatRate(baseTariff.getVatRate());

            if (trunkInfoOpt.isPresent() && !cdrData.isNormalizedTariffApplied()) {
                TrunkInfo ti = trunkInfoOpt.get();
                log.debug("Applying trunk-specific rates for trunk: {}", ti.description);
                Optional<TrunkRateDetails> rateDetailsOpt = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetailsOpt.isPresent()) {
                    TrunkRateDetails rd = rateDetailsOpt.get();
                    log.debug("Found specific rate details for trunk: {}", rd);
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                    cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

                    cdrData.setPricePerMinute(rd.rateValue);
                    cdrData.setPriceIncludesVat(rd.includesVat);
                    cdrData.setChargeBySecond(rd.seconds != null && rd.seconds > 0);
                    cdrData.setVatRate(telephonyTypeLookupService.getVatForPrefix(rd.telephonyTypeId, rd.operatorId, commLocation.getIndicator().getOriginCountryId()));
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Trunk: " + ti.description + ")");

                    if (ti.isCelufijo() && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue()) {
                        log.debug("Trunk is celufijo and call is cellular. Changing type to CELUFIJO.");
                        cdrData.setTelephonyTypeId(TelephonyTypeEnum.CELUFIJO.getValue());
                        cdrData.setTelephonyTypeName("Celufijo (Trunk: " + ti.description + ")");
                    }
                } else {
                    log.debug("No specific rate details found for trunk {} with type {} and operator {}. Using base prefix tariff.",
                              ti.description, cdrData.getTelephonyTypeId(), cdrData.getOperatorId());
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
                log.info("Applying special rate: {}", sr);
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
                    cdrData.setSpecialRateDiscountPercentage(discountPercentage);
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
                    log.info("Applying trunk rule: {}", rule);
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
            log.warn("Could not determine destination or tariff for: {} (original number for tariffing: {}). Marking as ERROR.", finalNumberUsedForDestLookup, numberForTariffing);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.ERRORS.getValue()));
            cdrData.setBilledAmount(BigDecimal.ZERO);
        }
        log.info("Final tariff calculation for CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                 cdrData.getRawCdrLine(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
    }

    /**
     * PHP equivalent: Calcular_Valor
     */
    private BigDecimal calculateFinalBilledAmount(CdrData cdrData) {
        if (cdrData.getPricePerMinute() == null || cdrData.getDurationSeconds() == null || cdrData.getDurationSeconds() < 0) {
            log.debug("Cannot calculate billed amount: PricePerMinute or DurationSeconds is null/invalid.");
            return BigDecimal.ZERO;
        }

        long billableDurationUnits;
        BigDecimal ratePerUnit;

        if (cdrData.isChargeBySecond()) {
            billableDurationUnits = cdrData.getDurationSeconds();
            ratePerUnit = cdrData.getPricePerMinute().divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
            log.debug("Charging by second. Duration units: {}, Rate per second: {}", billableDurationUnits, ratePerUnit);
        } else {
            billableDurationUnits = (long) Math.ceil((double) cdrData.getDurationSeconds() / 60.0);
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1;
            ratePerUnit = cdrData.getPricePerMinute();
            log.debug("Charging by minute. Duration units (minutes): {}, Rate per minute: {}", billableDurationUnits, ratePerUnit);
        }
        
        if (billableDurationUnits == 0) {
            log.debug("Billable duration is 0. Billed amount is ZERO.");
            return BigDecimal.ZERO;
        }

        BigDecimal totalCost = ratePerUnit.multiply(BigDecimal.valueOf(billableDurationUnits));
        log.debug("Cost before VAT: {}", totalCost);

        if (!cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
            log.debug("Applied VAT ({}%). Cost after VAT: {}", cdrData.getVatRate(), totalCost);
        }
        
        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }
}