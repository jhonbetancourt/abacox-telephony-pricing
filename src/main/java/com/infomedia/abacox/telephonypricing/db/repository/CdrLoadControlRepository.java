package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.CdrLoadControl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CdrLoadControlRepository extends JpaRepository<CdrLoadControl, Long> {
    List<CdrLoadControl> findByActiveTrue();
    Optional<CdrLoadControl> findByNameAndActiveTrue(String name);
}
