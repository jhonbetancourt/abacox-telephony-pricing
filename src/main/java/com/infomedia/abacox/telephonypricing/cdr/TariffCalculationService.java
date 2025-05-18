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
        // PHP: if ($tiempo <= 0 && $tipotele_id > 0 && $tipotele_id != _TIPOTELE_ERRORES) $tipotele_id = _TIPOTELE_SINCONSUMO;
        if (cdrData.getDurationSeconds() <= appConfigService.getMinCallDurationForTariffing() &&
            cdrData.getTelephonyTypeId() != null &&
            cdrData.getTelephonyTypeId() > 0 && // Must have a type
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.ERRORS.getValue() &&
            cdrData.getTelephonyTypeId() != TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) { // Special services can have 0 duration cost
            cdrData.setBilledAmount(BigDecimal.ZERO);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
            cdrData.setTelephonyTypeName("No Consumption");
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

        String numberForTariffing = cdrData.getEffectiveDestinationNumber(); // Already processed by CallTypeDetermination
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        // PHP's evaluarDestino: if ($existe_troncal === false || ($existe_troncal !== false && $existe_troncal['noprefijopbx']))
        // This means if it's NOT a trunk call, OR if it IS a trunk call AND that trunk is marked as NOPREFIJOPBX,
        // then the number used for prefix lookup is the one cleaned of PBX prefixes.
        // Otherwise (it's a trunk call and NOT NOPREFIJOPBX), the raw (or PBX-rule-transformed) number is used.
        if (trunkInfoOpt.isEmpty() || (trunkInfoOpt.isPresent() && trunkInfoOpt.get().noPbxPrefix != null && trunkInfoOpt.get().noPbxPrefix)) {
            numberForTariffing = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
        } else {
            numberForTariffing = cdrData.getEffectiveDestinationNumber(); // Use as is, PBX prefix might be part of trunk dialing
        }
        // This numberForTariffing will be further transformed by _esCelular_fijo inside findMatchingPrefixes

        List<Long> trunkTelephonyTypeIds = null;
        if (trunkInfoOpt.isPresent()) {
            trunkTelephonyTypeIds = trunkInfoOpt.get().getAllowedTelephonyTypeIds();
        }

        List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
            numberForTariffing, // This is passed to _esCelular_fijo
            commLocation,       // Pass full commLocation
            trunkInfoOpt.isPresent(),
            trunkTelephonyTypeIds
        );

        DestinationInfo bestDestInfo = null;
        PrefixInfo bestPrefixInfo = null;
        String finalNumberUsedForDestLookup = numberForTariffing; // Default

        for (PrefixInfo prefixInfo : prefixes) {
            String numberWithoutPrefix = numberForTariffing; // This is after _esCelular_fijo
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
                prefixInfo.bandsAssociatedCount > 0 // PHP: $bandas_ok
            );

            if (destInfoOpt.isPresent()) {
                bestDestInfo = destInfoOpt.get();
                bestPrefixInfo = prefixInfo;
                break; 
            }
        }
        
        // PHP: Normalization logic if trunk call failed or resulted in "assumed"
        if (trunkInfoOpt.isPresent() && (bestDestInfo == null || bestDestInfo.isApproximateMatch() || (bestDestInfo.getDestinationDescription() != null && bestDestInfo.getDestinationDescription().contains(appConfigService.getAssumedText())))) {
            log.debug("Trunk call destination not definitively found or was assumed. Attempting normalization for: {}", numberForTariffing);
            String normalizedNumber = CdrParserUtil.cleanPhoneNumber(cdrData.getEffectiveDestinationNumber(), pbxPrefixes, true);
            
            List<PrefixInfo> normalizedPrefixes = prefixLookupService.findMatchingPrefixes(
                normalizedNumber, commLocation, false, null // Process as non-trunk
            );
            DestinationInfo normalizedBestDestInfo = null;
            PrefixInfo normalizedBestPrefixInfo = null;
            String finalNormalizedNumberUsedForDestLookup = normalizedNumber;

            for (PrefixInfo normPrefixInfo : normalizedPrefixes) {
                String normNumberWithoutPrefix = normalizedNumber;
                 if (normPrefixInfo.getPrefixCode() != null && !normPrefixInfo.getPrefixCode().isEmpty() && normalizedNumber.startsWith(normPrefixInfo.getPrefixCode())) {
                    normNumberWithoutPrefix = normalizedNumber.substring(normPrefixInfo.getPrefixCode().length());
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
                }
            }

            if (normalizedBestDestInfo != null) {
                // PHP: if (($infovalor['tipotele'] != $infovalor_pos['tipotele'] || $infovalor['indicativo'] != $infovalor_pos['indicativo']) ... )
                // This means if the normalized result is better (not error, not assumed) and different, use it.
                boolean useNormalized = true;
                if (bestDestInfo != null) { // if there was an initial "assumed" result
                    if (Objects.equals(bestPrefixInfo.telephonyTypeId, normalizedBestPrefixInfo.telephonyTypeId) &&
                        Objects.equals(bestDestInfo.getIndicatorId(), normalizedBestDestInfo.getIndicatorId())) {
                        useNormalized = false; // Same result, stick with original trunk-based if it existed
                    }
                }
                if (useNormalized) {
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
            cdrData.setEffectiveDestinationNumber(bestDestInfo.getMatchedPhoneNumber());

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
                    cdrData.setChargeBySecond(rd.seconds > 0);
                    cdrData.setVatRate(telephonyTypeLookupService.getVatForPrefix(rd.telephonyTypeId, rd.operatorId, commLocation.getIndicator().getOriginCountryId()));
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Trunk: " + ti.description + ")");
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
                    bestDestInfo.getBandId()
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                if (sr.valueType == 0) { // Absolute value
                    cdrData.setPricePerMinute(sr.rateValue);
                    cdrData.setPriceIncludesVat(sr.includesVat);
                } else { // Percentage adjustment
                    BigDecimal currentRateNoVat = cdrData.isPriceIncludesVat() && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0 ?
                        cdrData.getPricePerMinute().divide(BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP) :
                        cdrData.getPricePerMinute();
                    
                    BigDecimal discountPercentage = sr.rateValue; // Assuming sr.rateValue is the discount % (e.g., 20 for 20%)
                    BigDecimal discountFactor = BigDecimal.ONE.subtract(discountPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                    cdrData.setPricePerMinute(currentRateNoVat.multiply(discountFactor));
                    // PriceIncludesVat remains same as original before discount, VAT applied on discounted rate
                }
                cdrData.setVatRate(sr.vatRate); // VAT rate from the special rule's context
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
                    cdrData.setChargeBySecond(rule.seconds > 0);
                    
                    if (rule.newTelephonyTypeId != null && rule.newTelephonyTypeId != 0L) {
                        cdrData.setTelephonyTypeId(rule.newTelephonyTypeId);
                        cdrData.setTelephonyTypeName(rule.newTelephonyTypeName);
                    }
                    if (rule.newOperatorId != null && rule.newOperatorId != 0L) {
                        cdrData.setOperatorId(rule.newOperatorId);
                        cdrData.setOperatorName(rule.newOperatorName);
                    }
                    cdrData.setVatRate(rule.vatRate);
                    cdrData.setTelephonyTypeName(cdrData.getTelephonyTypeName() + " (Rule Applied)");
                }
            }
            cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
        } else {
            log.warn("Could not determine destination or tariff for: {} (original: {})", finalNumberUsedForDestLookup, numberForTariffing);
            cdrData.setTelephonyTypeId(TelephonyTypeEnum.ERRORS.getValue());
            cdrData.setTelephonyTypeName("Destination/Tariff Undetermined");
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
            // If rate is per minute, adjust to per second
            ratePerUnit = cdrData.getPricePerMinute().divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
        } else {
            billableDurationUnits = (long) Math.ceil((double) cdrData.getDurationSeconds() / 60.0);
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1; // Min 1 minute if any duration
            ratePerUnit = cdrData.getPricePerMinute();
        }
        
        if (billableDurationUnits == 0) return BigDecimal.ZERO;

        BigDecimal totalCost = ratePerUnit.multiply(BigDecimal.valueOf(billableDurationUnits));

        if (!cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }
        
        return totalCost.setScale(4, RoundingMode.HALF_UP); // Standard 4 decimal places for billing
    }
}