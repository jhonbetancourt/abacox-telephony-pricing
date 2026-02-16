package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface ConferenceCandidateProjection {
    Long getCallRecordId();

    LocalDateTime getServiceDate();

    String getEmployeeExtension();

    Integer getDuration();

    String getDialedNumber();

    BigDecimal getBilledAmount();

    String getEmployeeAuthCode();

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

    String getTransferKey();

    Long getOrganizerId();

    String getOrganizerName();

    Long getOrganizerSubdivisionId();

    String getOrganizerSubdivisionName();

    Integer getTransferCause();
}
