package com.infomedia.abacox.telephonypricing.cdr;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CachingConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(
            "prefixes", "trunks", "indicativeLimits", "specialServices", 
            "pbxSpecialRules", "limitsInternas", "telephonyTypeInternas", 
            "tipoteleLocal", "tipoteleLocalExt", "tipoteleNacional", 
            "tipoteleCelular", "tipoteleCelufijo", "tipoteleInternacional", 
            "tipoteleSatelital", "tipoteleEspeciales", "tipoteleErrores", 
            "asumido", "maxExtension", "ivaByTelephonyTypeAndOperator",
            "defaultOperatorByTelephonyType"
        );
    }
}