package com.project.plaque.plaque_calculator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories("com.project.plaque.plaque_calculator.repository")
@SpringBootApplication
public class PlaqueCalculatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlaqueCalculatorApplication.class, args);
	}

}
