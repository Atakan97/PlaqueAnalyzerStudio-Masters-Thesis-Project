package com.project.plaque.plaque_calculator;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;


@SpringBootTest
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class})
public class PlaqueCalculatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
