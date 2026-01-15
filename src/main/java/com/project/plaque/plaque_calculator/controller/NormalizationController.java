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
		logService.info("[NormalizationController] /continue invoked with payload keys=" + body.keySet());
		String computationId = body != null && body.get("computationId") != null ? String.valueOf(body.get("computationId")) : null;
		String prefix = (computationId == null || computationId.isBlank()) ? "" : ("computation_" + computationId + "_");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute(prefix + HISTORY_SESSION_KEY);
		if (history == null) {
			history = new ArrayList<>();
		}


		// Append current state (body) to the end of history
		history.add(body);
		logService.info("[NormalizationController] history size after append=" + history.size());
		session.setAttribute(prefix + HISTORY_SESSION_KEY, history);
		session.removeAttribute(prefix + RESTORE_SESSION_KEY);

		// Reset attempts counter after a successful advance
		session.removeAttribute(prefix + "normalizeAttempts");

		session.removeAttribute(prefix + RESET_SESSION_KEY);
		// Redirect to normalization page
		return computationId == null || computationId.isBlank()
			? "redirect:/normalization"
			: ("redirect:/normalization?id=" + computationId);
	}

	public Long setAndGetNormalizationStartTime(HttpSession session, String computationId) {
		String prefix = "computation_" + computationId + "_";
		Long startTime = (Long) session.getAttribute(prefix + START_TIME_SESSION_KEY);
		if (startTime == null) {
			startTime = System.currentTimeMillis();
			session.setAttribute(prefix + START_TIME_SESSION_KEY, startTime);
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
			String plaqueMode = (String) session.getAttribute("plaqueMode");
			logService.logBcnfSuccess(userName, attempts, elapsedTime, tableCount, dependencyPreserved, plaqueMode);
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

		// Extract and store computationId
		String computationId = body.get("computationId") != null ? String.valueOf(body.get("computationId")) : null;
		if (computationId != null && !computationId.isEmpty() && !"null".equals(computationId)) {
			summaryData.put("computationId", computationId);
		}

		Number attemptsFromSession = (Number) session.getAttribute("bcnfAttempts");
		if (attemptsFromSession != null) {
			summaryData.put("attempts", attemptsFromSession.intValue());
		}

		Number elapsedFromSession = (Number) session.getAttribute("bcnfElapsedTime");
		if (elapsedFromSession != null) {
			summaryData.put("elapsedTime", elapsedFromSession.longValue());
		}

		// Calculate tableCount from payload
		Integer tableCount = null;
		Object columnsPerTableObj = body.get("columnsPerTable");
		if (columnsPerTableObj instanceof List<?>) {
			tableCount = ((List<?>) columnsPerTableObj).size();
			// Update session with correct table count
			session.setAttribute("bcnfTableCount", tableCount);
			logService.info("[NormalizationController] Updated bcnfTableCount from payload: " + tableCount);
		} else {
			// Fallback to session value
			tableCount = (Integer) session.getAttribute("bcnfTableCount");
		}

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

		// Add computationId to model for navigation
		String computationId = summary.get("computationId") != null ? String.valueOf(summary.get("computationId")) : null;
		model.addAttribute("computationId", computationId);

		// Add plaqueMode to model
		String plaqueMode = (String) session.getAttribute("plaqueMode");
		model.addAttribute("plaqueMode", plaqueMode != null ? plaqueMode : "enabled");

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
	@PostMapping("/increment-attempt")
	public ResponseEntity<?> incrementAttempt(HttpSession session) {
		// Use same key as DecomposeController
		Integer attemptCount = (Integer) session.getAttribute("attemptCount");
		if (attemptCount == null) {
			attemptCount = 1;
		}
		attemptCount++;
		session.setAttribute("attemptCount", attemptCount);
		logService.info("[NormalizationController] Attempt count incremented to: " + attemptCount);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/previous")
	public String returnToPrevious(@RequestParam(value = "id", required = false) String computationId, HttpSession session) {
		String prefix = (computationId == null || computationId.isBlank()) ? "" : ("computation_" + computationId + "_");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute(prefix + HISTORY_SESSION_KEY);
		logService.info("[NormalizationController] /previous invoked. history size=" + (history == null ? 0 : history.size()));
		if (history == null || history.isEmpty()) {
			session.setAttribute(prefix + RESET_SESSION_KEY, Boolean.TRUE);
			logService.info("[NormalizationController] history empty. Trigger reset state.");
			return computationId == null || computationId.isBlank()
				? "redirect:/normalization"
				: ("redirect:/normalization?id=" + computationId);
		}

		List<Map<String, Object>> updatedHistory = new ArrayList<>(history);
		Map<String, Object> poppedState = updatedHistory.remove(updatedHistory.size() - 1);
		logService.info("[NormalizationController] popped state. new history size=" + updatedHistory.size());
		session.setAttribute(prefix + HISTORY_SESSION_KEY, updatedHistory);

		Map<String, Object> restoreState = poppedState != null ? new HashMap<>(poppedState) : null;

		if (restoreState != null) {
			session.setAttribute(prefix + RESTORE_SESSION_KEY, restoreState);
			session.setAttribute(prefix + "usingDecomposedAsOriginal", Boolean.TRUE);
			logService.info("[NormalizationController] restoreState keys=" + restoreState.keySet());
		} else {
			logService.info("[NormalizationController] history exhausted after pop; resetting normalization state.");
			session.setAttribute(prefix + RESET_SESSION_KEY, Boolean.TRUE);
			clearRestoreState(session, prefix);
		}
		return computationId == null || computationId.isBlank()
			? "redirect:/normalization"
			: ("redirect:/normalization?id=" + computationId);
	}

	private void clearRestoreState(HttpSession session, String prefix) {
		session.removeAttribute(prefix + "usingDecomposedAsOriginal");
		session.removeAttribute(prefix + RESTORE_SESSION_KEY);
	}

	private void clearRestoreState(HttpSession session) {
		clearRestoreState(session, "");
	}
}