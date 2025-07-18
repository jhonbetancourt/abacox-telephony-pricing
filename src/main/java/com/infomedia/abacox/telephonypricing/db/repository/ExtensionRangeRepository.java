package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.ExtensionRange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExtensionRangeRepository extends JpaRepository<ExtensionRange, Long>, JpaSpecificationExecutor<ExtensionRange> {
}