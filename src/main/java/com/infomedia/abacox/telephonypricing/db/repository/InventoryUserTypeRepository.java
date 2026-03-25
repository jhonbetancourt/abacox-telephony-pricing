package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.InventoryUserType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventoryUserTypeRepository extends JpaRepository<InventoryUserType, Long>, JpaSpecificationExecutor<InventoryUserType> {
}
