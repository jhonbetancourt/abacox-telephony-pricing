package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.TelephonyTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TelephonyTypeConfigRepository extends JpaRepository<TelephonyTypeConfig, Long>, JpaSpecificationExecutor<TelephonyTypeConfig> {
}