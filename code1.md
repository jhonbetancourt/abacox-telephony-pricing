Okay, I've analyzed the discrepancies and the PHP logic. The primary issue causing the `telephony_type_id` to become `99` (Error) in Java is that the tariff calculation for incoming calls was using the internal extension number instead of the external caller's number for prefix lookups. The secondary issue is the mapping of `dial` and `destination_phone` in the `CallRecord` for incoming calls to match PHP's `ACUMTOTAL_DIAL` and `ACUMTOTAL_TELEFONO_DESTINO` semantics.

Here are the updated classes:

**1. `TariffCalculationService.java`**

The `calculateTariffs` method needs to be adjusted to use the correct number for tariffing when the call is incoming.

```java
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
    private final OperatorLookupService operatorLookupService;

    public void calculateTariffs(CdrData cdrData, CommunicationLocation commLocation) {
        log.debug("Calculating tariffs for CDR: {}, CommLocation: {}", cdrData.getCtlHash(), commLocation.getDirectory());

        if (cdrData.getDurationSeconds() != null && cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
            if (cdrData.getTelephonyTypeId() != null &&
                cdrData.getTelephonyTypeId() > 0 &&
                cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                log.info("Call duration {}s <= min duration {}s. Setting type to NO_CONSUMPTION.", cdrData.getDurationSeconds(), appConfigService.getMinCallDurationForTariffing());
                cdrData.setBilledAmount(BigDecimal.ZERO);
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                return;
            }
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
                log.warn("TelephonyType is SPECIAL_SERVICES but no SpecialServiceInfo found for CDR: {}. Marking as ERROR.", cdrData.getCtlHash());
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

        String numberForTariffing;
        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            // For incoming calls, tariffing is based on the external caller's number
            numberForTariffing = cdrData.getFinalCalledPartyNumber();
            log.debug("Incoming call. Number for tariffing (external caller): {}", numberForTariffing);
        } else { // OUTGOING or internal processed as outgoing
            numberForTariffing = cdrData.getEffectiveDestinationNumber();
            log.debug("Outgoing call. Number for tariffing (effective destination): {}", numberForTariffing);
        }


        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        String initialNumberForPrefixLookup = numberForTariffing;
        if (trunkInfoOpt.isEmpty()) {
            initialNumberForPrefixLookup = CdrUtil.cleanPhoneNumber(numberForTariffing, pbxPrefixes, true);
            log.debug("Non-trunk call. Number for prefix lookup (after PBX clean): {}", initialNumberForPrefixLookup);
        } else {
            log.debug("Trunk call. Initial number for prefix lookup (before trunk-specific PBX clean): {}", initialNumberForPrefixLookup);
        }
        
        TariffingAttemptResult attempt1 = attemptTariffing(initialNumberForPrefixLookup, cdrData, commLocation, trunkInfoOpt, pbxPrefixes, false);

        if (trunkInfoOpt.isPresent() && isTariffResultInvalidOrAssumed(attempt1) && !cdrData.isNormalizedTariffApplied()) {
            log.warn("Trunk call tariffing attempt 1 resulted in invalid/assumed. Attempting normalization for: {}", numberForTariffing);
            
            List<String> pbxPrefixesForNormalization = pbxPrefixes;
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                pbxPrefixesForNormalization = Collections.emptyList();
            }
            String normalizedNumberForLookup = CdrUtil.cleanPhoneNumber(numberForTariffing, pbxPrefixesForNormalization, true);
            log.debug("Normalized number for lookup (treated as non-trunk): {}", normalizedNumberForLookup);
            
            TariffingAttemptResult attempt2 = attemptTariffing(normalizedNumberForLookup, cdrData, commLocation, Optional.empty(), pbxPrefixes, true);

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
                    applyTariffingResult(cdrData, attempt2, commLocation, Optional.empty());
                    cdrData.setNormalizedTariffApplied(true);
                } else {
                    applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt);
                }
            } else {
                log.warn("Normalization did not yield a valid result. Sticking with initial attempt.");
                applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt);
            }
        } else {
            applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt);
        }

        log.info("Final tariff calculation for CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                 cdrData.getCtlHash(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
    }

    private TariffingAttemptResult attemptTariffing(String numberForLookup, CdrData cdrData, CommunicationLocation commLocation,
                                                    Optional<TrunkInfo> trunkInfoOpt, List<String> pbxPrefixes, boolean isNormalizationAttempt) {
        log.debug("Attempting tariffing for number: '{}', isTrunk: {}, isNormalization: {}", numberForLookup, trunkInfoOpt.isPresent(), isNormalizationAttempt);
        TariffingAttemptResult result = new TariffingAttemptResult();

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
            boolean operatorPrefixStrippedThisIteration = false;
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
                operatorPrefixStrippedThisIteration = true;
                operatorPrefixToPassToFindDest = null;
                log.trace("Stripped operator prefix '{}'. Number for dest lookup: {}", prefixInfo.getPrefixCode(), numberAfterOperatorPrefixStrip);
            }
            
            Optional<DestinationInfo> destInfoOpt = indicatorLookupService.findDestinationIndicator(
                numberAfterOperatorPrefixStrip,
                prefixInfo.telephonyTypeId,
                prefixInfo.telephonyTypeMinLength != null ? prefixInfo.telephonyTypeMinLength : 0,
                commLocation.getIndicatorId(),
                prefixInfo.prefixId,
                commLocation.getIndicator().getOriginCountryId(),
                prefixInfo.bandsAssociatedCount > 0,
                operatorPrefixStrippedThisIteration,
                operatorPrefixToPassToFindDest
            );
            log.trace("Destination lookup for '{}' (type {}): {}", numberAfterOperatorPrefixStrip, prefixInfo.telephonyTypeId, destInfoOpt.isPresent() ? destInfoOpt.get() : "Not Found");

            if (destInfoOpt.isPresent()) {
                 DestinationInfo currentDestInfo = destInfoOpt.get();
                 if (result.bestDestInfo == null ||
                     (!result.bestDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch()) || // Prefer non-approx over approx
                     (currentDestInfo.isApproximateMatch() == result.bestDestInfo.isApproximateMatch() && // If both same approx status
                         currentDestInfo.getSeriesRangeSize() < result.bestDestInfo.getSeriesRangeSize()) || // Prefer tighter range
                     (!currentDestInfo.isApproximateMatch() && result.bestDestInfo.isApproximateMatch()) // Prefer non-approx if current is non-approx and previous was approx
                 ) {
                    result.bestDestInfo = currentDestInfo;
                    result.bestPrefixInfo = prefixInfo;
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

    private void applyTariffingResult(CdrData cdrData, TariffingAttemptResult result, CommunicationLocation commLocation, Optional<TrunkInfo> trunkInfoOpt) {
        if (result.bestDestInfo != null && result.bestPrefixInfo != null) {
            log.info("Applying tariffing result. Destination: {}, Prefix: {}", result.bestDestInfo.getDestinationDescription(), result.bestPrefixInfo.getPrefixCode());
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
            // Effective destination number is set based on call direction before tariffing.
            // Here, we might update it if the tariffing process refined it (e.g. after stripping prefixes for lookup)
            // However, PHP's ACUMTOTAL_DIAL for incoming is the external number.
            // For outgoing, it's the interpreted dialed number.
            // CdrData.effectiveDestinationNumber is already set correctly by CallTypeDeterminationService.
            // The number used for the successful lookup is result.finalNumberUsedForDestLookup.
            // If this is an incoming call, finalNumberUsedForDestLookup is the external number.
            // If outgoing, it's the dialed number (possibly after prefix strips).
            // We should ensure cdrData.effectiveDestinationNumber reflects the number that *will be billed against*.
            // For incoming, this is complex as we bill our extension, but the rate is from the external number.
            // PHP's ACUMTOTAL_DIAL stores the external number for incoming.
            // Let's ensure cdrData.effectiveDestinationNumber is the number that *led to the tariff*.
            if (cdrData.getCallDirection() == CallDirection.INCOMING) {
                // For incoming, the tariff is based on the external number (result.finalNumberUsedForDestLookup)
                // but the "destination" of the call leg is our internal extension.
                // We don't change cdrData.effectiveDestinationNumber here as it's already set to our extension.
                // The tariffing was done using the external number.
            } else { // OUTGOING
                cdrData.setEffectiveDestinationNumber(result.finalNumberUsedForDestLookup);
            }


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
                    result.bestDestInfo.getBandId()
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
                        cdrData.setOperatorName(rule.newOperatorName != null ? rule.newOperatorName : operatorLookupService.findOperatorNameById(rule.newOperatorId));
                    }
                    cdrData.setVatRate(rule.vatRate);
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Rule Applied)");
                }
            }
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        } else {
            log.warn("Could not determine destination or tariff for: {} (original number for tariffing: {}). Marking as ERROR.", result.finalNumberUsedForDestLookup, result.finalNumberUsedForDestLookup);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.ERRORS.getValue()));
            cdrData.setBilledAmount(BigDecimal.ZERO);
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
                 log.warn("Price per minute is zero but duration is > 0 and charging by second. Result will be zero.");
                 ratePerUnit = BigDecimal.ZERO;
            } else if (cdrData.getPricePerMinute().compareTo(BigDecimal.ZERO) == 0 && billableDurationUnits == 0) {
                 ratePerUnit = BigDecimal.ZERO;
            }
            else {
                ratePerUnit = cdrData.getPricePerMinute().divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
            }
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
```

