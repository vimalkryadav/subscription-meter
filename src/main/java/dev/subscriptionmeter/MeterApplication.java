package dev.subscriptionmeter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class MeterApplication {
    public static void main(String[] args) { SpringApplication.run(MeterApplication.class, args); }
}
