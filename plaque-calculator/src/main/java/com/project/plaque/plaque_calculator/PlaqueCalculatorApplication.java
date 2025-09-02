package com.project.plaque.plaque_calculator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(
		exclude = { DataSourceAutoConfiguration.class }
)
public class PlaqueCalculatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlaqueCalculatorApplication.class, args);
	}

}
