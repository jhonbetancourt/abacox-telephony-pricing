package com.infomedia.abacox.telephonypricing.db.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Projection interface for conference call group summaries.
 */
public interface ConferenceCallGroupReport {
    String getTransferKey();

    String getDialedNumber();

    Long getConferenceNumber();

    LocalDateTime getConferenceServiceDate();

    Long getParticipantCount();

    BigDecimal getTotalBilled();

    Long getOrganizerId();

    String getOrganizerName();

    Long getOrganizerSubdivisionId();

    String getOrganizerSubdivisionName();
}
