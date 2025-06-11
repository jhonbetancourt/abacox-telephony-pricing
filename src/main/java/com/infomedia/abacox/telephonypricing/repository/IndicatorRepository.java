package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.entity.Indicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IndicatorRepository extends JpaRepository<Indicator, Long>, JpaSpecificationExecutor<Indicator> {
}