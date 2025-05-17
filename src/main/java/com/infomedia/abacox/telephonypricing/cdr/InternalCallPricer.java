package com.infomedia.abacox.telephonypricing.cdr;

import com.infomedia.abacox.telephonypricing.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class InternalCallPricer {

    private final EmployeeLookupService employeeLookupService;
    private final CoreLookupService coreLookupService;
    private final NumberRoutingLookupService numberRoutingLookupService;
    private final CallRecordUpdater callRecordUpdater;

    public boolean priceAsInternalCall(CallRecord callRecord, RawCiscoCdrData rawData, String effectiveDialedNumber,
                                       CommunicationLocation commLocation, InternalExtensionLimitsDto limits, Long originCountryId) {

        InternalCallTypeResultDto internalCallResult = evaluateInternalCallType(
                rawData.getEffectiveOriginatingNumber(),
                effectiveDialedNumber,
                commLocation, limits, originCountryId, rawData.getDateTimeOrigination()
        );

        if (internalCallResult != null) {
            if (internalCallResult.isIgnoreCall()) {
                log.info("Ignoring internal call as per logic (e.g., inter-plant global): {}", callRecord.getCdrHash());
                callRecord.setTelephonyTypeId(TelephonyTypeConstants.SIN_CONSUMO);
                coreLookupService.findTelephonyTypeById(TelephonyTypeConstants.SIN_CONSUMO).ifPresent(callRecord::setTelephonyType);
                return true; // Handled (ignored)
            }
            callRecordUpdater.setTelephonyTypeAndOperator(callRecord, internalCallResult.getTelephonyType().getId(), originCountryId, internalCallResult.getDestinationIndicator());
            callRecord.setOperator(internalCallResult.getOperator());
            callRecord.setOperatorId(internalCallResult.getOperator() != null ? internalCallResult.getOperator().getId() : null);
            callRecord.setDestinationEmployee(internalCallResult.getDestinationEmployee());
            callRecord.setDestinationEmployeeId(internalCallResult.getDestinationEmployee() != null ? internalCallResult.getDestinationEmployee().getId() : null);

            if (internalCallResult.isIncomingInternal() && !callRecord.isIncoming()) {
                callRecord.setIncoming(true);
                String tempTrunk = callRecord.getTrunk();
                callRecord.setTrunk(callRecord.getInitialTrunk());
                callRecord.setInitialTrunk(tempTrunk);
            }

            Optional<Prefix> internalPrefixOpt = numberRoutingLookupService.findInternalPrefixForType(internalCallResult.getTelephonyType().getId(), originCountryId);
            if (internalPrefixOpt.isPresent()) {
                Prefix internalPrefix = internalPrefixOpt.get();
                BigDecimal price = internalPrefix.getBaseValue() != null ? internalPrefix.getBaseValue() : BigDecimal.ZERO;
                BigDecimal vatRate = internalPrefix.getVatValue() != null ? internalPrefix.getVatValue() : BigDecimal.ZERO;
                boolean vatIncluded = internalPrefix.isVatIncluded();

                BigDecimal priceWithVat = price;
                if (!vatIncluded && vatRate.compareTo(BigDecimal.ZERO) > 0) {
                    priceWithVat = price.multiply(BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))).setScale(4, RoundingMode.HALF_UP);
                }
                callRecord.setPricePerMinute(priceWithVat);
                callRecord.setInitialPrice(price);

                int durationInSeconds = callRecord.getDuration() != null ? callRecord.getDuration() : 0;
                long billedUnits = CdrHelper.duracionMinuto(durationInSeconds, false);
                callRecord.setBilledAmount(priceWithVat.multiply(BigDecimal.valueOf(billedUnits)).setScale(2, RoundingMode.HALF_UP));

            } else {
                callRecord.setBilledAmount(BigDecimal.ZERO);
                callRecord.setPricePerMinute(BigDecimal.ZERO);
                callRecord.setInitialPrice(BigDecimal.ZERO);
            }
            log.debug("Call identified as Internal (Type: {})", internalCallResult.getTelephonyType().getName());
            return true; // Handled
        }
        return false; // Not an internal call
    }

    private InternalCallTypeResultDto evaluateInternalCallType(String originExtension, String destinationExtension,
                                                               CommunicationLocation currentCommLocation,
                                                               InternalExtensionLimitsDto limits, Long currentOriginCountryId,
                                                               LocalDateTime callDateTime) {
        // This is a simplified version. PHP's logic is much more nuanced.
        Optional<Employee> originEmpOpt = employeeLookupService.findEmployeeByExtension(originExtension, currentCommLocation.getId(), limits);
        Optional<Employee> destEmpOpt = employeeLookupService.findEmployeeByExtension(destinationExtension, currentCommLocation.getId(), limits);

        if (originEmpOpt.isPresent() && destEmpOpt.isPresent()) {
            TelephonyType tt = coreLookupService.findTelephonyTypeById(TelephonyTypeConstants.INTERNA).orElse(null);
            Operator op = coreLookupService.findInternalOperatorByTelephonyType(TelephonyTypeConstants.INTERNA, currentOriginCountryId).orElse(null);

            Indicator destIndicator = null;
            if (destEmpOpt.get().getCommunicationLocation() != null) { // Employee might have a commLocation
                destIndicator = destEmpOpt.get().getCommunicationLocation().getIndicator();
            } else if (destEmpOpt.get().getCommunicationLocationId() != null) { // Or just an ID
                Optional<CommunicationLocation> clOpt = coreLookupService.findCommunicationLocationById(destEmpOpt.get().getCommunicationLocationId());
                if (clOpt.isPresent()) destIndicator = clOpt.get().getIndicator();
            }


            return InternalCallTypeResultDto.builder()
                    .telephonyType(tt)
                    .operator(op)
                    .destinationIndicator(destIndicator)
                    .destinationEmployee(destEmpOpt.get())
                    .ignoreCall(false)
                    .isIncomingInternal(false)
                    .build();
        }
        return null;
    }
}
