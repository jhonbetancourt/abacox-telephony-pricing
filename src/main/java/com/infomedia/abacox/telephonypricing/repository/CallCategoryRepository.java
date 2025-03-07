package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.CallCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CallCategoryRepository extends JpaRepository<CallCategory, Long>, JpaSpecificationExecutor<CallCategory> {
}