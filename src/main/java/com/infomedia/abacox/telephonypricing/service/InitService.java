package com.infomedia.abacox.telephonypricing.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InitService {

  /*  private final MigrationRunner migrationRunner;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        SourceDbConfig sourceDbConfig = SourceDbConfig.builder()
                .url("jdbc:sqlserver://172.16.4.71:1433;databaseName=abacox_infomedia;encrypt=false;trustServerCertificate=true;")
                .username("sa")
                .password("infomedia")
                .build();

        migrationRunner.run(sourceDbConfig);
    }*/
}
