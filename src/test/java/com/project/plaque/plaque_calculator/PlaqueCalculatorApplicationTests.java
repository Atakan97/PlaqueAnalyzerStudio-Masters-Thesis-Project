package com.project.plaque.plaque_calculator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test to verify the application context loads successfully.
 * Uses H2 in-memory database for testing.
 *
 * Note: This test excludes database-dependent components (AdminController, LogService, LogRepository)
 * to allow testing without a real database connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:application-test.properties")
class PlaqueCalculatorApplicationTests {

	@Test
	void contextLoads() {
		// Simple smoke test - verifies that Spring context loads successfully
		// This test runs with H2 in-memory database
	}

}
