package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.SpecialRateValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SpecialRateValueRepository extends JpaRepository<SpecialRateValue, Long>, JpaSpecificationExecutor<SpecialRateValue> {
}