package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.BandIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BandIndicatorRepository extends JpaRepository<BandIndicator, Long>, JpaSpecificationExecutor<BandIndicator> {
}