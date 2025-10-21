package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.model.FD;
import com.project.plaque.plaque_calculator.service.FDService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.*;
import java.util.stream.Collectors;

@Controller
public class PageController {

	private final NormalizationController normalizationController;
	private final FDService fdService;
	private final Gson gson = new Gson();
	private static final String RESTORE_SESSION_KEY = "normalizationRestoreState";

	public PageController(NormalizationController normalizationController, FDService fdService) {
		this.normalizationController = normalizationController;
		this.fdService = fdService;
	}

	// Home page redirect
	@GetMapping("/")
	public String index(HttpSession session) {

		// If the student is already logged in, they can be redirected directly to the calc page
		if (session.getAttribute("studentNumber") != null) {
			return "redirect:/calc";
		}
		return "index";
	}

	@GetMapping("/calc")
	public String getCalcPage(
			@RequestParam(value = "inputData", required = false) String inputData,
			@RequestParam(value = "fdList", required = false) String fdList,
			Model model) {

		// If it comes from the results page and has data/FD information, add it to the model
		if (inputData != null && !inputData.isEmpty()) {
			model.addAttribute("restoredInputData", inputData);
		}
		if (fdList != null && !fdList.isEmpty()) {
			model.addAttribute("restoredFdList", fdList);
		}

		return "calc";
	}

	// Handle Student Logout
	@GetMapping("/logout")
	public String studentLogout(HttpSession session) {

		// Log the student logout action
		// logService.logActivity("STUDENT_LOGOUT", null, session, null, null);

		// Invalidate the entire session
		session.invalidate();

		// Redirect to the main index page (login screen)
		return "redirect:/";
	}

