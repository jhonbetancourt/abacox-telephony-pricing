package com.infomedia.abacox.telephonypricing.config;

import com.infomedia.abacox.telephonypricing.cache.PersistentCacheManager;
import com.infomedia.abacox.telephonypricing.cache.PersistentCacheSpec;
import com.infomedia.abacox.telephonypricing.db.repository.CacheEntryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String DASHBOARD_CURRENT                      = "dashboard-current";
    public static final String DASHBOARD_HISTORICAL                   = "dashboard-historical";
    public static final String DASHBOARD_EMPLOYEE_ACTIVITY_CURRENT    = "dashboard-employee-activity-current";
    public static final String DASHBOARD_EMPLOYEE_ACTIVITY_HISTORICAL = "dashboard-employee-activity-historical";

    @Bean
    public List<PersistentCacheSpec> persistentCacheSpecs() {
        return List.of(
                new PersistentCacheSpec(DASHBOARD_CURRENT,                      Duration.ofDays(1),  500),
                new PersistentCacheSpec(DASHBOARD_HISTORICAL,                   Duration.ofDays(7),  1000),
                new PersistentCacheSpec(DASHBOARD_EMPLOYEE_ACTIVITY_CURRENT,    Duration.ofDays(1),  500),
                new PersistentCacheSpec(DASHBOARD_EMPLOYEE_ACTIVITY_HISTORICAL, Duration.ofDays(7),  1000)
        );
    }

    @Bean
    public CacheManager cacheManager(List<PersistentCacheSpec> specs,
                                     ObjectProvider<CacheEntryRepository> cacheEntryRepositoryProvider) {
        return new PersistentCacheManager(specs, cacheEntryRepositoryProvider);
    }
}
