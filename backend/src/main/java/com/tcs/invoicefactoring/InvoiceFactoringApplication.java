package com.tcs.invoicefactoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the B2B Invoice Factoring & Smart Escrow Network backend.
 * Architecture: Modular Monolith (Java 21 / Spring Boot 3.4).
 */
@SpringBootApplication
public class InvoiceFactoringApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvoiceFactoringApplication.class, args);
    }
}