	/**
	 * Render normalization page.
	 *     - currentRelationsManualJson    : List<String> (manual rows per decomposed table)
	 *     - currentRelationsColumnsJson   : List<List<Integer>> (columns per decomposed table)
	 *     - currentRelationsFdsJson       : List<String> (per-table FDs)
	 */
	@GetMapping("/normalization")
	public String normalizePage(HttpSession session, Model model) {

		Long startTime = normalizationController.setAndGetNormalizationStartTime(session);
		model.addAttribute("normalizationStartTimeMs", startTime);

		@SuppressWarnings("unchecked")
		// Get history list from session
		List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute("normalizationHistory");

		boolean canReturn = history != null && !history.isEmpty();
		model.addAttribute("canReturn", canReturn);

		@SuppressWarnings("unchecked")
		Map<String, Object> restoreState = (Map<String, Object>) session.getAttribute(RESTORE_SESSION_KEY);
		Object restoreFlag = session.getAttribute("usingDecomposedAsOriginal");
		boolean restoreRequested = restoreFlag instanceof Boolean && (Boolean) restoreFlag && restoreState != null;
		if (restoreRequested) {
			System.out.println("[PageController] Using restoreState: " + gson.toJson(restoreState));
			model.addAttribute("currentRelationsManualJson", gson.toJson(restoreState.getOrDefault("manualPerTable", Collections.emptyList())));
			model.addAttribute("currentRelationsColumnsJson", gson.toJson(restoreState.getOrDefault("columnsPerTable", Collections.emptyList())));
			model.addAttribute("currentRelationsFdsJson", gson.toJson(restoreState.getOrDefault("fdsPerTable", Collections.emptyList())));
			model.addAttribute("currentRelationsFdsOriginalJson", gson.toJson(restoreState.getOrDefault("fdsPerTableOriginal", Collections.emptyList())));
			model.addAttribute("currentGlobalRicJson", gson.toJson(restoreState.getOrDefault("globalRic", Collections.emptyList())));
			model.addAttribute("currentUnionColsJson", gson.toJson(restoreState.getOrDefault("unionCols", Collections.emptyList())));
			model.addAttribute("currentGlobalManualRowsJson", gson.toJson(restoreState.getOrDefault("manualPerTable", Collections.emptyList())));
			model.addAttribute("initialCalcTableJson", "[]");
			model.addAttribute("ricJson", "[]");

			// make sure we don't reuse stale state on future loads
			session.removeAttribute(RESTORE_SESSION_KEY);
			session.removeAttribute("usingDecomposedAsOriginal");
		} else if (history != null && !history.isEmpty()) {
			Map<String, Object> currentState = history.get(history.size() - 1);
			System.out.println("[PageController] Using history tail state: " + gson.toJson(currentState));

			// Transfer the saved data to the model during "Continue"
			model.addAttribute("currentRelationsManualJson", gson.toJson(currentState.get("manualPerTable")));
			model.addAttribute("currentRelationsColumnsJson", gson.toJson(currentState.get("columnsPerTable")));
			model.addAttribute("currentRelationsFdsJson", gson.toJson(currentState.get("fdsPerTable")));
			model.addAttribute("currentRelationsFdsOriginalJson", gson.toJson(currentState.get("fdsPerTableOriginal")));
			model.addAttribute("currentGlobalRicJson", gson.toJson(currentState.get("globalRic")));
			model.addAttribute("currentUnionColsJson", gson.toJson(currentState.get("unionCols")));
			model.addAttribute("currentGlobalManualRowsJson", gson.toJson(currentState.get("manualPerTable")));
			model.addAttribute("initialCalcTableJson", "[]");
			model.addAttribute("ricJson", "[]");
		} else {
			// Step 1
			String initJson = (String) session.getAttribute("initialCalcTableJson");
			String ricJson = (String) session.getAttribute("originalTableJson");
			model.addAttribute("initialCalcTableJson", initJson != null ? initJson : "[]");
			model.addAttribute("ricJson", ricJson != null ? ricJson : "[]");
			// Set the fields used for the second stage to empty
			model.addAttribute("currentRelationsManualJson", "[]");
			model.addAttribute("currentRelationsColumnsJson", "[]");
			model.addAttribute("currentRelationsFdsJson", "[]");
			model.addAttribute("currentRelationsFdsOriginalJson", "[]");
			model.addAttribute("currentGlobalRicJson", "[]");
			model.addAttribute("currentUnionColsJson", "[]");
			model.addAttribute("currentGlobalManualRowsJson", "[]");
		}

		String fdList = (String) session.getAttribute("fdList");
		Set<String> userFds = new LinkedHashSet<>();
		if (fdList != null && !fdList.isBlank()) {
			String[] parts = fdList.split("[;\\r\\n]+");
			for (String p : parts) {
				String t = p == null ? "" : p.trim();
				if (t.isEmpty()) continue;
				t = t.replace('→', '-').replaceAll("-+>", "->");
				t = t.replaceAll("\\s*,\\s*", ",");
				userFds.add(t);
			}
		}

		// Get the original FD list from session and calculate only the transitive ones
		@SuppressWarnings("unchecked")
		List<FD> originalFDs = (List<FD>) session.getAttribute("originalFDs");
		if (originalFDs == null) {
			originalFDs = new ArrayList<>(); // Security measure
		}

		List<FD> transitiveFDs = fdService.findTransitiveFDs(originalFDs);

		// Sort original FDs within itself
		List<String> originalFdStrings = originalFDs.stream()
				.map(FD::toString)
				.sorted()
				.collect(Collectors.toList());

		// Sort derived (inferred) FDs within themselves
		List<String> transitiveFdStrings  = transitiveFDs.stream()
				.map(FD::toString)
				.sorted()
				.collect(Collectors.toList());

		// Create the final list to display: originals first, then derivatives
		List<String> allFdItemStrings = new ArrayList<>(originalFdStrings);
		allFdItemStrings.addAll(transitiveFdStrings);

		// Clear duplicate elements
		List<String> distinctFdItems = new ArrayList<>(new LinkedHashSet<>(allFdItemStrings));

		model.addAttribute("fdItems", originalFdStrings);

		// Only derived ones will be shown in red
		model.addAttribute("fdInferred", transitiveFdStrings);
		model.addAttribute("transitiveFdStrings", transitiveFdStrings);

		return "normalization";
	}
}