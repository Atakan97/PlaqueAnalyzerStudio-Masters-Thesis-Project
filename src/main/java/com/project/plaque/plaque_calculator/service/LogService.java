package com.project.plaque.plaque_calculator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

	public void logBcnfSuccess(String userName, int attempts, long elapsedTimeSecs,
							 Integer tableCount, Boolean dependencyPreserved, String plaqueMode) {
		LogEntry logEntry = new LogEntry();
		logEntry.setUserName(userName);
		logEntry.setAttempts(attempts > 0 ? attempts : 1);
		logEntry.setElapsedTimeSecs(elapsedTimeSecs);
		logEntry.setTimestamp(LocalDateTime.now());
		logEntry.setActivityType("BCNF_SUCCESS"); // Define activity type

		try {
			var details = objectMapper.createObjectNode();
			details.put("Total Decomposed Table Count", tableCount != null ? tableCount : 0);
			details.put("Decomposition Dependency-Preserving Status", dependencyPreserved != null && dependencyPreserved);
			details.put("Plaque Mode", plaqueMode != null ? plaqueMode : "enabled");
			logEntry.setDetailsJson(objectMapper.writeValueAsString(details));
		} catch (Exception ex) {
			logEntry.setDetailsJson(null);
		}

		// Save using LogRepository
		logRepository.save(logEntry);
		System.out.println("Logged BCNF Success: User=" + userName + ", Attempts=" + attempts + ", Time=" + elapsedTimeSecs + "s, Tables=" + (tableCount != null ? tableCount : 0) + ", DP=" + (dependencyPreserved != null && dependencyPreserved) + ", Mode=" + plaqueMode);
	}

	public void info(String message) {
		System.out.println(message);
	}
}