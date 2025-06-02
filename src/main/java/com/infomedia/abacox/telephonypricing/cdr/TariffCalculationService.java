// File: com/infomedia/abacox/telephonypricing/cdr/TariffCalculationService.java
package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
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
                cdrData.setPricePerMinute(BigDecimal.ZERO);
                cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                return;
            }
        }

        if (cdrData.getCallDirection() == CallDirection.INCOMING) {
            log.debug("Incoming call. TelephonyType (source): {}, Operator (source): {}, Indicator (source): {}",
                    cdrData.getTelephonyTypeId(), cdrData.getOperatorId(), cdrData.getIndicatorId());

            if (cdrData.getTelephonyTypeId() != null && cdrData.getTelephonyTypeId() > 0) {
                cdrData.setPricePerMinute(BigDecimal.ZERO);
                cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
                cdrData.setPriceIncludesVat(false);
                cdrData.setVatRate(BigDecimal.ZERO);
                applySpecialRatesAndRules(cdrData, commLocation, null, null);
                cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
                log.info("Final tariff for INCOMING CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
                         cdrData.getCtlHash(), cdrData.getBilledAmount(), cdrData.getPricePerMinute(), cdrData.getTelephonyTypeName());
                return;
            } else {
                log.warn("Incoming call, but TelephonyType for source is not set. Setting tariff to 0.");
                cdrData.setPricePerMinute(BigDecimal.ZERO);
                cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
                cdrData.setBilledAmount(BigDecimal.ZERO);
                return;
            }
        }

        // --- Logic for OUTGOING and INTERNAL calls ---
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

        // If we reach here for an OUTGOING call, and telephonyTypeId is still null/unknown,
        // it means it wasn't special, wasn't internal IP, and CallTypeDeterminationService didn't set a specific type.
        // This is where we must perform the equivalent of PHP's `evaluarDestino_pos`.
        log.debug("Outgoing/Non-IP-Internal call. Current TelephonyType: {}. Effective Dest: {}",
                  cdrData.getTelephonyTypeId(), cdrData.getEffectiveDestinationNumber());

        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
            log.debug("Trunk lookup for '{}': {}", cdrData.getDestDeviceName(), trunkInfoOpt.isPresent() ? "Found" : "Not Found");
        }

        String numberForTariffing = cdrData.getOriginalDialNumberForTariffing();
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();

        if (trunkInfoOpt.isEmpty()) {
            numberForTariffing = CdrUtil.cleanPhoneNumber(numberForTariffing, pbxPrefixes, true);
            log.debug("Non-trunk call. Number for tariffing (after PBX clean): {}", numberForTariffing);
        } else {
            if (trunkInfoOpt.get().noPbxPrefix == null || !trunkInfoOpt.get().noPbxPrefix) {
                numberForTariffing = CdrUtil.cleanPhoneNumber(numberForTariffing, pbxPrefixes, true);
                log.debug("Trunk call, noPbxPrefix is false/null. Number for tariffing (after PBX clean): {}", numberForTariffing);
            } else {
                log.debug("Trunk call, noPbxPrefix is true. Number for tariffing (as-is): {}", numberForTariffing);
            }
        }
        log.debug("Final number used for tariffing attempt 1: {}", numberForTariffing);

        TariffingAttemptResult attempt1 = attemptTariffing(numberForTariffing, cdrData, commLocation, trunkInfoOpt, pbxPrefixes, false);

        if (trunkInfoOpt.isPresent() && isTariffResultInvalidOrAssumed(attempt1) && !cdrData.isNormalizedTariffApplied()) {
            log.warn("Trunk call tariffing attempt 1 resulted in invalid/assumed. Attempting normalization for: {}", cdrData.getOriginalDialNumberForTariffing());
            String normalizedNumberForLookup = CdrUtil.cleanPhoneNumber(cdrData.getOriginalDialNumberForTariffing(), pbxPrefixes, true);
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
                    log.info("Using normalized tariffing result for trunk call. Original number: {}", cdrData.getOriginalDialNumberForTariffing());
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

        log.info("Final tariff calculation for OUTGOING/INTERNAL CDR: {}. Billed Amount: {}, Price/Min: {}, Type: {}",
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
            } else { // Not a trunk call, or it's a normalization attempt (treated as non-trunk)
                stripOperatorPrefixForDestLookup = true; // Standard behavior for non-trunk is to strip operator prefix
            }

            if (stripOperatorPrefixForDestLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForLookup.startsWith(prefixInfo.getPrefixCode())) {
                numberAfterOperatorPrefixStrip = numberForLookup.substring(prefixInfo.getPrefixCode().length());
                operatorPrefixToPassToFindDest = null; // Prefix is now stripped
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
                (operatorPrefixToPassToFindDest == null), // True if op prefix was stripped above
                operatorPrefixToPassToFindDest
            );
            log.trace("Destination lookup for '{}' (type {}): {}", numberAfterOperatorPrefixStrip, prefixInfo.telephonyTypeId, destInfoOpt.isPresent() ? destInfoOpt.get() : "Not Found");

            if (destInfoOpt.isPresent()) {
                 DestinationInfo currentDestInfo = destInfoOpt.get();
                 if (result.bestDestInfo == null ||
                     (currentDestInfo.isApproximateMatch() == result.bestDestInfo.isApproximateMatch() && currentDestInfo.getPaddedSeriesRangeSize() < result.bestDestInfo.getPaddedSeriesRangeSize()) ||
                     (!currentDestInfo.isApproximateMatch() && result.bestDestInfo.isApproximateMatch())
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

            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(result.bestDestInfo.getNdc(), commLocation.getIndicatorId(), result.bestDestInfo.getIndicatorId())) {
                log.debug("Call to {} identified as LOCAL_EXTENDED.", result.bestDestInfo.getDestinationDescription());
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
                PrefixInfo localExtPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId());
                if (localExtPrefixInfo != null) {
                    cdrData.setOperatorId(localExtPrefixInfo.getOperatorId());
                    cdrData.setOperatorName(localExtPrefixInfo.getOperatorName());
                    result.bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId(); // Update prefixId for tariff lookup
                }
            }

            // Set base tariff values from the chosen prefix/destination context
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
            // Initial price defaults to current price before special/rules
            cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
            cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

            // Apply trunk-specific overrides if applicable
            if (trunkInfoOpt.isPresent() && !cdrData.isNormalizedTariffApplied()) {
                applyTrunkSpecificRates(cdrData, trunkInfoOpt.get(), commLocation);
            }

            // Apply special rates and rules (which might further modify pricePerMinute, etc.)
            applySpecialRatesAndRules(cdrData, commLocation, result.bestDestInfo, trunkInfoOpt.orElse(null));

            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        } else {
            // PHP: if ($arr_destino === false) { ... $tipotele_id = 0; ... }
            // If no destination info, but a prefix was matched (e.g. only operator prefix, no specific city/series)
            // PHP might still assign a telephony type based on the prefix alone if it's unambiguous.
            // Here, if bestDestInfo is null, it means no series/indicator matched.
            // We need to check if bestPrefixInfo is still valid to assign a type.
            if (result.bestPrefixInfo != null) {
                log.warn("No specific destination found for '{}', but prefix '{}' (type {}) matched. Tariffing as assumed for this type.",
                         result.finalNumberUsedForDestLookup, result.bestPrefixInfo.getPrefixCode(), result.bestPrefixInfo.getTelephonyTypeId());
                cdrData.setTelephonyTypeId(result.bestPrefixInfo.getTelephonyTypeId());
                cdrData.setTelephonyTypeName(result.bestPrefixInfo.getTelephonyTypeName() + " (" + appConfigService.getAssumedText() + ")");
                cdrData.setOperatorId(result.bestPrefixInfo.getOperatorId());
                cdrData.setOperatorName(result.bestPrefixInfo.getOperatorName());
                cdrData.setIndicatorId(null); // No specific destination indicator
                cdrData.setDestinationCityName(cdrData.getTelephonyTypeName()); // Use type name as description

                TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(
                    result.bestPrefixInfo.getPrefixId(),
                    null, // No destination indicator
                    commLocation.getId(),
                    commLocation.getIndicatorId()
                );
                cdrData.setPricePerMinute(baseTariff.getRateValue());
                cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
                cdrData.setVatRate(baseTariff.getVatRate());
                cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

                if (trunkInfoOpt.isPresent() && !cdrData.isNormalizedTariffApplied()) {
                    applyTrunkSpecificRates(cdrData, trunkInfoOpt.get(), commLocation);
                }
                applySpecialRatesAndRules(cdrData, commLocation, null, trunkInfoOpt.orElse(null));
                cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));

            } else {
                // Fallback to LOCAL if number length matches LOCAL config, as per PHP's final fallback
                TelephonyTypeConfig localConfig = telephonyTypeLookupService.getTelephonyTypeConfig(TelephonyTypeEnum.LOCAL.getValue(), commLocation.getIndicator().getOriginCountryId());
                if (localConfig != null &&
                    result.finalNumberUsedForDestLookup.length() >= localConfig.getMinValue() &&
                    result.finalNumberUsedForDestLookup.length() <= localConfig.getMaxValue()) {
                    log.warn("No prefix/destination match for '{}'. Falling back to LOCAL type based on length.", result.finalNumberUsedForDestLookup);
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL.getValue());
                    cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL.getValue()) + " (Fallback)");
                    // Get default operator/prefix for LOCAL
                    PrefixInfo localPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId()); // Using this as it fetches a prefix for a local-like type
                    if (localPrefixInfo != null) {
                        cdrData.setOperatorId(localPrefixInfo.getOperatorId());
                        cdrData.setOperatorName(localPrefixInfo.getOperatorName());
                        TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(localPrefixInfo.getPrefixId(), null, commLocation.getId(), commLocation.getIndicatorId());
                        cdrData.setPricePerMinute(baseTariff.getRateValue());
                        cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
                        cdrData.setVatRate(baseTariff.getVatRate());
                    } else {
                        cdrData.setPricePerMinute(BigDecimal.ZERO);
                    }
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
                    cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());
                    applySpecialRatesAndRules(cdrData, commLocation, null, trunkInfoOpt.orElse(null)); // Trunk info might still be relevant if it was a trunk call that failed to find specific prefix
                    cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
                } else {
                    log.warn("Could not determine destination or tariff for: {} (original number for tariffing: {}). Marking as ERROR.", result.finalNumberUsedForDestLookup, result.finalNumberUsedForDestLookup);
                    cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
                    cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.ERRORS.getValue()));
                    cdrData.setBilledAmount(BigDecimal.ZERO);
                    cdrData.setPricePerMinute(BigDecimal.ZERO);
                    cdrData.setInitialPricePerMinute(BigDecimal.ZERO);
                }
            }
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
            // Initial price is already set from baseTariff, no need to set it again here unless logic changes
            // cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute());
            // cdrData.setInitialPriceIncludesVat(cdrData.isPriceIncludesVat());

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
        // Ensure initial price is set if not already
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
                destinationInfo != null ? destinationInfo.getBandId() : null
        );

        if (specialRateOpt.isPresent()) {
            SpecialRateInfo sr = specialRateOpt.get();
            log.info("Applying special rate: {}", sr);
            // PHP: Guardar_ValorInicial($infovalor); (already handled by setting initialPrice above)

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
                cdrData.setPriceIncludesVat(false); // After discount, it's net value
                cdrData.setSpecialRateDiscountPercentage(discountPercentage);
            }
            cdrData.setVatRate(sr.vatRate); // VAT rate from the prefix associated with the special rate's type/op
            cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Special Rate)");
        }

        // Apply trunk rules only if it's an outgoing/internal call and a trunk was involved
        // For incoming, trunkInfo would be null.
        if (cdrData.getCallDirection() != CallDirection.INCOMING && cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
             Optional<AppliedTrunkRuleInfo> ruleInfoOpt =
                trunkRuleLookupService.getAppliedTrunkRule(
                    cdrData.getDestDeviceName(),
                    cdrData.getTelephonyTypeId(),
                    cdrData.getIndicatorId(), // This is destination indicator for outgoing
                    commLocation.getIndicatorId() // This is origin indicator
                );
            if (ruleInfoOpt.isPresent()) {
                AppliedTrunkRuleInfo rule = ruleInfoOpt.get();
                log.info("Applying trunk rule: {}", rule);
                // PHP: Guardar_ValorInicial($infovalor); (already handled by setting initialPrice above)

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
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1; // PHP: Minimo 1 minuto
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