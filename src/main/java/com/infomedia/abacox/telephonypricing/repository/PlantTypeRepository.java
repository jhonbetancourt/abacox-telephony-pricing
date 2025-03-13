package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.PlantType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlantTypeRepository extends JpaRepository<PlantType, Long>, JpaSpecificationExecutor<PlantType> {
}