package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.TelephonyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TelephonyTypeRepository extends JpaRepository<TelephonyType, Long>, JpaSpecificationExecutor<TelephonyType> {
}