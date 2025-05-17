package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.CommunicationLocation;
import com.infomedia.abacox.telephonypricing.entity.SpecialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class SpecialServicePricer {

    private final PricingRuleLookupService pricingRuleLookupService;
    private final CdrConfigService cdrConfigService;
    private final CallRecordUpdater callRecordUpdater;

    public boolean priceAsSpecialService(CallRecord callRecord, String dialedNumber, CommunicationLocation commLocation, Long originCountryId) {
        List<String> pbxPrefixes = cdrConfigService.getPbxOutputPrefixes(commLocation);
        String numberForSpecialLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, pbxPrefixes, false);

        if (numberForSpecialLookup.isEmpty() && !pbxPrefixes.isEmpty()) {
            return false;
        }
        if (numberForSpecialLookup.isEmpty() && pbxPrefixes.isEmpty()) {
            numberForSpecialLookup = CdrHelper.cleanAndStripPhoneNumber(dialedNumber, null, true);
        }

        Optional<SpecialService> specialServiceOpt = pricingRuleLookupService.findSpecialService(
                numberForSpecialLookup,
                commLocation.getIndicatorId(),
                originCountryId
        );

        if (specialServiceOpt.isPresent()) {
            SpecialService ss = specialServiceOpt.get();
            callRecordUpdater.setTelephonyTypeAndOperator(callRecord, TelephonyTypeConstants.NUMEROS_ESPECIALES, originCountryId, ss.getIndicator());

            BigDecimal value = ss.getValue() != null ? ss.getValue() : BigDecimal.ZERO;
            BigDecimal vatAmount = ss.getVatAmount() != null ? ss.getVatAmount() : BigDecimal.ZERO;
            BigDecimal billedAmount;
            if (ss.getVatIncluded() != null && ss.getVatIncluded()) {
                billedAmount = value;
            } else {
                billedAmount = value.add(vatAmount);
            }
            callRecord.setBilledAmount(billedAmount.setScale(2, RoundingMode.HALF_UP));
            callRecord.setPricePerMinute(value.setScale(4, RoundingMode.HALF_UP));
            callRecord.setInitialPrice(value.setScale(4, RoundingMode.HALF_UP));
            log.debug("Call identified as Special Service: {}", ss.getDescription());
            return true;
        }
        return false;
    }
}
