// File: com/infomedia/abacox/telephonypricing/component/cdrprocessing/TariffCalculationService.java
package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

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
    private final OperatorLookupService operatorLookupService;

    /**
     * PHP equivalent: procesaSaliente_Complementar and parts of evaluarDestino
     */
    public void calculateTariffsForOutgoing(CdrData cdrData, CommunicationLocation commLocation, ExtensionLimits extensionLimits) {
        log.debug("Calculating tariffs for OUTGOING/EXTERNAL CDR: {}, CommLocation: {}", cdrData.getCtlHash(), commLocation.getDirectory());

        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
            log.debug("Trunk lookup for '{}': {}", cdrData.getDestDeviceName(), trunkInfoOpt.isPresent() ? "Found" : "Not Found");
        }

        String numberForTariffing = cdrData.getEffectiveDestinationNumber();
        log.debug("Number for tariffing (effectiveDestinationNumber): {}", numberForTariffing);

        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();

        String initialNumberForPrefixLookup = numberForTariffing;
        if (trunkInfoOpt.isEmpty()) {
            initialNumberForPrefixLookup = CdrUtil.cleanPhoneNumber(numberForTariffing, pbxPrefixes, true).getCleanedNumber();
            log.debug("Non-trunk call. Number for prefix lookup (after PBX clean): {}", initialNumberForPrefixLookup);
        } else {
            log.debug("Trunk call. Initial number for prefix lookup (before trunk-specific PBX clean): {}", initialNumberForPrefixLookup);
        }

        TariffingAttemptResult attempt1 = attemptTariffing(initialNumberForPrefixLookup, commLocation, trunkInfoOpt, false);

        if (trunkInfoOpt.isPresent() && isTariffResultInvalidOrAssumed(attempt1) && !cdrData.isNormalizedTariffApplied()) {
            log.warn("Trunk call tariffing attempt 1 resulted in invalid/assumed. Attempting normalization for: {}", numberForTariffing);

            List<String> pbxPrefixesForNormalization = pbxPrefixes;
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                pbxPrefixesForNormalization = Collections.emptyList();
            }
            String normalizedNumberForLookup = CdrUtil.cleanPhoneNumber(numberForTariffing, pbxPrefixesForNormalization, true).getCleanedNumber();
            log.debug("Normalized number for lookup (treated as non-trunk): {}", normalizedNumberForLookup);

            TariffingAttemptResult attempt2 = attemptTariffing(normalizedNumberForLookup, commLocation, Optional.empty(), true);

            if (attempt2.bestPrefixInfo != null && attempt2.bestPrefixInfo.telephonyTypeId > 0 &&
                attempt2.bestPrefixInfo.telephonyTypeId != TelephonyTypeEnum.ERRORS.getValue() &&
                attempt2.bestDestInfo != null && attempt2.bestDestInfo.getIndicatorId() != null && attempt2.bestDestInfo.getIndicatorId() > 0) {

                boolean useNormalized = true;
                if (attempt1.bestPrefixInfo != null && attempt1.bestDestInfo != null) {
                    if (Objects.equals(attempt1.bestPrefixInfo.telephonyTypeId, attempt2.bestPrefixInfo.telephonyTypeId) &&
                        Objects.equals(attempt1.bestDestInfo.getIndicatorId(), attempt2.bestDestInfo.getIndicatorId())) {
                        useNormalized = false;
                        log.debug("Normalized result is same type/indicator as initial assumed result. Preferring initial.");
                    }
                }
                if (useNormalized) {
                    log.info("Using normalized tariffing result for trunk call. Original number: {}", numberForTariffing);
                    applyTariffingResult(cdrData, attempt2, commLocation, Optional.empty(), extensionLimits);
                    cdrData.setNormalizedTariffApplied(true);
                } else {
                    applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt, extensionLimits);
                }
            } else {
                log.warn("Normalization did not yield a valid result. Sticking with initial attempt.");
                applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt, extensionLimits);
            }
        } else {
            applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt, extensionLimits);
        }
        log.info("Final tariff calculation for OUTGOING/EXTERNAL CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                 cdrData.getCtlHash(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
    }

    public void calculateTariffsForIncoming(CdrData cdrData, CommunicationLocation commLocation) {
        log.debug("Calculating tariffs for INCOMING CDR: {}", cdrData.getCtlHash());
        // For incoming, the type/operator/indicator are of the *source*.
        // Base tariffing for incoming is typically zero unless specific rules apply.
        cdrData.setPricePerMinute(BigDecimal.ZERO);
        cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
        cdrData.setPriceIncludesVat(false);
        cdrData.setInitialPriceIncludesVat(false);
        cdrData.setVatRate(BigDecimal.ZERO);

        applySpecialRatesAndRules(cdrData, commLocation, null, null); // No destInfo/trunkInfo for incoming source tariffing

        cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        log.info("Final tariff for INCOMING CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                 cdrData.getCtlHash(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
    }

    public void calculateTariffsForInternal(CdrData cdrData, CommunicationLocation commLocation) {
        log.debug("Calculating tariffs for INTERNAL CDR: {}, Type: {}", cdrData.getCtlHash(), cdrData.getTelephonyTypeId());
        TariffValue internalTariff = telephonyTypeLookupService.getInternalTariffValue(
            cdrData.getTelephonyTypeId(), commLocation.getIndicator().getOriginCountryId()
        );
        cdrData.setPricePerMinute(internalTariff.getRateValue());
        cdrData.setInitialPricePerMinute(internalTariff.getRateValue()); // Initial is same as final for internal
        cdrData.setPriceIncludesVat(internalTariff.isIncludesVat());
        cdrData.setInitialPriceIncludesVat(internalTariff.isIncludesVat());
        cdrData.setVatRate(internalTariff.getVatRate());

        // Internal calls typically don't have special rates or trunk rules in the same way,
        // but if needed, applySpecialRatesAndRules could be called here with appropriate context.
        // For now, assume internal tariffs are final.

        cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        log.info("Final tariff for INTERNAL CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                 cdrData.getCtlHash(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
    }

    public void calculateTariffsForSpecialService(CdrData cdrData) {
        SpecialServiceInfo ssi = cdrData.getSpecialServiceTariff();
        if (ssi != null) {
            log.debug("Applying special service tariff: {}", ssi);
            cdrData.setPricePerMinute(ssi.value);
            cdrData.setInitialPricePerMinute(ssi.value);
            cdrData.setPriceIncludesVat(ssi.vatIncluded);
            cdrData.setInitialPriceIncludesVat(ssi.vatIncluded);
            cdrData.setVatRate(ssi.vatRate != null ? ssi.vatRate : BigDecimal.ZERO);
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
            log.info("Final tariff for SPECIAL SERVICE CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                     cdrData.getCtlHash(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
        } else {
            log.warn("TelephonyType is SPECIAL_SERVICES but no SpecialServiceInfo found for CDR: {}. Setting tariff to 0.", cdrData.getCtlHash());
            cdrData.setBilledAmount(BigDecimal.ZERO);
            cdrData.setPricePerMinute(BigDecimal.ZERO);
            cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
        }
    }


    private TariffingAttemptResult attemptTariffing(String numberForLookup, CommunicationLocation commLocation,
                                                    Optional<TrunkInfo> trunkInfoOpt, boolean isNormalizationAttempt) {
        log.debug("Attempting tariffing for number: '{}', isTrunk: {}, isNormalization: {}", numberForLookup, trunkInfoOpt.isPresent(), isNormalizationAttempt);
        TariffingAttemptResult result = new TariffingAttemptResult();
        result.setWasNormalizedAttempt(isNormalizationAttempt);

        List<Long> trunkTelephonyTypeIds = null;
        if (trunkInfoOpt.isPresent() && !isNormalizationAttempt) {
            trunkTelephonyTypeIds = trunkInfoOpt.get().getAllowedTelephonyTypeIds();
        }

        List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
            numberForLookup,
            commLocation,
            trunkInfoOpt.isPresent() && !isNormalizationAttempt,
            trunkTelephonyTypeIds
        );
        log.debug("Found {} potential prefixes for number '{}'", prefixes.size(), numberForLookup);

        for (PrefixInfo prefixInfo : prefixes) {
            log.trace("Evaluating prefix: {}", prefixInfo.getPrefixCode());
            String numberAfterOperatorPrefixStrip = numberForLookup;
            String operatorPrefixToPassToFindDest = prefixInfo.getPrefixCode();

            boolean stripOperatorPrefixForDestLookup = false;
            if (trunkInfoOpt.isPresent() && !isNormalizationAttempt) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, prefixInfo.telephonyTypeId, prefixInfo.operatorId
                );
                if (rateDetails.isPresent() && rateDetails.get().noPrefix != null) {
                    stripOperatorPrefixForDestLookup = rateDetails.get().noPrefix;
                }
            } else {
                stripOperatorPrefixForDestLookup = true;
            }

            if (stripOperatorPrefixForDestLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForLookup.startsWith(prefixInfo.getPrefixCode())) {
                numberAfterOperatorPrefixStrip = numberForLookup.substring(prefixInfo.getPrefixCode().length());
                operatorPrefixToPassToFindDest = null;
                log.trace("Stripped operator prefix '{}'. Number for dest lookup: {}", prefixInfo.getPrefixCode(), numberAfterOperatorPrefixStrip);
            }

            String numberForDestLookup = numberAfterOperatorPrefixStrip;
            if (prefixInfo.getTelephonyTypeMaxLength() != null && numberForDestLookup.length() > prefixInfo.getTelephonyTypeMaxLength()) {
                numberForDestLookup = numberForDestLookup.substring(0, prefixInfo.getTelephonyTypeMaxLength());
                log.debug("Number truncated to max length ({}) for type {}. New number for dest lookup: {}",
                        prefixInfo.getTelephonyTypeMaxLength(), prefixInfo.getTelephonyTypeId(), numberForDestLookup);
            }

            Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                numberForDestLookup, // Use the potentially truncated number
                prefixInfo.telephonyTypeId,
                prefixInfo.telephonyTypeMinLength != null ? prefixInfo.telephonyTypeMinLength : 0,
                commLocation.getIndicatorId(),
                prefixInfo.prefixId,
                commLocation.getIndicator().getOriginCountryId(),
                prefixInfo.bandsAssociatedCount > 0,
                (operatorPrefixToPassToFindDest == null),
                operatorPrefixToPassToFindDest
            );
            log.trace("Destination lookup for '{}' (type {}): {}", numberForDestLookup, prefixInfo.telephonyTypeId, destInfoOpt.isPresent() ? destInfoOpt.get() : "Not Found");

            if (destInfoOpt.isPresent()) {
                 DestinationInfo currentDestInfo = destInfoOpt.get();
                 if (result.bestDestInfo == null ||
                     (currentDestInfo.isApproximateMatch() == result.bestDestInfo.isApproximateMatch() && currentDestInfo.getPaddedSeriesRangeSize() < result.bestDestInfo.getPaddedSeriesRangeSize()) ||
                     (!currentDestInfo.isApproximateMatch() && result.bestDestInfo.isApproximateMatch())
                 ) {
                    result.bestDestInfo = currentDestInfo;
                    result.bestPrefixInfo = prefixInfo;
                    result.matchedNumber = numberForDestLookup;
                    log.debug("New best destination match for attempt: {}, with prefix: {}", result.bestDestInfo, result.bestPrefixInfo.getPrefixCode());
                 }
                 if (result.bestDestInfo != null && !result.bestDestInfo.isApproximateMatch()) {
                    log.debug("Exact destination match found for attempt. Stopping prefix iteration.");
                    break;
                 }
            }
        }
        result.finalNumberUsedForDestLookup = numberForLookup;
        if (result.bestDestInfo != null) {
            result.finalNumberUsedForDestLookup = result.bestDestInfo.getMatchedPhoneNumber();
        }
        return result;
    }

    private void applyTariffingResult(CdrData cdrData, TariffingAttemptResult result, CommunicationLocation commLocation, Optional<TrunkInfo> trunkInfoOpt, ExtensionLimits extensionLimits) {
        if (result.bestDestInfo != null && result.bestPrefixInfo != null) {
            log.info("Applying tariffing result. Destination: {}, Prefix: {}", result.bestDestInfo.getDestinationDescription(), result.bestPrefixInfo.getPrefixCode());

            cdrData.setEffectiveDestinationNumber(result.getMatchedNumber());
            log.debug("CDR effective destination number updated to matched/truncated value: {}", cdrData.getEffectiveDestinationNumber());

            cdrData.setTelephonyTypeId(result.bestPrefixInfo.telephonyTypeId);
            cdrData.setTelephonyTypeName(result.bestPrefixInfo.telephonyTypeName);

            if (result.bestDestInfo.getOperatorId() != null && result.bestDestInfo.getOperatorId() != 0L) {
                cdrData.setOperatorId(result.bestDestInfo.getOperatorId());
                String operatorNameFromIndicator = operatorLookupService.findOperatorNameById(result.bestDestInfo.getOperatorId());
                cdrData.setOperatorName(operatorNameFromIndicator != null ? operatorNameFromIndicator : result.bestPrefixInfo.operatorName);
            } else {
                cdrData.setOperatorId(result.bestPrefixInfo.operatorId);
                cdrData.setOperatorName(result.bestPrefixInfo.operatorName);
            }

            cdrData.setIndicatorId(result.bestDestInfo.getIndicatorId());
            cdrData.setDestinationCityName(result.bestDestInfo.getDestinationDescription());

            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(result.bestDestInfo.getNdc(), commLocation.getIndicatorId(), result.bestDestInfo.getIndicatorId())) {
                log.debug("Call to {} identified as LOCAL_EXTENDED.", result.bestDestInfo.getDestinationDescription());
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
                PrefixInfo localExtPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId());
                if (localExtPrefixInfo != null) {
                    cdrData.setOperatorId(localExtPrefixInfo.getOperatorId());
                    cdrData.setOperatorName(localExtPrefixInfo.getOperatorName());
                    result.bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId(); // Update for subsequent logic
                }
            }

            TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(
                result.bestPrefixInfo.prefixId,
                result.bestDestInfo.getIndicatorId(),
                commLocation.getId(),
                commLocation.getIndicatorId()
            );
            log.debug("Base tariff for prefixId {}: {}", result.bestPrefixInfo.prefixId, baseTariff);

            cdrData.setPricePerMinute(baseTariff.getRateValue());
            cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
            cdrData.setVatRate(baseTariff.getVatRate());
            cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
            cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

            if (trunkInfoOpt.isPresent() && !result.isWasNormalizedAttempt()) {
                applyTrunkSpecificRates(cdrData, trunkInfoOpt.get(), commLocation);
            }

            applySpecialRatesAndRules(cdrData, commLocation, result.bestDestInfo, trunkInfoOpt.orElse(null));
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        } else {
            log.warn("Could not determine destination or tariff for: {} (original number for tariffing: {}). Applying fallback logic.",
                    result.finalNumberUsedForDestLookup, result.finalNumberUsedForDestLookup);

            Long attemptedTelephonyTypeId = TelephonyTypeEnum.LOCAL.getValue(); // Default if no prefix matched
            int attemptedMinLength = 0;
            if (result.bestPrefixInfo != null) {
                attemptedTelephonyTypeId = result.bestPrefixInfo.telephonyTypeId;
                attemptedMinLength = result.bestPrefixInfo.telephonyTypeMinLength != null ? result.bestPrefixInfo.telephonyTypeMinLength : 0;
            }

            int phoneLength = result.finalNumberUsedForDestLookup.length();
            int maxInternalLength = String.valueOf(extensionLimits.getMaxLength()).length();
            boolean isLocalType = telephonyTypeLookupService.isLocalType(attemptedTelephonyTypeId);

            boolean isError = (isLocalType && phoneLength > maxInternalLength && phoneLength < attemptedMinLength) ||
                              (!isLocalType && phoneLength < attemptedMinLength);

            if (isError) {
                log.error("Number '{}' has invalid length for attempted type {}. Marking as ERROR.", result.finalNumberUsedForDestLookup, attemptedTelephonyTypeId);
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
                cdrData.setTelephonyTypeName("Invalid Number Length");
            } else {
                log.info("Number '{}' is valid for attempted type {} but no destination was found. Assigning type with zero cost.", result.finalNumberUsedForDestLookup, attemptedTelephonyTypeId);
                cdrData.setTelephonyTypeId(attemptedTelephonyTypeId);
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(attemptedTelephonyTypeId) + " (Unclassified - No Destination Match)");
            }

            cdrData.setBilledAmount(BigDecimal.ZERO);
            cdrData.setPricePerMinute(BigDecimal.ZERO);
            cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
        }
    }

    private void applyTrunkSpecificRates(CdrData cdrData, TrunkInfo trunkInfo, CommunicationLocation commLocation) {
        log.debug("Applying trunk-specific rates for trunk: {}", trunkInfo.description);
        Optional<TrunkRateDetails> rateDetailsOpt = trunkLookupService.getRateDetailsForTrunk(
            trunkInfo.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
        );
        if (rateDetailsOpt.isPresent()) {
            TrunkRateDetails rd = rateDetailsOpt.get();
            log.debug("Found specific rate details for trunk: {}", rd);
            // Store current price as initial before overriding with trunk rate
            cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
            cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

            cdrData.setPricePerMinute(rd.rateValue);
            cdrData.setPriceIncludesVat(rd.includesVat);
            cdrData.setChargeBySecond(rd.seconds != null && rd.seconds > 0);
            cdrData.setVatRate(telephonyTypeLookupService.getVatForPrefix(rd.telephonyTypeId, rd.operatorId, commLocation.getIndicator().getOriginCountryId()));
            cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Trunk: " + trunkInfo.description + ")");

            if (trunkInfo.isCelufijo() && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.CELLULAR.getValue()) {
                log.debug("Trunk is celufijo and call is cellular. Changing type to CELUFIJO.");
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.CELUFIJO.getValue());
                cdrData.setTelephonyTypeName("Celufijo (Trunk: " + trunkInfo.description + ")");
            }
        } else {
            log.debug("No specific rate details found for trunk {} with type {} and operator {}. Using previously determined base prefix tariff.",
                      trunkInfo.description, cdrData.getTelephonyTypeId(), cdrData.getOperatorId());
        }
    }

    private void applySpecialRatesAndRules(CdrData cdrData, CommunicationLocation commLocation,
                                           DestinationInfo destinationInfo, TrunkInfo trunkInfo) {
        if (cdrData.getInitialPricePerMinute() == null) { // Ensure initial price is set
             cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute() != null ? cdrData.getPricePerMinute() : BigDecimal.ZERO);
             cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
        }

        Optional<SpecialRateInfo> specialRateOpt =
            specialRateValueLookupService.getApplicableSpecialRate(
                cdrData.getDateTimeOrigination(),
                commLocation.getIndicatorId(),
                cdrData.getTelephonyTypeId(),
                cdrData.getOperatorId(),
                destinationInfo != null ? destinationInfo.getBandId() : null
        );

        if (specialRateOpt.isPresent()) {
            SpecialRateInfo sr = specialRateOpt.get();
            log.info("Applying special rate: {}", sr);
            // Store current price as initial *before* applying special rate, if not already different
            if (Objects.equals(cdrData.getInitialPricePerMinute(), cdrData.getPricePerMinute()) &&
                cdrData.isInitialPriceIncludesVat() == cdrData.isPriceIncludesVat()) {
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

        if (cdrData.getCallDirection() != CallDirection.INCOMING && cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
             Optional<AppliedTrunkRuleInfo> ruleInfoOpt =
                trunkRuleLookupService.getAppliedTrunkRule(
                    cdrData.getDestDeviceName(),
                    cdrData.getTelephonyTypeId(), // Use current type, which might have been changed by special rate
                    cdrData.getIndicatorId(),
                    commLocation.getIndicatorId()
                );
            if (ruleInfoOpt.isPresent()) {
                AppliedTrunkRuleInfo rule = ruleInfoOpt.get();
                log.info("Applying trunk rule: {}", rule);
                // Store current price as initial *before* applying rule, if not already different
                if (Objects.equals(cdrData.getInitialPricePerMinute(), cdrData.getPricePerMinute()) &&
                    cdrData.isInitialPriceIncludesVat() == cdrData.isPriceIncludesVat()) {
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
                    cdrData.setOperatorName(rule.newOperatorName != null ? rule.newOperatorName : operatorLookupService.findOperatorNameById(rule.newOperatorId));
                }
                cdrData.setVatRate(rule.vatRate);
                cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Rule Applied)");
            }
        }
    }


    private boolean isTariffResultInvalidOrAssumed(TariffingAttemptResult result) {
        return result.bestDestInfo == null ||
               result.bestPrefixInfo == null ||
               result.bestPrefixInfo.telephonyTypeId == null ||
               result.bestPrefixInfo.telephonyTypeId <= 0 ||
               result.bestPrefixInfo.telephonyTypeId == TelephonyTypeEnum.ERRORS.getValue() ||
               (result.bestDestInfo.getDestinationDescription() != null && result.bestDestInfo.getDestinationDescription().contains(appConfigService.getAssumedText())) ||
               (result.bestPrefixInfo.telephonyTypeName != null && result.bestPrefixInfo.telephonyTypeName.contains(appConfigService.getAssumedText()));
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
            if (cdrData.getPricePerMinute().compareTo(BigDecimal.ZERO) == 0 && billableDurationUnits > 0) {
                 ratePerUnit = BigDecimal.ZERO;
            } else if (cdrData.getPricePerMinute().compareTo(BigDecimal.ZERO) == 0 && billableDurationUnits == 0) {
                 ratePerUnit = BigDecimal.ZERO;
            }
            else {
                ratePerUnit = cdrData.getPricePerMinute().divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
            }
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