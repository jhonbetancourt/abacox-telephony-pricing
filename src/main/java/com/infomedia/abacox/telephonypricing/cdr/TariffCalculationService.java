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

        // PHP: $prefijo_salida_pbx logic in evaluarDestino
        List<String> pbxPrefixesForCleaning = Collections.emptyList();
        boolean cleanNumberForTariffing = true; // Default for non-trunk or trunk that expects cleaned number

        if (trunkInfoOpt.isPresent()) {
            TrunkInfo ti = trunkInfoOpt.get();
            boolean trunkExpectsNoPbxPrefix = ti.noPbxPrefix != null && ti.noPbxPrefix;
            // Check specific rate details if available
            if (cdrData.getTelephonyTypeId() != null && cdrData.getOperatorId() != null) {
                 Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetails.isPresent() && rateDetails.get().noPbxPrefix != null) {
                    trunkExpectsNoPbxPrefix = rateDetails.get().noPbxPrefix;
                }
            }
            if (!trunkExpectsNoPbxPrefix) { // Trunk expects number *with* PBX prefix
                cleanNumberForTariffing = false;
            } else { // Trunk expects number *without* PBX prefix
                if (commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty()) {
                    pbxPrefixesForCleaning = Arrays.asList(commLocation.getPbxPrefix().split(","));
                }
            }
        } else { // Not a trunk call, always clean with PBX prefixes if they exist
            if (commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty()) {
                pbxPrefixesForCleaning = Arrays.asList(commLocation.getPbxPrefix().split(","));
            }
        }
        log.debug("Clean number for tariffing: {}, PBX prefixes for cleaning: {}", cleanNumberForTariffing, pbxPrefixesForCleaning);

        String numberForTariffing = cdrData.getEffectiveDestinationNumber();
        if (cleanNumberForTariffing) {
            String cleaned = CdrUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixesForCleaning, true);
            if (!Objects.equals(cleaned, numberForTariffing)) {
                log.debug("Number '{}' cleaned to '{}' for tariffing", numberForTariffing, cleaned);
                numberForTariffing = cleaned;
            }
        }
        
        TariffingAttemptResult initialAttempt = attemptTariffing(numberForTariffing, cdrData, commLocation, trunkInfoOpt, false);

        boolean initialResultIsInvalidOrAssumed = initialAttempt.getBestDestInfo() == null ||
                                                  initialAttempt.getBestPrefixInfo() == null ||
                                                  initialAttempt.getBestPrefixInfo().getTelephonyTypeId() == null ||
                                                  initialAttempt.getBestPrefixInfo().getTelephonyTypeId() <= 0 ||
                                                  initialAttempt.getBestPrefixInfo().getTelephonyTypeId() == TelephonyTypeEnum.ERRORS.getValue() ||
                                                  (initialAttempt.getBestDestInfo().getDestinationDescription() != null && initialAttempt.getBestDestInfo().getDestinationDescription().contains(appConfigService.getAssumedText())) ||
                                                  (initialAttempt.getBestPrefixInfo().getTelephonyTypeName() != null && initialAttempt.getBestPrefixInfo().getTelephonyTypeName().contains(appConfigService.getAssumedText()));

        if (trunkInfoOpt.isPresent() && initialResultIsInvalidOrAssumed && !cdrData.isNormalizedTariffApplied()) {
            log.warn("Trunk call destination not definitively found or was assumed. Attempting normalization for: {}", cdrData.getEffectiveDestinationNumber());
            
            // PHP: $prefijo_salida_pbx = ''; // Retira prefijo si existe pues debe ignorarse (for normalization)
            List<String> pbxPrefixesForNormalization = Collections.emptyList(); // No PBX prefix for normalization
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                // If trunk was already expecting no PBX prefix, use the already cleaned number.
                // If trunk was expecting PBX prefix, we need to clean it now for normalization.
                if (commLocation.getPbxPrefix() != null && !commLocation.getPbxPrefix().isEmpty()) {
                     pbxPrefixesForNormalization = Arrays.asList(commLocation.getPbxPrefix().split(","));
                }
            }

            String normalizedNumberForLookup = CdrUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixesForNormalization, true);
            log.debug("Normalized number for lookup: {}", normalizedNumberForLookup);

            TariffingAttemptResult normalizedAttempt = attemptTariffing(normalizedNumberForLookup, cdrData, commLocation, Optional.empty(), true);

            if (normalizedAttempt.getBestDestInfo() != null && normalizedAttempt.getBestPrefixInfo() != null &&
                normalizedAttempt.getBestPrefixInfo().getTelephonyTypeId() > 0 &&
                normalizedAttempt.getBestPrefixInfo().getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                
                boolean useNormalized = true;
                if (initialAttempt.getBestDestInfo() != null && initialAttempt.getBestPrefixInfo() != null) {
                    if (Objects.equals(initialAttempt.getBestPrefixInfo().getTelephonyTypeId(), normalizedAttempt.getBestPrefixInfo().getTelephonyTypeId()) &&
                        Objects.equals(initialAttempt.getBestDestInfo().getIndicatorId(), normalizedAttempt.getBestDestInfo().getIndicatorId())) {
                        useNormalized = false;
                        log.debug("Normalized result is same type/indicator as initial assumed result. Preferring initial.");
                    }
                }
                if (useNormalized) {
                    log.info("Using normalized tariffing result for trunk call. Original number: {}", cdrData.getEffectiveDestinationNumber());
                    applyTariffingResult(normalizedAttempt, cdrData, commLocation, Optional.empty()); // Apply as if non-trunk
                    cdrData.setNormalizedTariffApplied(true);
                } else {
                    applyTariffingResult(initialAttempt, cdrData, commLocation, trunkInfoOpt); // Re-apply initial if better
                }
            } else {
                log.warn("Normalization did not yield a valid result. Using initial attempt if available.");
                applyTariffingResult(initialAttempt, cdrData, commLocation, trunkInfoOpt);
            }
        } else {
            applyTariffingResult(initialAttempt, cdrData, commLocation, trunkInfoOpt);
        }

        // Final calculations after all potential tariff sources
        if (cdrData.getTelephonyTypeId() != null &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.NO_CONSUMPTION.getValue() &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) { // Special services already billed

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
                    initialAttempt.getBestDestInfo() != null ? initialAttempt.getBestDestInfo().getBandId() : null // Use band from initial attempt
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                log.info("Applying special rate: {}", sr);
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
                        cdrData.setOperatorName(rule.newOperatorName != null ? rule.newOperatorName : prefixLookupService.findOperatorNameById(rule.newOperatorId));
                    }
                    cdrData.setVatRate(rule.vatRate);
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Rule Applied)");
                }
            }
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        } else if (cdrData.getBilledAmount() == null) { // Ensure billed amount is set if not calculated
            cdrData.setBilledAmount(BigDecimal.ZERO);
        }


        log.info("Final tariff calculation for CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                 cdrData.getRawCdrLine(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
    }

    private TariffingAttemptResult attemptTariffing(String numberForLookup, CdrData cdrData,
                                                    CommunicationLocation commLocation,
                                                    Optional<TrunkInfo> trunkInfoOpt,
                                                    boolean isNormalizationAttempt) {
        log.debug("Attempting tariffing for number: {}, isNormalization: {}", numberForLookup, isNormalizationAttempt);
        List<Long> trunkTelephonyTypeIds = null;
        if (trunkInfoOpt.isPresent() && !isNormalizationAttempt) { // For normalization, we treat as non-trunk
            trunkTelephonyTypeIds = trunkInfoOpt.get().getAllowedTelephonyTypeIds();
        }

        List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
            numberForLookup,
            commLocation,
            trunkInfoOpt.isPresent() && !isNormalizationAttempt, // Treat as non-trunk for normalization prefix lookup
            trunkTelephonyTypeIds
        );

        DestinationInfo bestDestInfo = null;
        PrefixInfo bestPrefixInfo = null;

        for (PrefixInfo prefixInfo : prefixes) {
            String numberWithoutPrefix = numberForLookup;
            boolean stripOperatorPrefixForDestLookup = false;
            boolean actualOperatorPrefixStrippedThisIteration = false;
            String operatorPrefixToPassToFindDest = prefixInfo.getPrefixCode();

            if (trunkInfoOpt.isPresent() && !isNormalizationAttempt) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, prefixInfo.telephonyTypeId, prefixInfo.operatorId
                );
                if (rateDetails.isPresent() && rateDetails.get().noPrefix != null) {
                    stripOperatorPrefixForDestLookup = rateDetails.get().noPrefix;
                }
            } else { // Not a trunk call, or it's a normalization attempt
                stripOperatorPrefixForDestLookup = true;
            }

            if (stripOperatorPrefixForDestLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForLookup.startsWith(prefixInfo.getPrefixCode())) {
                numberWithoutPrefix = numberForLookup.substring(prefixInfo.getPrefixCode().length());
                actualOperatorPrefixStrippedThisIteration = true;
                operatorPrefixToPassToFindDest = null;
            }
            
            Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                numberWithoutPrefix, prefixInfo.telephonyTypeId,
                prefixInfo.telephonyTypeMinLength != null ? prefixInfo.telephonyTypeMinLength : 0,
                commLocation.getIndicatorId(), prefixInfo.prefixId,
                commLocation.getIndicator().getOriginCountryId(), prefixInfo.bandsAssociatedCount > 0,
                actualOperatorPrefixStrippedThisIteration,
                operatorPrefixToPassToFindDest
            );

            if (destInfoOpt.isPresent()) {
                DestinationInfo currentDestInfo = destInfoOpt.get();
                 if (bestDestInfo == null ||
                     (!bestDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch()) ||
                     (currentDestInfo.isApproximateMatch() == bestDestInfo.isApproximateMatch() &&
                         currentDestInfo.getSeriesRangeSize() < bestDestInfo.getSeriesRangeSize()) ||
                     (!currentDestInfo.isApproximateMatch() && bestDestInfo.isApproximateMatch())
                 ) {
                    bestDestInfo = currentDestInfo;
                    bestPrefixInfo = prefixInfo;
                 }
                 if (bestDestInfo != null && !bestDestInfo.isApproximateMatch()) {
                    break;
                 }
            }
        }
        return new TariffingAttemptResult(bestDestInfo, bestPrefixInfo);
    }

    private void applyTariffingResult(TariffingAttemptResult attempt, CdrData cdrData,
                                      CommunicationLocation commLocation, Optional<TrunkInfo> trunkInfoOpt) {
        if (attempt.getBestDestInfo() == null || attempt.getBestPrefixInfo() == null) {
            log.warn("Tariffing attempt yielded no valid destination/prefix. Marking CDR as ERROR. Original number: {}", cdrData.getEffectiveDestinationNumber());
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.ERRORS.getValue()));
            cdrData.setBilledAmount(BigDecimal.ZERO);
            return;
        }

        DestinationInfo bestDestInfo = attempt.getBestDestInfo();
        PrefixInfo bestPrefixInfo = attempt.getBestPrefixInfo();

        cdrData.setTelephonyTypeId(bestPrefixInfo.telephonyTypeId);
        cdrData.setTelephonyTypeName(bestPrefixInfo.telephonyTypeName);

        if (bestDestInfo.getOperatorId() != null && bestDestInfo.getOperatorId() != 0L) {
            cdrData.setOperatorId(bestDestInfo.getOperatorId());
            String operatorNameFromIndicator = prefixLookupService.findOperatorNameById(bestDestInfo.getOperatorId());
            cdrData.setOperatorName(operatorNameFromIndicator != null ? operatorNameFromIndicator : bestPrefixInfo.operatorName);
        } else {
            cdrData.setOperatorId(bestPrefixInfo.operatorId);
            cdrData.setOperatorName(bestPrefixInfo.operatorName);
        }

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
                bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId(); // Update for tariff lookup
            }
        }

        TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(
            bestPrefixInfo.prefixId, bestDestInfo.getIndicatorId(),
            commLocation.getId(), commLocation.getIndicatorId()
        );
        
        cdrData.setPricePerMinute(baseTariff.getRateValue());
        cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
        cdrData.setVatRate(baseTariff.getVatRate());

        if (trunkInfoOpt.isPresent() && !cdrData.isNormalizedTariffApplied()) { // Don't apply trunk rates if normalization was used
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
    }


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
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1; // Min 1 minute if any duration
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
