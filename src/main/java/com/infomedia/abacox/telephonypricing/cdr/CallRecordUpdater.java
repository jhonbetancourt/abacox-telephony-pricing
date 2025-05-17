package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.entity.Indicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Log4j2
@RequiredArgsConstructor
public class CallRecordUpdater {

    private final CoreLookupService coreLookupService;

    public void setDefaultErrorTelephonyType(CallRecord callRecord) {
        callRecord.setTelephonyTypeId(TelephonyTypeConstants.ERRORES);
        coreLookupService.findTelephonyTypeById(TelephonyTypeConstants.ERRORES).ifPresent(callRecord::setTelephonyType);
        callRecord.setBilledAmount(BigDecimal.ZERO);
        callRecord.setPricePerMinute(BigDecimal.ZERO);
        callRecord.setInitialPrice(BigDecimal.ZERO);
    }

    public void setTelephonyTypeAndOperator(CallRecord callRecord, Long telephonyTypeId, Long originCountryId, Indicator indicator) {
        coreLookupService.findTelephonyTypeById(telephonyTypeId).ifPresent(tt -> {
            callRecord.setTelephonyType(tt);
            callRecord.setTelephonyTypeId(tt.getId());
        });
        // Only set operator if not already set (e.g. by a trunk rule that changes it)
        if (callRecord.getOperatorId() == null) {
            coreLookupService.findInternalOperatorByTelephonyType(telephonyTypeId, originCountryId)
                .ifPresent(op -> {
                    callRecord.setOperator(op);
                    callRecord.setOperatorId(op.getId());
                });
        }
        if (indicator != null) {
            callRecord.setIndicator(indicator);
            callRecord.setIndicatorId(indicator.getId());
        }
    }
}

