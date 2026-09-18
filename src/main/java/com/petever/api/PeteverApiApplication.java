package com.petever.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PeteverApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PeteverApiApplication.class, args);
	}

}
