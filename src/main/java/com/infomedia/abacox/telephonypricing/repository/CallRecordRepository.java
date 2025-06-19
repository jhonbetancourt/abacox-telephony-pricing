package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.entity.CallRecord;
import com.infomedia.abacox.telephonypricing.db.projection.EmployeeActivityReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CallRecordRepository extends JpaRepository<CallRecord, Long>, JpaSpecificationExecutor<CallRecord> {


}