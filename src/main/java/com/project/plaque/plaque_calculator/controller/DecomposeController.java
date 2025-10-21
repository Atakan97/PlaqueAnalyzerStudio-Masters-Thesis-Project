package com.project.plaque.plaque_calculator.controller;

import com.project.plaque.plaque_calculator.dto.DecomposeAllRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeAllResponse;
import com.project.plaque.plaque_calculator.dto.DecomposeRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeResponse;
import com.project.plaque.plaque_calculator.service.DecomposeService;
import com.project.plaque.plaque_calculator.service.LogService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/normalize")
public class DecomposeController {

	private final DecomposeService decomposeService;
	private final LogService logService;
	private static final String ATTEMPT_COUNT_SESSION_KEY = "attemptCount";
	private static final String NORMALIZATION_START_TIME_KEY = "normalizationStartTime";

	public DecomposeController(DecomposeService decomposeService, LogService logService) {
		this.decomposeService = decomposeService;
		this.logService = logService;
	}

	// POST /normalize/decompose
	@PostMapping("/decompose")
	public ResponseEntity<?> decompose(
			@RequestBody DecomposeRequest req,
			HttpSession session
	) {
		DecomposeResponse resp = decomposeService.decompose(req, session);

		return ResponseEntity.ok(resp);
	}
	// POST /normalize/project-fds
	@PostMapping("/project-fds")
	public ResponseEntity<?> projectFDs(
			@RequestBody DecomposeRequest req,
			HttpSession session
	) {
		try {
			DecomposeResponse resp = decomposeService.projectFDsOnly(req, session);
			return ResponseEntity.ok(resp);
		} catch (IllegalStateException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
		} catch (Exception ex) {
			ex.printStackTrace();
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Server error"));
		}
	}

	// POST /normalize/decompose-all (processing multiple decomposed-tables)
	@PostMapping("/decompose-all")
	public ResponseEntity<?> decomposeAll(@RequestBody DecomposeAllRequest req, HttpSession session) {
		// Attempt sayısını artır
		Integer attemptCount = (Integer) session.getAttribute(ATTEMPT_COUNT_SESSION_KEY);
		if (attemptCount == null) {
			attemptCount = 0;
		}
		attemptCount++;
		session.setAttribute(ATTEMPT_COUNT_SESSION_KEY, attemptCount);

		// Normalizasyon başlangıç zamanını set et (ilk denemede)
		if (attemptCount == 1) {
			session.setAttribute(NORMALIZATION_START_TIME_KEY, System.currentTimeMillis());
		}

		DecomposeAllResponse response = decomposeService.decomposeAll(req, session);

		// Eğer BCNF ise log kaydet
		if (response.isBCNFDecomposition()) {
			Long startTime = (Long) session.getAttribute(NORMALIZATION_START_TIME_KEY);
			long elapsedTime = startTime != null ?
					(System.currentTimeMillis() - startTime) / 1000 : 0;

			// Session'dan userName al
			String userName = (String) session.getAttribute("userName");
			if (userName == null || userName.trim().isEmpty()) {
				userName = "Anonymous User"; // Varsayılan değer
			}

			logService.logBcnfSuccess(userName, attemptCount, elapsedTime);

			// Session'ı temizle
			session.removeAttribute(ATTEMPT_COUNT_SESSION_KEY);
			session.removeAttribute(NORMALIZATION_START_TIME_KEY);
		}

		return ResponseEntity.ok(response);
	}
}
