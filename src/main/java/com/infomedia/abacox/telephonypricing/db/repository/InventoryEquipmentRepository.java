package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.InventoryEquipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventoryEquipmentRepository extends JpaRepository<InventoryEquipment, Long>, JpaSpecificationExecutor<InventoryEquipment> {
}
