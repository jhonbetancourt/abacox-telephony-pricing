package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.InventorySupplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventorySupplierRepository extends JpaRepository<InventorySupplier, Long>, JpaSpecificationExecutor<InventorySupplier> {
}