**2. `CallRecordPersistenceService.java`**

The `mapCdrDataToCallRecord` method needs to be updated to handle the `dial` and `destinationPhone` fields differently for incoming calls.

```java
// File: com/infomedia/abacox/telephonypricing/cdr/CallRecordPersistenceService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.NoResultException;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Log4j2
public class CallRecordPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;
    private final FailedCallRecordPersistenceService failedCallRecordPersistenceService;

    public CallRecordPersistenceService(FailedCallRecordPersistenceService failedCallRecordPersistenceService) {
        this.failedCallRecordPersistenceService = failedCallRecordPersistenceService;
    }

    @Transactional
    public CallRecord saveOrUpdateCallRecord(CdrData cdrData, CommunicationLocation commLocation) {
        if (cdrData.isMarkedForQuarantine()) {
            log.warn("CDR marked for quarantine, not saving to CallRecord: {}", cdrData.getCtlHash());
            return null;
        }

        String cdrHash = CdrUtil.generateCtlHash(cdrData.getRawCdrLine(), commLocation.getId());
        log.debug("Generated CDR Hash: {}", cdrHash);


        CallRecord existingRecord = findByCtlHash(cdrHash);
        if (existingRecord != null) {
            log.warn("Duplicate CDR detected based on hash {}. Original ID: {}. Quarantining.",
                    cdrHash, existingRecord.getId());
            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.DUPLICATE_RECORD,
                    "Duplicate record based on hash. Original ID: " + existingRecord.getId(),
                    "saveOrUpdateCallRecord_DuplicateCheck",
                    existingRecord.getId());
            return existingRecord;
        }

        CallRecord callRecord = new CallRecord();
        mapCdrDataToCallRecord(cdrData, callRecord, commLocation, cdrHash);

        try {
            log.debug("Persisting CallRecord: {}", callRecord);
            entityManager.persist(callRecord);
            log.info("Saved new CallRecord with ID: {} for CDR line: {}", callRecord.getId(), cdrData.getCtlHash());
            return callRecord;
        } catch (Exception e) {
            log.error("Database error while saving CallRecord for CDR: {}. Error: {}", cdrData.getCtlHash(), e.getMessage(), e);
            failedCallRecordPersistenceService.quarantineRecord(cdrData,
                    QuarantineErrorType.DB_INSERT_FAILED,
                    "Database error: " + e.getMessage(),
                    "saveOrUpdateCallRecord_Persist",
                    null);
            return null;
        }
    }

    @Transactional(readOnly = true)
    public CallRecord findByCtlHash(String ctlHash) {
        try {
            return entityManager.createQuery("SELECT cr FROM CallRecord cr WHERE cr.ctlHash = :hash", CallRecord.class)
                    .setParameter("hash", ctlHash)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }


    private void mapCdrDataToCallRecord(CdrData cdrData, CallRecord callRecord, CommunicationLocation commLocation, String cdrHash) {
        // Mapping logic based on call direction to match PHP's ACUMTOTAL fields
        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            // For INCOMING:
            // PHP ACUMTOTAL_DIAL = external number
            // PHP ACUMTOTAL_TELEFONO_DESTINO = external number (or modified external by buscarOrigen)
            // PHP ACUMTOTAL_FUN_EXTENSION = our internal extension
            callRecord.setDial(cdrData.getFinalCalledPartyNumber() != null ? cdrData.getFinalCalledPartyNumber().substring(0, Math.min(cdrData.getFinalCalledPartyNumber().length(), 50)) : "");
            callRecord.setDestinationPhone(cdrData.getFinalCalledPartyNumber() != null ? cdrData.getFinalCalledPartyNumber().substring(0, Math.min(cdrData.getFinalCalledPartyNumber().length(), 50)) : "");
            callRecord.setEmployeeExtension(cdrData.getCallingPartyNumber() != null ? cdrData.getCallingPartyNumber().substring(0, Math.min(cdrData.getCallingPartyNumber().length(), 50)) : "");
        } else { // OUTGOING
            // For OUTGOING:
            // PHP ACUMTOTAL_DIAL = interpreted/effective destination
            // PHP ACUMTOTAL_TELEFONO_DESTINO = original dial_number from CDR
            // PHP ACUMTOTAL_FUN_EXTENSION = our internal extension
            callRecord.setDial(cdrData.getEffectiveDestinationNumber() != null ? cdrData.getEffectiveDestinationNumber().substring(0, Math.min(cdrData.getEffectiveDestinationNumber().length(), 50)) : "");
            callRecord.setDestinationPhone(cdrData.getFinalCalledPartyNumber() != null ? cdrData.getFinalCalledPartyNumber().substring(0, Math.min(cdrData.getFinalCalledPartyNumber().length(), 50)) : "");
            callRecord.setEmployeeExtension(cdrData.getCallingPartyNumber() != null ? cdrData.getCallingPartyNumber().substring(0, Math.min(cdrData.getCallingPartyNumber().length(), 50)) : "");
        }

        callRecord.setCommLocationId(commLocation.getId());
        callRecord.setServiceDate(cdrData.getDateTimeOrigination());
        callRecord.setOperatorId(cdrData.getOperatorId());
        callRecord.setEmployeeAuthCode(cdrData.getAuthCodeDescription() != null ? cdrData.getAuthCodeDescription().substring(0, Math.min(cdrData.getAuthCodeDescription().length(), 50)) : "");
        callRecord.setIndicatorId(cdrData.getIndicatorId());
        callRecord.setDuration(cdrData.getDurationSeconds());
        callRecord.setRingCount(cdrData.getRingingTimeSeconds());
        callRecord.setTelephonyTypeId(cdrData.getTelephonyTypeId());
        callRecord.setBilledAmount(cdrData.getBilledAmount());
        callRecord.setPricePerMinute(cdrData.getPricePerMinute());
        callRecord.setInitialPrice(cdrData.getInitialPricePerMinute());
        callRecord.setIncoming(cdrData.getCallDirection() == CallDirection.INCOMING);
        callRecord.setTrunk(cdrData.getDestDeviceName() != null ? cdrData.getDestDeviceName().substring(0, Math.min(cdrData.getDestDeviceName().length(), 50)) : "");
        callRecord.setInitialTrunk(cdrData.getOrigDeviceName() != null ? cdrData.getOrigDeviceName().substring(0, Math.min(cdrData.getOrigDeviceName().length(), 50)) : "");
        callRecord.setEmployeeId(cdrData.getEmployeeId());
        callRecord.setEmployeeTransfer(cdrData.getEmployeeTransferExtension() != null ? cdrData.getEmployeeTransferExtension().substring(0, Math.min(cdrData.getEmployeeTransferExtension().length(), 50)) : "");
        callRecord.setTransferCause(cdrData.getTransferCause().getValue());
        callRecord.setAssignmentCause(cdrData.getAssignmentCause().getValue());
        callRecord.setDestinationEmployeeId(cdrData.getDestinationEmployeeId());
        // callRecord.setCdrString(cdrData.getRawCdrLine()); // Removed as per entity definition
        if (cdrData.getFileInfo() != null) {
            callRecord.setFileInfoId(cdrData.getFileInfo().getId().longValue()); // Changed to Long
        }
        callRecord.setCdrHash(cdrHash);
    }
}
```

