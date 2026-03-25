package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.InventoryAdditionalService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventoryAdditionalServiceRepository extends JpaRepository<InventoryAdditionalService, Long>, JpaSpecificationExecutor<InventoryAdditionalService> {
}
