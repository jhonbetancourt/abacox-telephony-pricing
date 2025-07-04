package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.entity.SpecialExtension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SpecialExtensionRepository extends JpaRepository<SpecialExtension, Long>, JpaSpecificationExecutor<SpecialExtension> {
}