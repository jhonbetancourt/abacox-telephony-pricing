package com.infomedia.abacox.telephonypricing.multitenancy;

import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(@NonNull Runnable runnable) {
        String tenantId = TenantContext.getTenant();
        return () -> {
            try {
                TenantContext.setTenant(tenantId);
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}