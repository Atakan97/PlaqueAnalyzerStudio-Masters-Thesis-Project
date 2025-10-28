package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.service.LogService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
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
	private static final String BCNF_SUMMARY_SESSION_KEY = "bcnfSummary";
	private static final String RESTORE_SESSION_KEY = "normalizationRestoreState";
	private static final String RESET_SESSION_KEY = "normalizationReset";

	@Autowired
	public NormalizationController(LogService logService) {
		this.logService = logService;
	}

	@PostMapping("/continue")
	public String continueNormalization(@RequestBody Map<String, Object> body, HttpSession session) {
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
		session.removeAttribute(RESTORE_SESSION_KEY);

		// Reset attempts counter after a successful advance
		session.removeAttribute("normalizeAttempts");

		session.removeAttribute(RESET_SESSION_KEY);
		// Redirect to normalization page
		return "redirect:/normalization";
	}

	public Long setAndGetNormalizationStartTime(HttpSession session) {
		Long startTime = (Long) session.getAttribute(START_TIME_SESSION_KEY);
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
											  @RequestParam("elapsedTime") long elapsedTime,
											  HttpSession session) {
		try {
			Integer tableCount = (Integer) session.getAttribute("bcnfTableCount");
			Boolean dependencyPreserved = (Boolean) session.getAttribute("bcnfDependencyPreserved");
			logService.logBcnfSuccess(userName, attempts, elapsedTime, tableCount, dependencyPreserved);
			session.removeAttribute("bcnfTableCount");
			session.removeAttribute("bcnfDependencyPreserved");
			return ResponseEntity.ok().build();
		} catch (Exception e) {
			System.err.println("Error logging BCNF success: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to log success.");
		}
	}

	@PostMapping("/bcnf-review")
	public ResponseEntity<?> storeBcnfReview(@RequestBody Map<String, Object> body, HttpSession session) {
		if (body == null || body.isEmpty()) {
			return ResponseEntity.badRequest().body("BCNF data payload is empty.");
		}
		Map<String, Object> summaryData = new HashMap<>(body);

		Number attemptsFromSession = (Number) session.getAttribute("bcnfAttempts");
		if (attemptsFromSession != null) {
			summaryData.put("attempts", attemptsFromSession.intValue());
		}

		Number elapsedFromSession = (Number) session.getAttribute("bcnfElapsedTime");
		if (elapsedFromSession != null) {
			summaryData.put("elapsedTime", elapsedFromSession.longValue());
		}

		Integer tableCount = (Integer) session.getAttribute("bcnfTableCount");
		if (tableCount != null) {
			summaryData.put("tableCount", tableCount);
		}

		Boolean dependencyPreserved = (Boolean) session.getAttribute("bcnfDependencyPreserved");
		if (dependencyPreserved != null) {
			summaryData.put("dependencyPreserved", dependencyPreserved);
		}

		session.setAttribute(BCNF_SUMMARY_SESSION_KEY, summaryData);
		session.removeAttribute("bcnfAttempts");
		session.removeAttribute("bcnfElapsedTime");
		session.removeAttribute("normalizationSessionStart");
		Map<String, Object> response = new HashMap<>();
		response.put("redirectUrl", "/normalize/bcnf-summary");
		return ResponseEntity.ok(response);
	}

	@GetMapping("/bcnf-summary")
	public String showBcnfSummary(HttpSession session, Model model) {
		@SuppressWarnings("unchecked")
		Map<String, Object> summary = (Map<String, Object>) session.getAttribute(BCNF_SUMMARY_SESSION_KEY);
		if (summary == null) {
			return "redirect:/normalization";
		}

		model.addAttribute("bcnfSummaryJson", gson.toJson(summary));

		Number attemptsNumber = extractNumber(summary.get("attempts"));
		Number elapsedNumber = extractNumber(summary.get("elapsedTime"));

		model.addAttribute("bcnfAttempts", attemptsNumber != null ? attemptsNumber.intValue() : 0);
		model.addAttribute("bcnfElapsedSeconds", elapsedNumber != null ? elapsedNumber.longValue() : 0L);

		return "bcnf-summary";
	}

	private Number extractNumber(Object value) {
		if (value instanceof Number number) {
			return number;
		}
		if (value instanceof String str) {
			try {
				if (str.contains(".")) {
					return Double.parseDouble(str);
				}
				return Long.parseLong(str);
			} catch (NumberFormatException ignored) {
			}
		}
		return null;
	}
	@GetMapping("/previous")
	public String returnToPrevious(HttpSession session) {
		session.setAttribute(RESET_SESSION_KEY, Boolean.TRUE);
		session.removeAttribute(HISTORY_SESSION_KEY);
		clearRestoreState(session);
		return "redirect:/normalization";
	}

	private void clearRestoreState(HttpSession session) {
		session.removeAttribute("usingDecomposedAsOriginal");
		session.removeAttribute(RESTORE_SESSION_KEY);
	}
}