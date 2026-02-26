package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.ExtensionList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExtensionListRepository
        extends JpaRepository<ExtensionList, Long>, JpaSpecificationExecutor<ExtensionList> {

    @Query("SELECT e FROM ExtensionList e WHERE e.active = true AND e.type = :type ORDER BY e.name")
    List<ExtensionList> findActiveByType(@Param("type") String type);

    @Query("SELECT e FROM ExtensionList e WHERE e.active = true AND (:id IS NULL OR e.id = :id) AND e.type = :type ORDER BY e.name")
    List<ExtensionList> findActiveByTypeAndOptionalId(@Param("type") String type, @Param("id") Long id);
}
