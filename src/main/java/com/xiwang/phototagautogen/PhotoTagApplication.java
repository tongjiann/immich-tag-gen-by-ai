package com.xiwang.phototagautogen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PhotoTagApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PhotoTagApplication.class, args);
        int exitCode = SpringApplication.exit(context);
        System.exit(exitCode);
    }

}
