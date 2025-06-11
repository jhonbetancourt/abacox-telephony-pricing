package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.db.entity.CommunicationLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CommunicationLocationRepository extends JpaRepository<CommunicationLocation, Long>, JpaSpecificationExecutor<CommunicationLocation> {
}