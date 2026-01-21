package com.example.filesecbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FileSecBoxApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileSecBoxApplication.class, args);
    }
}

