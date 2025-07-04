package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.SubdivisionManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SubdivisionManagerRepository extends JpaRepository<SubdivisionManager, Long>, JpaSpecificationExecutor<SubdivisionManager> {
}