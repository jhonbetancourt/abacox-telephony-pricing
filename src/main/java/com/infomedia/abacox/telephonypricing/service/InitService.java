package com.infomedia.abacox.telephonypricing.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InitService {


    @EventListener(ApplicationReadyEvent.class)
    public void init() {

    }
}
