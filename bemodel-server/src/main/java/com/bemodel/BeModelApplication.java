package com.bemodel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.bemodel.**.mapper")
public class BeModelApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeModelApplication.class, args);
    }
}