**3. `PrefixLookupService.java`**

The logic for non-trunk calls when no explicit operator prefix is found needs to be more accommodating for types like International, which might not have a leading prefix in the `prefix.code` table but are identified by their structure and `telephony_type_id`.

```java
// File: com/infomedia/abacox/telephonypricing/cdr/PrefixLookupService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.Operator;
import com.infomedia.abacox.telephonypricing.entity.Prefix;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Log4j2
@RequiredArgsConstructor
public class PrefixLookupService {

    @PersistenceContext
    private EntityManager entityManager;
    private final PhoneNumberTransformationService phoneNumberTransformationService;
    private final TelephonyTypeLookupService telephonyTypeLookupService;

    @Transactional(readOnly = true)
    public List<PrefixInfo> findMatchingPrefixes(String dialedNumber,
                                                 CommunicationLocation commLocation,
                                                 boolean isTrunkCall,
                                                 List<Long> trunkTelephonyTypeIds) {
        log.debug("Finding matching prefixes for dialedNumber: '{}', isTrunkCall: {}, trunkTelephonyTypeIds: {}",
                dialedNumber, isTrunkCall, trunkTelephonyTypeIds);

        String numberForLookup = dialedNumber;
        Long hintedTelephonyTypeIdFromTransform = null;
        if (commLocation != null && commLocation.getIndicator() != null &&
            commLocation.getIndicator().getOriginCountryId() != null) {
            TransformationResult transformResult =
                phoneNumberTransformationService.transformForPrefixLookup(dialedNumber, commLocation);
            if (transformResult.isTransformed()) {
                numberForLookup = transformResult.getTransformedNumber();
                hintedTelephonyTypeIdFromTransform = transformResult.getNewTelephonyTypeId();
                log.debug("Dialed number '{}' transformed for prefix lookup to '{}', hinted type: {}", dialedNumber, numberForLookup, hintedTelephonyTypeIdFromTransform);
            }
        }
        final String finalNumberForLookup = numberForLookup;

        String queryStr = "SELECT p.*, ttc.min_value as ttc_min, ttc.max_value as ttc_max, " +
                "(SELECT COUNT(*) FROM band b WHERE b.prefix_id = p.id AND b.active = true) as bands_count " +
                "FROM prefix p " +
                "JOIN operator o ON p.operator_id = o.id " +
                "JOIN telephony_type tt ON p.telephony_type_id = tt.id " +
                "LEFT JOIN telephony_type_config ttc ON tt.id = ttc.telephony_type_id AND ttc.origin_country_id = :originCountryId AND ttc.active = true " +
                "WHERE p.active = true AND o.active = true AND tt.active = true AND o.origin_country_id = :originCountryId " +
                "AND p.telephony_type_id != :specialServicesTypeId ";

        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
            queryStr += "AND p.telephony_type_id IN (:trunkTelephonyTypeIds) ";
            log.debug("Trunk call, filtering by telephonyTypeIds: {}", trunkTelephonyTypeIds);
        }
        queryStr += "ORDER BY LENGTH(p.code) DESC, ttc.min_value DESC, p.telephony_type_id";


        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", commLocation.getIndicator().getOriginCountryId());
        nativeQuery.setParameter("specialServicesTypeId", TelephonyTypeEnum.SPECIAL_SERVICES.getValue());
        if (isTrunkCall && trunkTelephonyTypeIds != null && !trunkTelephonyTypeIds.isEmpty()) {
            nativeQuery.setParameter("trunkTelephonyTypeIds", trunkTelephonyTypeIds);
        }

        List<Tuple> results = nativeQuery.getResultList();
        List<PrefixInfo> allRelevantPrefixes = results.stream().map(tuple -> {
            Prefix p = entityManager.find(Prefix.class, tuple.get("id", Number.class).longValue());
            TelephonyTypeConfig cfg = new TelephonyTypeConfig();
            cfg.setMinValue(tuple.get("ttc_min", Number.class) != null ? tuple.get("ttc_min", Number.class).intValue() : 0);
            cfg.setMaxValue(tuple.get("ttc_max", Number.class) != null ? tuple.get("ttc_max", Number.class).intValue() : 99);
            int bandsCount = tuple.get("bands_count", Number.class).intValue();
            return new PrefixInfo(p, cfg, bandsCount);
        }).collect(Collectors.toList());
        log.debug("All relevant prefixes fetched: {}", allRelevantPrefixes.size());

        List<PrefixInfo> matchedPrefixes = new ArrayList<>();
        if (!isTrunkCall) {
            String bestMatchPrefixCode = null;
            for (PrefixInfo pi : allRelevantPrefixes) {
                if (pi.getPrefixCode() != null && !pi.getPrefixCode().isEmpty() && finalNumberForLookup.startsWith(pi.getPrefixCode())) {
                    bestMatchPrefixCode = pi.getPrefixCode();
                    break;
                }
            }

            if (bestMatchPrefixCode != null) {
                final String finalBestMatchPrefixCode = bestMatchPrefixCode;
                allRelevantPrefixes.stream()
                        .filter(pi -> finalBestMatchPrefixCode.equals(pi.getPrefixCode()))
                        .forEach(matchedPrefixes::add);
                log.debug("Non-trunk call, best matching explicit prefix code: '{}', found {} matches.", bestMatchPrefixCode, matchedPrefixes.size());
            } else {
                // If no explicit prefix code matched, consider prefixes with empty codes
                // that match the hintedTelephonyTypeIdFromTransform or any type if no hint.
                log.debug("Non-trunk call, no explicit prefix code matched for '{}'. Considering prefixes with empty codes or matching hinted type.", finalNumberForLookup);
                allRelevantPrefixes.stream()
                    .filter(pi -> (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty()) &&
                                   (hintedTelephonyTypeIdFromTransform == null || pi.getTelephonyTypeId().equals(hintedTelephonyTypeIdFromTransform)) &&
                                   finalNumberForLookup.length() >= (pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0))
                    .forEach(matchedPrefixes::add);
                if (!matchedPrefixes.isEmpty()) {
                    log.debug("Added {} prefixes with empty codes (or matching hinted type) for non-trunk call.", matchedPrefixes.size());
                }
            }
        } else {
            matchedPrefixes.addAll(allRelevantPrefixes);
            log.debug("Trunk call, added {} prefixes associated with allowed trunk telephony types.", matchedPrefixes.size());
        }

        if (!isTrunkCall) {
            boolean localPrefixAlreadyMatched = matchedPrefixes.stream()
                .anyMatch(pi -> pi.getTelephonyTypeId().equals(TelephonyTypeEnum.LOCAL.getValue()));

            if (!localPrefixAlreadyMatched) {
                List<PrefixInfo> finalMatchedPrefixes = matchedPrefixes;
                allRelevantPrefixes.stream()
                    .filter(pi -> pi.getTelephonyTypeId().equals(TelephonyTypeEnum.LOCAL.getValue()) &&
                                 (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalNumberForLookup.startsWith(pi.getPrefixCode())) &&
                                 finalNumberForLookup.length() >= (pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0)
                           )
                    .forEach(localPrefixInfo -> {
                        if (!finalMatchedPrefixes.stream().anyMatch(mp -> mp.getPrefixId().equals(localPrefixInfo.getPrefixId()))) {
                             finalMatchedPrefixes.add(localPrefixInfo);
                             log.debug("Added LOCAL prefix as fallback/additional for non-trunk call: {}", localPrefixInfo);
                        }
                    });
            }
        }
        
        if (hintedTelephonyTypeIdFromTransform != null && !matchedPrefixes.isEmpty()) {
            Long finalHintedTelephonyTypeIdFromTransform = hintedTelephonyTypeIdFromTransform;
            List<PrefixInfo> hintedTypeMatches = matchedPrefixes.stream()
                .filter(pi -> pi.getTelephonyTypeId().equals(finalHintedTelephonyTypeIdFromTransform) &&
                              (pi.getPrefixCode() == null || pi.getPrefixCode().isEmpty() || finalNumberForLookup.startsWith(pi.getPrefixCode())))
                .collect(Collectors.toList());
            if (!hintedTypeMatches.isEmpty()) {
                log.debug("Prioritizing {} matches for hinted telephony type {} from transformation.", hintedTypeMatches.size(), hintedTelephonyTypeIdFromTransform);
                matchedPrefixes = hintedTypeMatches;
            } else {
                log.debug("No matching prefixes found for hinted telephony type {} from transformation. Using original matches.", hintedTelephonyTypeIdFromTransform);
            }
        }

        matchedPrefixes.sort(Comparator
                .comparing((PrefixInfo pi) -> pi.getPrefixCode() != null ? pi.getPrefixCode().length() : 0, Comparator.reverseOrder())
                .thenComparing((PrefixInfo pi) -> pi.getTelephonyTypeMinLength() != null ? pi.getTelephonyTypeMinLength() : 0, Comparator.reverseOrder()));

        log.debug("Final sorted matched prefixes ({}): {}", matchedPrefixes.size(), matchedPrefixes);
        return matchedPrefixes;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getInternalTelephonyTypePrefixes(Long originCountryId) {
        List<Long> internalTypeIds = telephonyTypeLookupService.getInternalTypeIds();
        if (internalTypeIds.isEmpty()) {
            log.debug("No internal telephony type IDs defined.");
            return Collections.emptyMap();
        }

        String queryStr = "SELECT p.code, p.telephony_type_id " +
                          "FROM prefix p JOIN operator o ON p.operator_id = o.id " +
                          "WHERE p.active = true AND o.active = true AND o.origin_country_id = :originCountryId " +
                          "AND p.telephony_type_id IN (:internalTypeIds) AND p.code IS NOT NULL AND p.code != '' " +
                          "ORDER BY LENGTH(p.code) DESC, p.code DESC";

        jakarta.persistence.Query nativeQuery = entityManager.createNativeQuery(queryStr, Tuple.class);
        nativeQuery.setParameter("originCountryId", originCountryId);
        nativeQuery.setParameter("internalTypeIds", internalTypeIds);

        List<Tuple> results = nativeQuery.getResultList();
        Map<String, Long> internalPrefixMap = new TreeMap<>(Collections.reverseOrder());


        for (Tuple row : results) {
            String prefixCode = row.get("code", String.class);
            Long telephonyTypeId = row.get("telephony_type_id", Number.class).longValue();
            internalPrefixMap.put(prefixCode, telephonyTypeId);
        }
        log.debug("Loaded {} internal telephony type prefixes for country {}: {}", internalPrefixMap.size(), originCountryId, internalPrefixMap);
        return internalPrefixMap;
    }
}

```

