package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.TrunkRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TrunkRateRepository extends JpaRepository<TrunkRate, Long>, JpaSpecificationExecutor<TrunkRate> {
}