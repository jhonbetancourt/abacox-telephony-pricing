package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.view.CorporateReportView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CorporateReportViewRepository extends JpaRepository<CorporateReportView, Long>, JpaSpecificationExecutor<CorporateReportView> {
}