package com.infomedia.abacox.telephonypricing.cache;

import com.infomedia.abacox.telephonypricing.db.repository.CacheEntryRepository;
import com.infomedia.abacox.telephonypricing.multitenancy.MultitenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Deletes expired rows from {@code cache_entry} in every tenant schema.
 * The in-app TTL check already ignores expired rows on read, so this is purely
 * housekeeping to keep the table small.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictionScheduler {

    private final MultitenantRunner multitenantRunner;
    private final CacheEntryRepository cacheEntryRepository;

    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpired() {
        log.info("Starting expired cache entry cleanup");
        LocalDateTime now = LocalDateTime.now();

        multitenantRunner.runForAllTenants(tenant -> {
            try {
                int deleted = cacheEntryRepository.deleteExpired(now);
                if (deleted > 0) {
                    log.info("Deleted {} expired cache entries for tenant={}", deleted, tenant);
                }
            } catch (Exception ex) {
                log.error("Cache eviction failed for tenant={}", tenant, ex);
            }
        });
        log.info("Finished expired cache entry cleanup");
    }
}
