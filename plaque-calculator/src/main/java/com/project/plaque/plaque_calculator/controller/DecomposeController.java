package com.project.plaque.plaque_calculator.controller;

import com.project.plaque.plaque_calculator.dto.DecomposeAllRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeAllResponse;
import com.project.plaque.plaque_calculator.dto.DecomposeRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeResponse;
import com.project.plaque.plaque_calculator.service.DecomposeService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/normalize")
public class DecomposeController {

	private final DecomposeService decomposeService;

	public DecomposeController(DecomposeService svc) {
		this.decomposeService = svc;
	}

	// POST /normalize/decompose
	@PostMapping("/decompose")
	public ResponseEntity<?> decompose(
			@RequestBody DecomposeRequest req,
			HttpSession session
	) {
		DecomposeResponse resp = decomposeService.decompose(req, session);

		// Enforcing server-side due to lossless-join is mandatory when normalization
		if (!resp.isLjPreserved()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("error", "Lossless-Join not preserved for selected columns"));
		}

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
	public ResponseEntity<?> decomposeAll(
			// Spring will automatically convert incoming JSON to DecomposeAllRequest DTO
			@RequestBody DecomposeAllRequest req,
			HttpSession session
	) {
		try {
			DecomposeAllResponse resp = decomposeService.decomposeAll(req, session);

			return ResponseEntity.ok(resp);
		} catch (IllegalStateException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
		} catch (Exception ex) {
			ex.printStackTrace();
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Server error"));
		}
	}

}



