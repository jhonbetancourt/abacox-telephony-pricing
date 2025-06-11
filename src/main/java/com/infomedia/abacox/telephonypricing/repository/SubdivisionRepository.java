package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.entity.Subdivision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SubdivisionRepository extends JpaRepository<Subdivision, Long>, JpaSpecificationExecutor<Subdivision> {
}