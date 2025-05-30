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
        log.debug("Calculating tariffs for CDR: {}, CommLocation: {}", cdrData.getCtlHash(), commLocation.getDirectory());

        // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) { $tipotele_id = _TIPOTELE_SINCONSUMO; }
        if (cdrData.getDurationSeconds() != null && cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing()) {
            if (cdrData.getTelephonyTypeId() != null &&
                cdrData.getTelephonyTypeId() > 0 && // Not UNKNOWN
                cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue()) {
                log.info("Call duration {}s <= min duration {}s. Setting type to NO_CONSUMPTION.", cdrData.getDurationSeconds(), appConfigService.getMinCallDurationForTariffing());
                cdrData.setBilledAmount(BigDecimal.ZERO);
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.NO_CONSUMPTION.getValue()));
                return; // No further tariffing for NO_CONSUMPTION
            }
        }
        
        // PHP: procesaServespecial logic path
        if (cdrData.getTelephonyTypeId() != null && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            SpecialServiceInfo ssi = cdrData.getSpecialServiceTariff(); // This should have been set by CallTypeDeterminationService
            if (ssi != null) {
                log.debug("Applying special service tariff: {}", ssi);
                cdrData.setPricePerMinute(ssi.value);
                cdrData.setPriceIncludesVat(ssi.vatIncluded);
                cdrData.setVatRate(ssi.vatRate != null ? ssi.vatRate : BigDecimal.ZERO);
                // PHP: $valor_facturado = Calcular_Valor($duracion, $infovalor);
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
        
        // PHP: procesaInterna logic path (for tariffing)
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

        // --- Start of PHP's evaluarDestino logic ---
        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        // PHP: $existe_troncal  = buscarTroncal($info_co, $comubicacion_id, $link);
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
            log.debug("Trunk lookup for '{}': {}", cdrData.getDestDeviceName(), trunkInfoOpt.isPresent() ? "Found" : "Not Found");
        }

        String numberForTariffing = cdrData.getEffectiveDestinationNumber();
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        // PHP: $info_destino_limpio = limpiar_numero($info_destino, $prefijo_salida_pbx, true);
        // PHP: if ($existe_troncal === false) { $telefono = $info_destino_limpio; }
        // This means if it's NOT a trunk call, always use the PBX-cleaned number.
        // If it IS a trunk call, the original number (before PBX cleaning) is used initially,
        // but later, if `noprefijopbx` is false for the trunk, it gets cleaned.
        String initialNumberForPrefixLookup = numberForTariffing;
        if (trunkInfoOpt.isEmpty()) { // Not a trunk call
            initialNumberForPrefixLookup = CdrUtil.cleanPhoneNumber(numberForTariffing, pbxPrefixes, true);
            log.debug("Non-trunk call. Number for prefix lookup (after PBX clean): {}", initialNumberForPrefixLookup);
        } else {
            log.debug("Trunk call. Initial number for prefix lookup (before trunk-specific PBX clean): {}", initialNumberForPrefixLookup);
        }
        
        TariffingAttemptResult attempt1 = attemptTariffing(initialNumberForPrefixLookup, cdrData, commLocation, trunkInfoOpt, pbxPrefixes, false);

        // PHP: if ($existe_troncal !== false && evaluarDestino_novalido($infovalor))
        if (trunkInfoOpt.isPresent() && isTariffResultInvalidOrAssumed(attempt1) && !cdrData.isNormalizedTariffApplied()) {
            log.warn("Trunk call tariffing attempt 1 resulted in invalid/assumed. Attempting normalization for: {}", cdrData.getEffectiveDestinationNumber());
            
            // PHP: if ($existe_troncal['noprefijopbx']) { $prefijo_salida_pbx = ''; }
            List<String> pbxPrefixesForNormalization = pbxPrefixes;
            if (trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix) {
                pbxPrefixesForNormalization = Collections.emptyList();
            }
            String normalizedNumberForLookup = CdrUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixesForNormalization, true);
            log.debug("Normalized number for lookup (treated as non-trunk): {}", normalizedNumberForLookup);
            
            TariffingAttemptResult attempt2 = attemptTariffing(normalizedNumberForLookup, cdrData, commLocation, Optional.empty(), pbxPrefixes, true);

            // PHP: if (($infovalor['tipotele'] != $infovalor_pos['tipotele'] || $infovalor['indicativo'] != $infovalor_pos['indicativo']) && $infovalor_pos['tipotele'] > 0 ...)
            if (attempt2.bestPrefixInfo != null && attempt2.bestPrefixInfo.telephonyTypeId > 0 &&
                attempt2.bestPrefixInfo.telephonyTypeId != TelephonyTypeEnum.ERRORS.getValue() &&
                attempt2.bestDestInfo != null && attempt2.bestDestInfo.getIndicatorId() != null && attempt2.bestDestInfo.getIndicatorId() > 0) {

                boolean useNormalized = true;
                if (attempt1.bestPrefixInfo != null && attempt1.bestDestInfo != null) { // Compare with previous best (even if assumed)
                    if (Objects.equals(attempt1.bestPrefixInfo.telephonyTypeId, attempt2.bestPrefixInfo.telephonyTypeId) &&
                        Objects.equals(attempt1.bestDestInfo.getIndicatorId(), attempt2.bestDestInfo.getIndicatorId())) {
                        useNormalized = false;
                        log.debug("Normalized result is same type/indicator as initial assumed result. Preferring initial.");
                    }
                }
                if (useNormalized) {
                    log.info("Using normalized tariffing result for trunk call. Original number: {}", cdrData.getEffectiveDestinationNumber());
                    applyTariffingResult(cdrData, attempt2, commLocation, Optional.empty()); // Apply as if non-trunk
                    cdrData.setNormalizedTariffApplied(true);
                } else {
                    applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt); // Re-apply original attempt
                }
            } else {
                log.warn("Normalization did not yield a valid result. Sticking with initial attempt.");
                applyTariffingResult(cdrData, attempt1, commLocation, trunkInfoOpt); // Re-apply original attempt
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
        if (trunkInfoOpt.isPresent() && !isNormalizationAttempt) { // For normalization, we treat as non-trunk
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

            // PHP: if ($existe_troncal !== false) { $reducir = $existe_troncal['noprefijo']; ... }
            // PHP: if ($reducir) { $tipotele_arr['tipotele_min'] -= $lprefijo; ... }
            boolean stripOperatorPrefixForDestLookup = false;
            if (trunkInfoOpt.isPresent() && !isNormalizationAttempt) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, prefixInfo.telephonyTypeId, prefixInfo.operatorId
                );
                if (rateDetails.isPresent() && rateDetails.get().noPrefix != null) {
                    stripOperatorPrefixForDestLookup = rateDetails.get().noPrefix;
                }
            } else { // Not a trunk call (or normalization attempt)
                stripOperatorPrefixForDestLookup = true; // Default to stripping for non-trunk
            }

            if (stripOperatorPrefixForDestLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForLookup.startsWith(prefixInfo.getPrefixCode())) {
                numberAfterOperatorPrefixStrip = numberForLookup.substring(prefixInfo.getPrefixCode().length());
                operatorPrefixStrippedThisIteration = true;
                operatorPrefixToPassToFindDest = null; // Already stripped
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
                     (!result.bestDestInfo.isApproximateMatch() && currentDestInfo.isApproximateMatch()) ||
                     (currentDestInfo.isApproximateMatch() == result.bestDestInfo.isApproximateMatch() &&
                         currentDestInfo.getSeriesRangeSize() < result.bestDestInfo.getSeriesRangeSize()) ||
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
        result.finalNumberUsedForDestLookup = numberForLookup; // Store the number that led to this result
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
                String operatorNameFromIndicator = prefixLookupService.findOperatorNameById(result.bestDestInfo.getOperatorId());
                cdrData.setOperatorName(operatorNameFromIndicator != null ? operatorNameFromIndicator : result.bestPrefixInfo.operatorName);
            } else {
                cdrData.setOperatorId(result.bestPrefixInfo.operatorId);
                cdrData.setOperatorName(result.bestPrefixInfo.operatorName);
            }

            cdrData.setIndicatorId(result.bestDestInfo.getIndicatorId());
            cdrData.setDestinationCityName(result.bestDestInfo.getDestinationDescription());
            cdrData.setEffectiveDestinationNumber(result.finalNumberUsedForDestLookup); // Use the number that led to this match

            if (cdrData.getTelephonyTypeId() == TelephonyTypeEnum.LOCAL.getValue() &&
                indicatorLookupService.isLocalExtended(result.bestDestInfo.getNdc(), commLocation.getIndicatorId(), result.bestDestInfo.getIndicatorId())) {
                log.debug("Call to {} identified as LOCAL_EXTENDED.", result.bestDestInfo.getDestinationDescription());
                cdrData.setTelephonyTypeId(TelephonyTypeEnum.LOCAL_EXTENDED.getValue());
                cdrData.setTelephonyTypeName(telephonyTypeLookupService.getTelephonyTypeName(TelephonyTypeEnum.LOCAL_EXTENDED.getValue()));
                PrefixInfo localExtPrefixInfo = telephonyTypeLookupService.getPrefixInfoForLocalExtended(commLocation.getIndicator().getOriginCountryId());
                if (localExtPrefixInfo != null) {
                    cdrData.setOperatorId(localExtPrefixInfo.getOperatorId());
                    cdrData.setOperatorName(localExtPrefixInfo.getOperatorName());
                    result.bestPrefixInfo.prefixId = localExtPrefixInfo.getPrefixId();
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

            // Apply trunk-specific rates only if this is not a normalization attempt that forced non-trunk logic
            if (trunkInfoOpt.isPresent() && !cdrData.isNormalizedTariffApplied()) {
                TrunkInfo ti = trunkInfoOpt.get();
                log.debug("Applying trunk-specific rates for trunk: {}", ti.description);
                Optional<TrunkRateDetails> rateDetailsOpt = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetailsOpt.isPresent()) {
                    TrunkRateDetails rd = rateDetailsOpt.get();
                    log.debug("Found specific rate details for trunk: {}", rd);
                    cdrData.setInitialPricePerMinute(cdrData.getPricePerMinute()); // Store current as initial
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
                    result.bestDestInfo.getBandId()
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                log.info("Applying special rate: {}", sr);
                // Store current as initial *before* applying special rate, if not already set
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
            // Ensure pricePerMinute is not null before division
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
            // PHP: $duracion_minuto = Duracion_Minuto($duracion, $ensegundos);
            // Duracion_Minuto: ceil($duracion / 60); if ($duracion_minuto == 0 && $duracion > 0) $duracion_minuto = 1;
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

        // PHP: if (!$infovalor['valor_minuto_iva']) { $valor_minuto = $valor_minuto * $valor_iva; }
        if (!cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
            log.debug("Applied VAT ({}%). Cost after VAT: {}", cdrData.getVatRate(), totalCost);
        }
        
        return totalCost.setScale(4, RoundingMode.HALF_UP); // PHP doesn't specify scale, 4 is common for currency
    }

}