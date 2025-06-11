package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.entity.Prefix;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PrefixRepository extends JpaRepository<Prefix, Long>, JpaSpecificationExecutor<Prefix> {
}