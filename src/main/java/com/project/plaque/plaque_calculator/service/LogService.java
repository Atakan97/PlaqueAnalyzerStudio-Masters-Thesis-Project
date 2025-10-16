package com.project.plaque.plaque_calculator.service;

import com.fasterxml.jackson.databind.ObjectMapper; // JSON işlemek için
import com.project.plaque.plaque_calculator.model.LogEntry;
import com.project.plaque.plaque_calculator.repository.LogRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class LogService {

	@Autowired
	private LogRepository logRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public void logBcnfSuccess(String userName, int attempts, long elapsedTimeSecs) {
		LogEntry logEntry = new LogEntry();
		logEntry.setUserName(userName);
		logEntry.setAttempts(attempts > 0 ? attempts : 1);
		logEntry.setElapsedTimeSecs(elapsedTimeSecs);
		logEntry.setTimestamp(LocalDateTime.now());
		logEntry.setActivityType("BCNF_SUCCESS"); // Define activity type

		// Save using LogRepository
		logRepository.save(logEntry);
		System.out.println("LOGGED BCNF SUCCESS: User=" + userName + ", Attempts=" + attempts + ", Time=" + elapsedTimeSecs + "s");
	}
}