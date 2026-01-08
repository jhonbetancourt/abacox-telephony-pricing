package com.infomedia.abacox.telephonypricing.multitenancy;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class TenantInitService {

    private final MultitenantRunner multitenantRunner;
    private final List<TenantInitializer> initializers;

    @EventListener(ApplicationReadyEvent.class)
    private void initAll() {
        multitenantRunner.runForAllTenants(this::init);
    }

    public void init(String tenantId) {
        initializers.forEach(init -> {
            try {
                init.onTenantInit(tenantId);
            }catch (Exception e){
                log.error("Failed to initialize tenant: {}", tenantId, e);
            }
        });
    }
}
