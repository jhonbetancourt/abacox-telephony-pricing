package com.infomedia.abacox.telephonypricing.db.repository;

import com.infomedia.abacox.telephonypricing.db.entity.CacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CacheEntryRepository extends JpaRepository<CacheEntry, CacheEntry.CacheEntryId> {

    Optional<CacheEntry> findByCacheNameAndCacheKey(String cacheName, String cacheKey);

    @Modifying
    @Transactional
    @Query("delete from CacheEntry c where c.cacheName = :cacheName and c.cacheKey = :cacheKey")
    int deleteEntry(@Param("cacheName") String cacheName, @Param("cacheKey") String cacheKey);

    @Modifying
    @Transactional
    @Query("delete from CacheEntry c where c.cacheName = :cacheName")
    int deleteAllByCacheName(@Param("cacheName") String cacheName);

    @Modifying
    @Transactional
    @Query("delete from CacheEntry c where c.expiresAt < :threshold")
    int deleteExpired(@Param("threshold") LocalDateTime threshold);
}
