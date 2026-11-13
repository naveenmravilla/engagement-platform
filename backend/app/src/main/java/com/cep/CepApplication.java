package com.cep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Customer Engagement Platform: modular monolith entry point. Component scan covers every com.cep module. */
@SpringBootApplication
public class CepApplication {
    public static void main(String[] args) {
        SpringApplication.run(CepApplication.class, args);
    }
}
