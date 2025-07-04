package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.SpecialService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SpecialServiceRepository extends JpaRepository<SpecialService, Long>, JpaSpecificationExecutor<SpecialService> {
}