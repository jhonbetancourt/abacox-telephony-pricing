package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.OriginCountry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OriginCountryRepository extends JpaRepository<OriginCountry, Long>, JpaSpecificationExecutor<OriginCountry> {
}