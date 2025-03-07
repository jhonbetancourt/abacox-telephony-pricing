package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.BandIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BandIndicatorRepository extends JpaRepository<BandIndicator, Long>, JpaSpecificationExecutor<BandIndicator> {
}