package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class ExternalCallPricer {

    private final NumberRoutingLookupService numberRoutingLookupService;
    private final PricingRuleLookupService pricingRuleLookupService;
    private final CoreLookupService coreLookupService;
    private final CdrConfigService cdrConfigService;
    private final CallRecordUpdater callRecordUpdater;

    public void priceExternalCall(CallRecord callRecord, RawCiscoCdrData rawData, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        boolean usesTrunk = callRecord.getTrunk() != null && !callRecord.getTrunk().isEmpty();

        String numberForPrefixLookup = usesTrunk ? dialedNumber : CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);

        List<Prefix> prefixes = numberRoutingLookupService.findMatchingPrefixes(numberForPrefixLookup, originCountryId, usesTrunk);
        EvaluatedDestinationDto bestEval = null;

        for (Prefix prefix : prefixes) {
            EvaluatedDestinationDto currentEval = evaluateDestinationWithPrefix(
                    numberForPrefixLookup,
                    dialedNumber,
                    prefix, commLocation, originCountryId, usesTrunk, pbxPrefixes, callRecord.getServiceDate()
            );
            if (currentEval != null && (bestEval == null || isBetterEvaluation(currentEval, bestEval))) {
                bestEval = currentEval;
                if (bestEval.getTelephonyType() != null &&
                        !Objects.equals(bestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) &&
                        !bestEval.isAssumed()) {
                    break;
                }
            }
        }

        if (usesTrunk && (bestEval == null || bestEval.getTelephonyType() == null || Objects.equals(bestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) || bestEval.isAssumed())) {
            log.debug("Trunk call pricing was not definitive for {}. Attempting non-trunk 'normalization'.", dialedNumber);
            String normalizedNumberLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, true);
            List<Prefix> normalizedPrefixes = numberRoutingLookupService.findMatchingPrefixes(normalizedNumberLookup, originCountryId, false);

            if (!normalizedPrefixes.isEmpty()) {
                EvaluatedDestinationDto normalizedBestEval = null;
                for (Prefix normPrefix : normalizedPrefixes) {
                    EvaluatedDestinationDto currentNormEval = evaluateDestinationWithPrefix(
                            normalizedNumberLookup, dialedNumber, normPrefix, commLocation, originCountryId, false, pbxPrefixes, callRecord.getServiceDate()
                    );
                    if (currentNormEval != null && (normalizedBestEval == null || isBetterEvaluation(currentNormEval, normalizedBestEval))) {
                        normalizedBestEval = currentNormEval;
                        if (normalizedBestEval.getTelephonyType() != null &&
                                !Objects.equals(normalizedBestEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES) &&
                                !normalizedBestEval.isAssumed()) {
                            break;
                        }
                    }
                }
                if (normalizedBestEval != null && (bestEval == null || isBetterEvaluation(normalizedBestEval, bestEval))) {
                    log.info("Normalized pricing provided a better result for {}. Original trunk eval: {}", dialedNumber, bestEval);
                    bestEval = normalizedBestEval;
                    bestEval.setFromTrunk(true);
                    bestEval.setDestinationDescription(bestEval.getDestinationDescription() + " (Normalized)");
                }
            }
        }

        if (bestEval == null) {
            log.warn("No viable prefix/indicator combination found for {}. Falling back to error.", dialedNumber);
            callRecordUpdater.setDefaultErrorTelephonyType(callRecord);
            return;
        }

        applyEvaluationToCallRecord(callRecord, bestEval, commLocation, originCountryId);
    }

    private boolean isBetterEvaluation(EvaluatedDestinationDto newEval, EvaluatedDestinationDto oldEval) {
        if (oldEval == null) return true;
        if (newEval.getTelephonyType() == null) return false;
        if (oldEval.getTelephonyType() == null) return true;

        boolean newIsError = Objects.equals(newEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES);
        boolean oldIsError = Objects.equals(oldEval.getTelephonyType().getId(), TelephonyTypeConstants.ERRORES);

        if (oldIsError && !newIsError) return true;
        if (!oldIsError && newIsError) return false;

        if (oldEval.isAssumed() && !newEval.isAssumed()) return true;
        if (!oldEval.isAssumed() && newEval.isAssumed()) return false;

        return false;
    }

    private EvaluatedDestinationDto evaluateDestinationWithPrefix(
            String numberUsedForPrefixMatch, String originalDialedNumber, Prefix prefix,
            CommunicationLocation commLocation, Long originCountryId, boolean isTrunkCallContext,
            List<String> pbxPrefixes, LocalDateTime callDateTime) {

        String currentNumberToProcess = numberUsedForPrefixMatch;
        boolean currentVatIncluded = prefix.isVatIncluded();
        BigDecimal currentVatRate = prefix.getVatValue() != null ? prefix.getVatValue() : BigDecimal.ZERO;
        BigDecimal currentCallValue = prefix.getBaseValue() != null ? prefix.getBaseValue() : BigDecimal.ZERO;
        Long currentTelephonyTypeId = prefix.getTelephoneTypeId();

        TelephonyType currentTelephonyType = prefix.getTelephonyType();
        if (currentTelephonyType == null && currentTelephonyTypeId != null) {
            currentTelephonyType = coreLookupService.findTelephonyTypeById(currentTelephonyTypeId).orElse(null);
        }

        Operator currentOperator = prefix.getOperator();
        if (currentOperator == null && prefix.getOperatorId() != null) {
            currentOperator = coreLookupService.findOperatorById(prefix.getOperatorId()).orElse(null);
        }
        Long currentOperatorId = prefix.getOperatorId();

        String currentDestinationDescription = currentTelephonyType != null ? currentTelephonyType.getName() : "Unknown";
        Indicator currentIndicator = null;
        Long bandIdForSpecialRate = null;
        String bandName = null;
        Integer currentBillingUnit = 60;

        String numberAfterPrefix = currentNumberToProcess;
        if (prefix.getCode() != null && !prefix.getCode().isEmpty() && currentNumberToProcess.startsWith(prefix.getCode())) {
            numberAfterPrefix = currentNumberToProcess.substring(prefix.getCode().length());
        }

        String numberForIndicatorLookup = numberAfterPrefix;
        Long typeForIndicatorLookup = currentTelephonyTypeId;

        if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
            typeForIndicatorLookup = TelephonyTypeConstants.NACIONAL;
            String localAreaCode = numberRoutingLookupService.findLocalAreaCodeForIndicator(commLocation.getIndicatorId());
            numberForIndicatorLookup = localAreaCode + numberAfterPrefix;
        }

        List<SeriesMatchDto> seriesMatches = numberRoutingLookupService.findIndicatorsByNumberAndType(
                numberForIndicatorLookup, typeForIndicatorLookup, originCountryId, commLocation, prefix.getId(), prefix.isBandOk()
        );

        boolean assumed = false;
        if (!seriesMatches.isEmpty()) {
            SeriesMatchDto bestMatch = seriesMatches.get(0);
            currentIndicator = bestMatch.getIndicator();
            currentDestinationDescription = bestMatch.getDestinationDescription();
            assumed = bestMatch.isApproximate();

            if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL) &&
                    commLocation.getIndicator() != null && currentIndicator != null &&
                    !Objects.equals(commLocation.getIndicatorId(), currentIndicator.getId()) &&
                    numberRoutingLookupService.isLocalExtended(commLocation.getIndicator(), currentIndicator)) {

                currentTelephonyTypeId = TelephonyTypeConstants.LOCAL_EXTENDIDA;
                currentTelephonyType = coreLookupService.findTelephonyTypeById(TelephonyTypeConstants.LOCAL_EXTENDIDA).orElse(currentTelephonyType);
                currentDestinationDescription = (currentTelephonyType != null ? currentTelephonyType.getName() : "Local Ext.") + " (" + bestMatch.getDestinationDescription() + ")";
            }
        } else if (Objects.equals(currentTelephonyTypeId, TelephonyTypeConstants.LOCAL)) {
            currentIndicator = commLocation.getIndicator();
            if (currentIndicator != null) {
                currentDestinationDescription = numberRoutingLookupService.buildDestinationDescription(currentIndicator, commLocation.getIndicator());
            }
            assumed = true;
        } else {
            log.debug("No indicator for prefix {}, number part {}, type {}. This path is not viable.", prefix.getCode(), numberAfterPrefix, currentTelephonyTypeId);
            return null;
        }

        BigDecimal initialPriceNoVat = currentCallValue;
        boolean initialVatIncluded = currentVatIncluded;

        if (prefix.isBandOk() && currentIndicator != null) {
            List<Band> bands = pricingRuleLookupService.findBandsForPrefixAndIndicator(prefix.getId(), commLocation.getIndicatorId());
            if (!bands.isEmpty()) {
                Band matchedBand = bands.get(0);
                currentCallValue = matchedBand.getValue();
                currentVatIncluded = matchedBand.getVatIncluded();
                bandIdForSpecialRate = matchedBand.getId();
                bandName = matchedBand.getName();
                initialPriceNoVat = currentCallValue;
                initialVatIncluded = currentVatIncluded;
            }
        }

        if (initialVatIncluded && currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
            initialPriceNoVat = initialPriceNoVat.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP);
        }

        SpecialRateApplicationResultDto srResult = applySpecialRatesToEvaluation(
                currentCallValue, currentVatIncluded, currentVatRate,
                callDateTime, currentTelephonyTypeId, currentOperatorId, bandIdForSpecialRate, commLocation.getIndicatorId(), originCountryId
        );
        if (srResult.isRateWasApplied()) {
            currentCallValue = srResult.getNewPricePerMinute(); // This is ex-VAT
            // currentVatIncluded is not directly used from srResult as price is now ex-VAT
            currentVatRate = srResult.getNewVatRate();
        } else { // If SR not applied, ensure currentCallValue is ex-VAT
            if (currentVatIncluded && currentVatRate.compareTo(BigDecimal.ZERO) > 0) {
                currentCallValue = currentCallValue.divide(BigDecimal.ONE.add(currentVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)), 10, RoundingMode.HALF_UP);
            }
        }


        return EvaluatedDestinationDto.builder()
                .processedNumber(numberAfterPrefix)
                .telephonyType(currentTelephonyType)
                .operator(currentOperator)
                .indicator(currentIndicator)
                .destinationDescription(currentDestinationDescription)
                .pricePerMinute(currentCallValue) // This is ex-VAT
                .vatIncludedInPrice(false) // Price is now ex-VAT
                .vatRate(currentVatRate)
                .initialPriceBeforeSpecialRates(initialPriceNoVat) // ex-VAT
                .initialPriceVatIncluded(prefix.isVatIncluded())
                .billedInSeconds(false) // Default, can be changed by trunk rule
                .billingUnitInSeconds(currentBillingUnit)
                .fromTrunk(isTrunkCallContext)
                .bandUsed(bandIdForSpecialRate != null)
                .bandId(bandIdForSpecialRate)
                .bandName(bandName)
                .assumed(assumed)
                .build();
    }

    private SpecialRateApplicationResultDto applySpecialRatesToEvaluation(
            BigDecimal currentPrice, boolean currentPriceIsVatIncluded, BigDecimal currentVatRatePercentage,
            LocalDateTime callTime, Long telephonyTypeId, Long operatorId, Long bandId, Long originIndicatorIdForSRLookup, Long originCountryId) {

        BigDecimal priceBeforeSpecialRateExVat = currentPrice;
        if (currentPriceIsVatIncluded && currentVatRatePercentage.compareTo(BigDecimal.ZERO) > 0) {
            priceBeforeSpecialRateExVat = currentPrice.divide(
                    BigDecimal.ONE.add(currentVatRatePercentage.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)),
                    10, RoundingMode.HALF_UP);
        }

        List<SpecialRateValue> specialRates = pricingRuleLookupService.findSpecialRateValues(
                callTime, telephonyTypeId, operatorId, bandId, originIndicatorIdForSRLookup
        );

        if (!specialRates.isEmpty()) {
            for (SpecialRateValue sr : specialRates) {
                if (isTimeInHoursSpecification(callTime, sr.getHoursSpecification())) {
                    BigDecimal newPriceExVat;
                    boolean srValueIncludesVat = sr.getIncludesVat();
                    BigDecimal newVatRate = currentVatRatePercentage;

                    Long srTelephonyTypeId = sr.getTelephonyTypeId() != null && sr.getTelephonyTypeId() != 0 ? sr.getTelephonyTypeId() : telephonyTypeId;
                    Long srOperatorId = sr.getOperatorId() != null && sr.getOperatorId() != 0 ? sr.getOperatorId() : operatorId;

                    if (!Objects.equals(srTelephonyTypeId, telephonyTypeId) || !Objects.equals(srOperatorId, operatorId)) {
                        newVatRate = pricingRuleLookupService.findVatForTelephonyOperator(srTelephonyTypeId, srOperatorId, originCountryId)
                                .orElse(currentVatRatePercentage);
                    }

                    if (sr.getValueType() != null && sr.getValueType() == 1) {
                        BigDecimal discountPercentage = sr.getRateValue().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                        newPriceExVat = priceBeforeSpecialRateExVat.multiply(BigDecimal.ONE.subtract(discountPercentage));
                    } else {
                        newPriceExVat = sr.getRateValue();
                        if (srValueIncludesVat && newVatRate.compareTo(BigDecimal.ZERO) > 0) {
                            newPriceExVat = newPriceExVat.divide(
                                    BigDecimal.ONE.add(newVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)),
                                    10, RoundingMode.HALF_UP);
                        }
                    }
                    return SpecialRateApplicationResultDto.builder()
                            .newPricePerMinute(newPriceExVat) // ex-VAT
                            .newVatIncluded(srValueIncludesVat) // VAT status of the SRV's absolute value
                            .newVatRate(newVatRate)
                            .appliedRule(sr)
                            .rateWasApplied(true)
                            .build();
                }
            }
        }
        return SpecialRateApplicationResultDto.builder().rateWasApplied(false)
                .newPricePerMinute(priceBeforeSpecialRateExVat).newVatIncluded(false).newVatRate(currentVatRatePercentage).build();
    }

    private boolean isTimeInHoursSpecification(LocalDateTime callTime, String hoursSpecification) {
        if (hoursSpecification == null || hoursSpecification.trim().isEmpty()) {
            return true;
        }
        int callHour = callTime.getHour();
        String[] parts = hoursSpecification.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-");
                if (range.length == 2) {
                    try {
                        int startHour = Integer.parseInt(range[0].trim());
                        int endHour = Integer.parseInt(range[1].trim());
                        if (callHour >= startHour && callHour <= endHour) {
                            return true;
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Invalid hour range in specification: {}", part);
                    }
                }
            } else {
                try {
                    if (callHour == Integer.parseInt(part)) {
                        return true;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid hour in specification: {}", part);
                }
            }
        }
        return false;
    }

    private TrunkRuleApplicationResultDto applyTrunkRulesToEvaluation(CallRecord callRecord, EvaluatedDestinationDto currentEval, CommunicationLocation commLocation, Long originCountryId) {
        if (!currentEval.isFromTrunk() || callRecord.getTrunk() == null || callRecord.getTrunk().isEmpty() || currentEval.getIndicator() == null) {
            return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
                    .newPricePerMinute(currentEval.getPricePerMinute())
                    .newVatIncluded(false)
                    .newVatRate(currentEval.getVatRate())
                    .newBillingUnitInSeconds(currentEval.getBillingUnitInSeconds())
                    .build();
        }

        Optional<Trunk> trunkOpt = coreLookupService.findTrunkByNameAndCommLocation(callRecord.getTrunk(), commLocation.getId());
        if (trunkOpt.isEmpty()) {
            return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
                    .newPricePerMinute(currentEval.getPricePerMinute()).newVatIncluded(false).newVatRate(currentEval.getVatRate())
                    .newBillingUnitInSeconds(currentEval.getBillingUnitInSeconds()).build();
        }

        List<TrunkRule> trunkRules = pricingRuleLookupService.findTrunkRules(
                trunkOpt.get().getId(),
                currentEval.getTelephonyType().getId(),
                String.valueOf(currentEval.getIndicator().getId()),
                commLocation.getIndicatorId()
        );

        if (!trunkRules.isEmpty()) {
            TrunkRule rule = trunkRules.get(0);
            log.debug("Applying trunk rule ID: {}", rule.getId());

            BigDecimal ruleRateValue = rule.getRateValue();
            boolean ruleVatIncluded = rule.getIncludesVat() != null && rule.getIncludesVat();

            BigDecimal ruleVatRate = currentEval.getVatRate();
            TelephonyType ruleTelephonyType = currentEval.getTelephonyType();
            Operator ruleOperator = currentEval.getOperator();
            Indicator ruleOriginIndicator = commLocation.getIndicator(); // Default to current commLocation's indicator for origin context


            if (rule.getNewTelephonyTypeId() != null) {
                Optional<TelephonyType> ttOpt = coreLookupService.findTelephonyTypeById(rule.getNewTelephonyTypeId());
                if (ttOpt.isPresent()) ruleTelephonyType = ttOpt.get();
            }
            if (rule.getNewOperatorId() != null) {
                Optional<Operator> opOpt = coreLookupService.findOperatorById(rule.getNewOperatorId());
                if (opOpt.isPresent()) ruleOperator = opOpt.get();
            }
            if (rule.getOriginIndicatorId() != null && rule.getOriginIndicatorId() != 0) {
                Optional<Indicator> indOpt = coreLookupService.findIndicatorById(rule.getOriginIndicatorId());
                if (indOpt.isPresent()) ruleOriginIndicator = indOpt.get();
            }

            if (ruleTelephonyType != null && ruleOperator != null) {
                Optional<BigDecimal> newVatRateOpt = pricingRuleLookupService.findVatForTelephonyOperator(
                        ruleTelephonyType.getId(), ruleOperator.getId(), originCountryId // Use originCountryId of the call
                );
                if (newVatRateOpt.isPresent()) ruleVatRate = newVatRateOpt.get();
            }

            BigDecimal priceAfterRuleExVat = ruleRateValue;
            if (ruleVatIncluded && ruleVatRate.compareTo(BigDecimal.ZERO) > 0) {
                priceAfterRuleExVat = ruleRateValue.divide(
                        BigDecimal.ONE.add(ruleVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)),
                        10, RoundingMode.HALF_UP);
            }

            Integer billingUnit = rule.getSeconds() != null && rule.getSeconds() > 0 ? rule.getSeconds() : currentEval.getBillingUnitInSeconds();

            return TrunkRuleApplicationResultDto.builder()
                    .newPricePerMinute(priceAfterRuleExVat)
                    .newVatIncluded(false)
                    .newVatRate(ruleVatRate)
                    .newBillingUnitInSeconds(billingUnit)
                    .newTelephonyType(ruleTelephonyType)
                    .newOperator(ruleOperator)
                    .newOriginIndicator(ruleOriginIndicator)
                    .appliedRule(rule)
                    .ruleWasApplied(true)
                    .build();
        }
        return TrunkRuleApplicationResultDto.builder().ruleWasApplied(false)
                .newPricePerMinute(currentEval.getPricePerMinute()).newVatIncluded(false).newVatRate(currentEval.getVatRate())
                .newBillingUnitInSeconds(currentEval.getBillingUnitInSeconds()).build();
    }

    private void applyEvaluationToCallRecord(CallRecord callRecord, EvaluatedDestinationDto evalDto, CommunicationLocation commLocation, Long originCountryId) {
        // Set initial values from evalDto before trunk rules potentially override them
        callRecord.setTelephonyType(evalDto.getTelephonyType());
        callRecord.setTelephonyTypeId(evalDto.getTelephonyType() != null ? evalDto.getTelephonyType().getId() : TelephonyTypeConstants.ERRORES);
        callRecord.setOperator(evalDto.getOperator());
        callRecord.setOperatorId(evalDto.getOperator() != null ? evalDto.getOperator().getId() : null);
        callRecord.setIndicator(evalDto.getIndicator());
        callRecord.setIndicatorId(evalDto.getIndicator() != null ? evalDto.getIndicator().getId() : null);

        callRecord.setInitialPrice(evalDto.getInitialPriceBeforeSpecialRates().setScale(4, RoundingMode.HALF_UP));

        TrunkRuleApplicationResultDto trunkResult = applyTrunkRulesToEvaluation(callRecord, evalDto, commLocation, originCountryId);

        BigDecimal finalPricePerUnitExVat;
        BigDecimal finalVatRate;
        Integer finalBillingUnit;

        if (trunkResult.isRuleWasApplied()) {
            finalPricePerUnitExVat = trunkResult.getNewPricePerMinute();
            finalVatRate = trunkResult.getNewVatRate();
            finalBillingUnit = trunkResult.getNewBillingUnitInSeconds();
            if (trunkResult.getNewTelephonyType() != null) {
                callRecord.setTelephonyType(trunkResult.getNewTelephonyType());
                callRecord.setTelephonyTypeId(trunkResult.getNewTelephonyType().getId());
            }
            if (trunkResult.getNewOperator() != null) {
                callRecord.setOperator(trunkResult.getNewOperator());
                callRecord.setOperatorId(trunkResult.getNewOperator().getId());
            }
            // Note: newOriginIndicator from trunk rule is not directly set on callRecord here,
            // but was used for VAT lookup if changed by rule.
        } else {
            finalPricePerUnitExVat = evalDto.getPricePerMinute(); // This is ex-VAT from evalDto
            finalVatRate = evalDto.getVatRate();
            finalBillingUnit = evalDto.getBillingUnitInSeconds();
        }

        BigDecimal finalPricePerUnitWithVat = finalPricePerUnitExVat;
        if (finalVatRate.compareTo(BigDecimal.ZERO) > 0) {
            finalPricePerUnitWithVat = finalPricePerUnitExVat.multiply(
                    BigDecimal.ONE.add(finalVatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
            );
        }
        callRecord.setPricePerMinute(finalPricePerUnitWithVat.setScale(4, RoundingMode.HALF_UP));

        int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
        long billedUnits = (long) Math.ceil((double) durationInSeconds / (double) finalBillingUnit);
        if (billedUnits == 0 && durationInSeconds > 0) billedUnits = 1;

        callRecord.setBilledAmount(finalPricePerUnitWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));
    }
}
