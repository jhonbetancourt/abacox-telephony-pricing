package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.view.ConferenceCallsReportView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ConferenceCallsReportViewRepository extends JpaRepository<ConferenceCallsReportView, Long>, JpaSpecificationExecutor<ConferenceCallsReportView> {
}