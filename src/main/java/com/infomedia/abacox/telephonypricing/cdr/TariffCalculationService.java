package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;


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
        if (cdrData.getDurationSeconds() < appConfigService.getMinCallDurationForTariffing() &&
            (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() != TelephonyTypeEnum.SPECIAL_SERVICES.getValue())) {
            cdrData.setBilledAmount(BigDecimal.ZERO);
            if (cdrData.getTelephonyTypeId() == null || cdrData.getTelephonyTypeId() == 0L || cdrData.getTelephonyTypeId() == TelephonyTypeEnum.UNKNOWN.getValue()){
                 cdrData.setTelephonyTypeId(TelephonyTypeEnum.NO_CONSUMPTION.getValue());
                 cdrData.setTelephonyTypeName("No Consumption");
            }
            return;
        }
        
        if (cdrData.getTelephonyTypeId() != null && cdrData.getTelephonyTypeId() == TelephonyTypeEnum.SPECIAL_SERVICES.getValue()) {
            SpecialServiceInfo ssi = (SpecialServiceInfo) cdrData.getAdditionalData().get("specialServiceTariff");
            if (ssi != null) {
                cdrData.setPricePerMinute(ssi.value);
                cdrData.setPriceIncludesVat(ssi.vatIncluded);
                cdrData.setVatRate(ssi.vatRate);
                cdrData.setBilledAmount(calculateFinalBilledAmount(cdrData));
                return;
            }
        }

        Optional<TrunkInfo> trunkInfoOpt = Optional.empty();
        if (cdrData.getDestDeviceName() != null && !cdrData.getDestDeviceName().isEmpty()) {
            trunkInfoOpt = trunkLookupService.findTrunkByName(cdrData.getDestDeviceName(), commLocation.getId());
        }

        String effectiveDestination = cdrData.getEffectiveDestinationNumber();
        List<String> pbxPrefixes = commLocation.getPbxPrefix() != null ? Arrays.asList(commLocation.getPbxPrefix().split(",")) : Collections.emptyList();
        
        String numberForTariffing = effectiveDestination;
        if (trunkInfoOpt.isPresent()) {
            TrunkInfo ti = trunkInfoOpt.get();
            if (ti.noPbxPrefix != null && !ti.noPbxPrefix) {
                numberForTariffing = CdrParserUtil.cleanPhoneNumber(effectiveDestination, pbxPrefixes, true);
            }
        } else {
            numberForTariffing = CdrParserUtil.cleanPhoneNumber(effectiveDestination, pbxPrefixes, true);
        }
        cdrData.setEffectiveDestinationNumber(numberForTariffing);

        List<Long> trunkTelephonyTypeIds = null;
        if (trunkInfoOpt.isPresent()) {
            trunkTelephonyTypeIds = trunkInfoOpt.get().getAllowedTelephonyTypeIds();
        }

        List<PrefixInfo> prefixes = prefixLookupService.findMatchingPrefixes(
            numberForTariffing,
            commLocation.getIndicator().getOriginCountryId(),
            trunkInfoOpt.isPresent(),
            trunkTelephonyTypeIds
        );

        DestinationInfo bestDestInfo = null;
        PrefixInfo bestPrefixInfo = null;

        for (PrefixInfo prefixInfo : prefixes) {
            String numberWithoutPrefix = numberForTariffing;
            boolean stripPrefixForLookup = true;

            if (trunkInfoOpt.isPresent()) {
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    trunkInfoOpt.get().id, prefixInfo.telephonyTypeId, prefixInfo.operatorId
                );
                if (rateDetails.isPresent() && rateDetails.get().noPrefix != null) {
                    stripPrefixForLookup = rateDetails.get().noPrefix;
                }
            }

            if (stripPrefixForLookup && prefixInfo.getPrefixCode() != null && !prefixInfo.getPrefixCode().isEmpty() && numberForTariffing.startsWith(prefixInfo.getPrefixCode())) {
                numberWithoutPrefix = numberForTariffing.substring(prefixInfo.getPrefixCode().length());
            }
            
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
                bestDestInfo = destInfoOpt.get();
                bestPrefixInfo = prefixInfo;
                break;
            }
        }

        if (bestDestInfo != null) { // bestPrefixInfo will also be non-null here
            cdrData.setTelephonyTypeId(bestPrefixInfo.telephonyTypeId);
            cdrData.setTelephonyTypeName(bestPrefixInfo.telephonyTypeName);
            cdrData.setOperatorId(bestPrefixInfo.operatorId);
            cdrData.setOperatorName(bestPrefixInfo.operatorName);
            cdrData.setIndicatorId(bestDestInfo.getIndicatorId()); // Corrected: use getter
            cdrData.setDestinationCityName(bestDestInfo.getDestinationDescription()); // Corrected: use getter
            cdrData.setEffectiveDestinationNumber(bestDestInfo.getMatchedPhoneNumber()); // Corrected: use getter

            TariffValue baseTariff = telephonyTypeLookupService.getBaseTariffValue(
                bestPrefixInfo.prefixId,
                bestDestInfo.getIndicatorId(), // Corrected
                commLocation.getId(),
                commLocation.getIndicatorId()
            );
            
            cdrData.setPricePerMinute(baseTariff.getRateValue());
            cdrData.setPriceIncludesVat(baseTariff.isIncludesVat());
            cdrData.setVatRate(baseTariff.getVatRate());

            if (trunkInfoOpt.isPresent()) {
                TrunkInfo ti = trunkInfoOpt.get();
                Optional<TrunkRateDetails> rateDetails = trunkLookupService.getRateDetailsForTrunk(
                    ti.id, cdrData.getTelephonyTypeId(), cdrData.getOperatorId()
                );
                if (rateDetails.isPresent()) {
                    TrunkRateDetails rd = rateDetails.get();
                    cdrData.setPricePerMinute(rd.rateValue);
                    cdrData.setPriceIncludesVat(rd.includesVat);
                    cdrData.setChargeBySecond(rd.seconds > 0); // Corrected: check rd.seconds
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
                    bestDestInfo.getBandId() // Corrected
            );
            
            if (specialRateOpt.isPresent()) {
                SpecialRateInfo sr = specialRateOpt.get();
                if (sr.valueType == 0) {
                    cdrData.setPricePerMinute(sr.rateValue);
                    cdrData.setPriceIncludesVat(sr.includesVat);
                } else {
                    BigDecimal currentRateNoVat = cdrData.isPriceIncludesVat() && cdrData.getVatRate().compareTo(BigDecimal.ZERO) != 0 ?
                        cdrData.getPricePerMinute().divide(BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)), 4, RoundingMode.HALF_UP) :
                        cdrData.getPricePerMinute();
                    
                    BigDecimal adjustmentFactor = BigDecimal.ONE.subtract(sr.rateValue.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                    cdrData.setPricePerMinute(currentRateNoVat.multiply(adjustmentFactor));
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
                    cdrData.setChargeBySecond(rule.seconds>0);
                    
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
            log.warn("Could not determine destination or tariff for: {}", numberForTariffing);
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
        if (cdrData.isChargeBySecond()) {
            billableDurationUnits = cdrData.getDurationSeconds();
        } else {
            billableDurationUnits = (long) Math.ceil((double) cdrData.getDurationSeconds() / 60.0);
            if (billableDurationUnits == 0 && cdrData.getDurationSeconds() > 0) billableDurationUnits = 1;
        }
        
        if (billableDurationUnits == 0) return BigDecimal.ZERO;

        BigDecimal rate = cdrData.getPricePerMinute();
        BigDecimal totalCost = rate.multiply(BigDecimal.valueOf(billableDurationUnits));

        if (!cdrData.isPriceIncludesVat() && cdrData.getVatRate() != null && cdrData.getVatRate().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vatMultiplier = BigDecimal.ONE.add(cdrData.getVatRate().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            totalCost = totalCost.multiply(vatMultiplier);
        }
        
        return totalCost.setScale(4, RoundingMode.HALF_UP);
    }
}