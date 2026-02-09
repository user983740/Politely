package com.politeai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PoliteAi - Korean tone transformation service.
 */
@SpringBootApplication
@EnableScheduling
public class PoliteAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PoliteAiApplication.class, args);
	}

}
