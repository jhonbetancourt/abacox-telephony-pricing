package com.infomedia.abacox.telephonypricing.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String DASHBOARD_CURRENT    = "dashboard-current";
    public static final String DASHBOARD_HISTORICAL = "dashboard-historical";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of(DASHBOARD_CURRENT, DASHBOARD_HISTORICAL));
        manager.registerCustomCache(DASHBOARD_CURRENT,
                Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.DAYS).maximumSize(500).build());
        manager.registerCustomCache(DASHBOARD_HISTORICAL,
                Caffeine.newBuilder().expireAfterWrite(7, TimeUnit.DAYS).maximumSize(1000).build());
        return manager;
    }
}
