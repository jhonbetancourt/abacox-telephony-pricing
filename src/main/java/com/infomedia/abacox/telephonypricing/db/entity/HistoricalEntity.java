package com.infomedia.abacox.telephonypricing.db.entity;

import java.time.LocalDateTime;

public interface HistoricalEntity {
    Long getId();

    void setId(Long id);

    LocalDateTime getHistorySince();

    void setHistorySince(LocalDateTime historySince);

    Long getHistoryControlId();

    void setHistoryControlId(Long historyControlId);
}
