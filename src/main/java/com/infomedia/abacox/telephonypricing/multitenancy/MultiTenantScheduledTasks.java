package com.infomedia.abacox.telephonypricing.multitenancy;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class MultiTenantScheduledTasks {

    /*private final MultitenantRunner multitenantRunner;
    private final LoginService loginService;

    @Scheduled(fixedRate = 300000) // Check every five minutes
    public void runExpiredLoginsJob() {
        log.debug("Starting scheduled job: Expired Logins Cleanup");

        multitenantRunner.runForAllTenants(tenant -> {
            try {
                loginService.expireLoginsForCurrentTenant();

            } catch (Exception e) {
                // Log but continue to next tenant so one failure doesn't stop the whole system
                log.error("Failed to expire logins for tenant: {}", tenant, e);
            } finally {
                // 3. Always clear context to avoid leaking into the next thread usage
                TenantContext.clear();
            }
        });

        log.debug("Finished scheduled job: Expired Logins Cleanup");
    }*/
}