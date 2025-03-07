package com.infomedia.abacox.telephonypricing.repository;

import com.infomedia.abacox.telephonypricing.entity.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ConfigurationRepository extends JpaRepository<Configuration, Long>, JpaSpecificationExecutor<Configuration> {


    Optional<Configuration> findByKey(String key);
}