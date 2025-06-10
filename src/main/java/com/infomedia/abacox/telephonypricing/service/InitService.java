package com.infomedia.abacox.telephonypricing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log4j2
public class InitService {


    @EventListener(ApplicationReadyEvent.class)
    public void init() {
    }
}
