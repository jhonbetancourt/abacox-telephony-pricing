package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.FailedCallRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FailedCallRecordRepository extends JpaRepository<FailedCallRecord, Long>, JpaSpecificationExecutor<FailedCallRecord> {
}