package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/normalize")
public class NormalizationController {

	private final Gson gson = new Gson();

	@PostMapping
	public String normalize(HttpSession session) {
		// Retrieve current attempts
		Integer attempts = (Integer) session.getAttribute("normalizeAttempts");
		if (attempts == null) attempts = 0;

		// Dummy normalization logic
		List<List<Integer>> twoNF = Collections.emptyList();
		List<List<Integer>> threeNF = Collections.emptyList();

		Map<String, Object> resp = new HashMap<>();
		resp.put("attempts", attempts);
		resp.put("relations2NF", twoNF);
		resp.put("relations3NF", threeNF);
		return gson.toJson(resp);
	}

	@PostMapping("/incrementAttempt")
	public String incrementAttempt(HttpSession session) {
		Integer attempts = (Integer) session.getAttribute("normalizeAttempts");
		attempts = (attempts == null ? 1 : attempts + 1);
		session.setAttribute("normalizeAttempts", attempts);

		Map<String, Object> resp = new HashMap<>();
		resp.put("attempts", attempts);
		return gson.toJson(resp);
	}

	@PostMapping("/resetAttempt")
	public String resetAttempt(HttpSession session) {
		session.removeAttribute("normalizeAttempts");
		Map<String, Object> resp = new HashMap<>();
		resp.put("attempts", 0);
		return gson.toJson(resp);
	}

}
