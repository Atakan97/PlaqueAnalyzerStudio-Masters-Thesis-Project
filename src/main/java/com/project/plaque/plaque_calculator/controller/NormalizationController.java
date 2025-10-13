package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.service.LogService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/normalize")
public class NormalizationController {

	private final Gson gson = new Gson();
	private final LogService logService;

	// The key to keeping the past in Session
	private static final String HISTORY_SESSION_KEY = "normalizationHistory";
	private static final String START_TIME_SESSION_KEY = "normalizationSessionStart";

	@Autowired
	public NormalizationController(LogService logService) {
		this.logService = logService;
	}

	@PostMapping("/continue")
	public String continueNormalization(@RequestBody Map<String,Object> body, HttpSession session) {
		long startTime = System.currentTimeMillis();

		// Get the history list from Session, create a new list if it doesn't exist
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute(HISTORY_SESSION_KEY);
		if (history == null) {
			history = new ArrayList<>();
		}

		// Append current state (body) to the end of history
		history.add(body);
		session.setAttribute(HISTORY_SESSION_KEY, history);

		// Reset attempts counter after a successful advance
		session.removeAttribute("normalizeAttempts");

		// Redirect to normalization page
		return "redirect:/normalization";
	}

	// Helper method in the NormalizationController class that will add the duration to the model
	public Long setAndGetNormalizationStartTime(HttpSession session) {
		Long startTime = (Long) session.getAttribute(START_TIME_SESSION_KEY);
		// If the duration has not been set before (for example, this is the first stage), set it
		if (startTime == null) {
			startTime = System.currentTimeMillis();
			session.setAttribute(START_TIME_SESSION_KEY, startTime);
		}
		return startTime;
	}

	// Adding new API method
	@PostMapping("/log-success")
	public ResponseEntity<?> logBcnfSuccess(@RequestParam("userName") String userName,
											@RequestParam("attempts") int attempts,
											@RequestParam("elapsedTime") long elapsedTime) {
		try {
			logService.logBcnfSuccess(userName, attempts, elapsedTime);
			return ResponseEntity.ok().build();
		} catch (Exception e) {
			System.err.println("Error logging BCNF success: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to log success.");
		}
	}

	@GetMapping("/previous")
	public String returnToPrevious(HttpSession session) {
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute(HISTORY_SESSION_KEY);

		// If there is a step in the past, delete the last step
		if (history != null && !history.isEmpty()) {
			history.remove(history.size() - 1);
			session.setAttribute(HISTORY_SESSION_KEY, history);
		}

		// By clearing this flag we tell PageController that we are back to the first stage
		if (history == null || history.isEmpty()) {
			session.removeAttribute("usingDecomposedAsOriginal");
		}

		return "redirect:/normalization";
	}
}