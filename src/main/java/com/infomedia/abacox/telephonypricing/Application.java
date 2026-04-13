package com.infomedia.abacox.telephonypricing;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Bogota")); 
    }


	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}
}
