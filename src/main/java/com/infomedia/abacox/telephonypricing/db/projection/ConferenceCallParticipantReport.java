package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Projection interface for conference call participant detail rows.
 */
public interface ConferenceCallParticipantReport {
    Long getCallRecordId();

    LocalDateTime getServiceDate();

    String getEmployeeExtension();

    Integer getDuration();

    Boolean getIsIncoming();

    String getDialedNumber();

    BigDecimal getBilledAmount();

    String getEmployeeAuthCode();

    Integer getTransferCause();

    String getTransferKey();

    Long getEmployeeId();

    String getEmployeeName();

    Long getSubdivisionId();

    String getSubdivisionName();

    Long getOperatorId();

    String getOperatorName();

    Long getTelephonyTypeId();

    String getTelephonyTypeName();

    String getCompanyName();

    Boolean getContactType();

    String getContactName();

    Long getContactOwnerId();
}
