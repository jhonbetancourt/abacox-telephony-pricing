package com.infomedia.abacox.telephonypricing;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;

@SpringBootApplication
@EnableScheduling
public class Application {
	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	 @PostConstruct
    public void init() {
        // Force the JVM to match the legacy PHP server's timezone
        // This ensures all new inserts/updates match the legacy database data
        TimeZone.setDefault(TimeZone.getTimeZone("America/Bogota")); 
        
        // Note: You can also inject a property here if you want it configurable:
        // TimeZone.setDefault(TimeZone.getTimeZone(myConfiguredTimezone));
    }
}
