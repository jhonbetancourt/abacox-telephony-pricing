package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.BandGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BandGroupRepository extends JpaRepository<BandGroup, Long>, JpaSpecificationExecutor<BandGroup> {
}