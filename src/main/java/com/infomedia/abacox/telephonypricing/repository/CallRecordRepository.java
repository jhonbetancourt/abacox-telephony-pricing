package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.CallRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CallRecordRepository extends JpaRepository<CallRecord, Long>, JpaSpecificationExecutor<CallRecord> {

    /**
     * Checks if a CallRecord with the given CDR hash already exists.
     * This is used to prevent processing duplicate CDR lines.
     *
     * @param cdrHash The SHA-256 hash of the raw CDR line.
     * @return true if a record with this hash exists, false otherwise.
     */
    boolean existsByCdrHash(String cdrHash);
}