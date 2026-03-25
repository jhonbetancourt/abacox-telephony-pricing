package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.InventoryDs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventoryDsRepository extends JpaRepository<InventoryDs, Long>, JpaSpecificationExecutor<InventoryDs> {
}
