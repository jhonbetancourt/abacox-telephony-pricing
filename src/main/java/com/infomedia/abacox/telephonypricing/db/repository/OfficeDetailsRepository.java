package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.OfficeDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OfficeDetailsRepository extends JpaRepository<OfficeDetails, Long>, JpaSpecificationExecutor<OfficeDetails> {
}