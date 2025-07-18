package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.JobPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface JobPositionRepository extends JpaRepository<JobPosition, Long>, JpaSpecificationExecutor<JobPosition> {
}