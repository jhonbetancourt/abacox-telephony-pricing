package com.infomedia.abacox.telephonypricing.multitenancy;

import com.infomedia.abacox.telephonypricing.service.DefaultDataLoadingService;
import com.infomedia.abacox.telephonypricing.service.ViewManagerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log4j2
public class TenantInitService {
    private final DefaultDataLoadingService defaultDataLoadingService;
    private final MultitenantRunner multitenantRunner;
    private final ViewManagerService viewManagerService;

    @EventListener(ApplicationReadyEvent.class)
    private void initAll(){
        multitenantRunner.runForALlTenants(this::init);
    }

    public void init(){
        defaultDataLoadingService.init();
        viewManagerService.init();
    }

}
