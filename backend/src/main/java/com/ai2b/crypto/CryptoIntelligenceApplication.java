package com.ai2b.crypto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CryptoIntelligenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoIntelligenceApplication.class, args);
    }
}
