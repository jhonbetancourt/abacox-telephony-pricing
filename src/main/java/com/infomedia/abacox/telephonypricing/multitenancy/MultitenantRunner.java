package com.infomedia.abacox.telephonypricing.multitenancy;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Log4j2

public class MultitenantRunner {

    private final TenantProvider tenantProvider;

    public void runForAllTenants(Consumer<String> task) {
        List<String> tenants = tenantProvider.getAllTenants();

        for (String tenant : tenants) {
            try {
                // 1. Set Context
                TenantContext.setTenant(tenant);

                // 2. Execute Task
                task.accept(tenant);

            } catch (Exception e) {
                log.error("Failed to execute task for tenant: {}", tenant, e);
            } finally {
                // 3. Clear Context
                TenantContext.clear();
            }
        }
    }

    public void runForALlTenants(Runnable task) {
        List<String> tenants = tenantProvider.getAllTenants();

        for (String tenant : tenants) {
            try {
                // 1. Set Context
                TenantContext.setTenant(tenant);

                // 2. Execute Task
                task.run();

            } catch (Exception e) {
                log.error("Failed to execute task for tenant: {}", tenant, e);
            } finally {
                // 3. Clear Context
                TenantContext.clear();
            }
        }
    }
}
