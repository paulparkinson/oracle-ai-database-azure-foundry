package com.example.oracle.azure.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DemoSettings.class)
public class RagApplication {
    public static void main(String[] args) { SpringApplication.run(RagApplication.class, args); }
}