**Explanation of Changes:**

1.  **`TariffCalculationService.java`**:
    *   In `calculateTariffs()`:
        *   The `numberForTariffing` is now explicitly set based on `cdrData.getCallDirection()`.
            *   For `INCOMING`, it uses `cdrData.getFinalCalledPartyNumber()` (the external caller's number).
            *   For `OUTGOING`, it uses `cdrData.getEffectiveDestinationNumber()` (the number dialed by our user, potentially after transformations).
        *   This `numberForTariffing` is then used to derive `initialNumberForPrefixLookup` and subsequently passed to `attemptTariffing`. This ensures that prefix lookups and destination lookups for tariffing are done using the correct number based on call direction.

2.  **`CallRecordPersistenceService.java`**:
    *   In `mapCdrDataToCallRecord()`:
        *   A conditional block based on `cdrData.getCallDirection()` now correctly maps fields to `CallRecord` to align with PHP's `ACUMTOTAL` semantics for incoming vs. outgoing calls:
            *   **Incoming:**
                *   `callRecord.setDial()` gets `cdrData.getFinalCalledPartyNumber()` (external number).
                *   `callRecord.setDestinationPhone()` gets `cdrData.getFinalCalledPartyNumber()` (external number, as PHP's `ACUMTOTAL_TELEFONO_DESTINO` is also the external number for incoming after `buscarOrigen` modifications).
                *   `callRecord.setEmployeeExtension()` gets `cdrData.getCallingPartyNumber()` (our internal extension).
            *   **Outgoing:**
                *   `callRecord.setDial()` gets `cdrData.getEffectiveDestinationNumber()` (interpreted/cleaned dialed number).
                *   `callRecord.setDestinationPhone()` gets `cdrData.getFinalCalledPartyNumber()` (original dialed number from CDR, possibly modified by parser).
                *   `callRecord.setEmployeeExtension()` gets `cdrData.getCallingPartyNumber()` (our internal extension).
        *   The `cdr_string` field was removed from the mapping as it's not present in the `CallRecord` entity.
        *   `fileInfoId` mapping changed to `Long` to match the entity.

3.  **`PrefixLookupService.java`**:
    *   In `findMatchingPrefixes()` for non-trunk calls:
        *   After attempting to find the `bestMatchPrefixCode` (longest explicit operator prefix), if `bestMatchPrefixCode` is still `null`, the logic now iterates through `allRelevantPrefixes` again.
        *   It adds `PrefixInfo` objects to `matchedPrefixes` if their `prefixCode` is null or empty, AND if the `finalNumberForLookup` meets the `telephonyTypeMinLength` for that prefix's telephony type.
        *   This allows types like "International" or "National" (which might not have explicit leading operator digits in the `prefix.code` table but are identified by their overall structure and `telephony_type_id`) to be considered when no explicit operator prefix is matched in the dialed string.
        *   It also considers the `hintedTelephonyTypeIdFromTransform` when filtering these empty-code prefixes.

These changes should align the Java system's output more closely with the PHP system's logic, specifically addressing the `telephony_type_id` and the `dial`/`destination_phone` discrepancies for incoming calls. The core idea is to ensure that tariffing for incoming calls uses the external number and that the final `CallRecord` fields reflect PHP's semantic choices for `ACUMTOTAL`.