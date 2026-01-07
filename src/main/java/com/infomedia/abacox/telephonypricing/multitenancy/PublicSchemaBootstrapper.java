package com.infomedia.abacox.telephonypricing.multitenancy;

import com.infomedia.abacox.telephonypricing.component.configmanager.ConfigValueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class PublicSchemaBootstrapper implements CommandLineRunner {

    private final SchemaMigrationService schemaMigrationService;
    private final ConfigValueService configValueService;

    @Override
    public void run(String... args) throws Exception {
        log.info("BOOTSTRAP: Initializing Public/Global Schema...");

        try {
            // 1. Sync ONLY the global tables (Config)
            schemaMigrationService.syncPublicSchema();
            
            // 2. Now that the table exists, reload configs
            // The ConfigValueService will look in 'public' by default if no tenant header is set
            configValueService.invalidateCurrentTenantCache(); // Clear any failed attempts
            // Accessing it triggers the lazy load from the DB
            configValueService.getConfiguration();
            
            log.info("BOOTSTRAP: Public schema ready.");
        } catch (Exception e) {
            log.error("BOOTSTRAP: Failed to initialize public schema.", e);
            // We do NOT rethrow here, so the app stays alive for other tenants
        }
    }
}