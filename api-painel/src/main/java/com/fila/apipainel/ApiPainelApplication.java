package com.fila.apipainel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiPainelApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiPainelApplication.class, args);
    }
}
