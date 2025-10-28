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
	private static final String RESET_SESSION_KEY = "normalizationReset";

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
			@RequestParam(value = "monteCarlo", required = false) String monteCarlo,
			@RequestParam(value = "samples", required = false) String samples,
			Model model) {

		// If it comes from the results page and has data/FD information, add it to the model
		if (inputData != null && !inputData.isEmpty()) {
			model.addAttribute("restoredInputData", inputData);
		}
		if (fdList != null && !fdList.isEmpty()) {
			model.addAttribute("restoredFdList", fdList);
		}

		if (monteCarlo != null) {
			model.addAttribute("restoredMonteCarlo", monteCarlo);
		}
		if (samples != null) {
			model.addAttribute("restoredSamples", samples);
		}

		return "calc";
	}

	@GetMapping("/calc-results")
	public String showCalcResults(HttpSession session, Model model) {

		@SuppressWarnings("unchecked")
		List<String[]> ricMatrix = (List<String[]>) session.getAttribute("calcResultsRicMatrix");
		Integer ricColCount = (Integer) session.getAttribute("calcResultsRicColCount");
		String ricJson = (String) session.getAttribute("calcResultsRicJson");
		String inputData = (String) session.getAttribute("calcResultsInputData");
		String fdList = (String) session.getAttribute("calcResultsFdList");
		@SuppressWarnings("unchecked")
		List<String> ricSteps = (List<String>) session.getAttribute("calcResultsRicSteps");
		String ricFinalStrategy = (String) session.getAttribute("calcResultsRicFinalStrategy");
		Boolean monteCarloSelected = (Boolean) session.getAttribute("calcResultsMonteCarloSelected");
		Integer monteCarloSamples = (Integer) session.getAttribute("calcResultsMonteCarloSamples");
		@SuppressWarnings("unchecked")
		List<String> allFdStrings = (List<String>) session.getAttribute("calcResultsAllFdStrings");
		@SuppressWarnings("unchecked")
		List<String> transitiveFds = (List<String>) session.getAttribute("calcResultsTransitiveFds");

		if (ricMatrix == null || inputData == null) {
			return "redirect:/calc";
		}

		model.addAttribute("ricMatrix", ricMatrix);
		model.addAttribute("ricColCount", ricColCount != null ? ricColCount : 0);
		model.addAttribute("ricJson", ricJson != null ? ricJson : "[]");
		model.addAttribute("inputData", inputData);
		model.addAttribute("fdList", fdList != null ? fdList : "");
		model.addAttribute("ricSteps", ricSteps != null ? ricSteps : List.of());
		model.addAttribute("ricFinalStrategy", ricFinalStrategy);
		model.addAttribute("monteCarloSelected", monteCarloSelected != null && monteCarloSelected);
		model.addAttribute("monteCarloSamples", monteCarloSamples != null ? monteCarloSamples : 100000);
		model.addAttribute("allFdStringsToShow", allFdStrings != null ? allFdStrings : List.of());
		model.addAttribute("transitiveFdStrings", transitiveFds != null ? transitiveFds : List.of());

		return "calc-results";
	}

	/**
	 * Render normalization page.
	 *     - currentRelationsManualJson    : List<String> (manual rows per decomposed table)
	 *     - currentRelationsColumnsJson   : List<List<Integer>> (columns per decomposed table)
	 *     - currentRelationsFdsJson       : List<String> (per-table FDs)
	 */
	@GetMapping("/normalization")
	public String normalizePage(HttpSession session, Model model) {
		Boolean resetRequested = (Boolean) session.getAttribute(RESET_SESSION_KEY);
        boolean initialStatePopulated = false;

		Long startTime = normalizationController.setAndGetNormalizationStartTime(session);
		model.addAttribute("normalizationStartTimeMs", startTime);

		Boolean alreadyBcnfFlag = (Boolean) session.getAttribute("alreadyBcnf");
		model.addAttribute("alreadyBcnf", alreadyBcnfFlag != null && alreadyBcnfFlag);

		@SuppressWarnings("unchecked")
		// Get history list from session
		List<Map<String, Object>> history = (List<Map<String, Object>>) session.getAttribute("normalizationHistory");

		boolean canReturn = history != null && !history.isEmpty();
		model.addAttribute("canReturn", canReturn);

		@SuppressWarnings("unchecked")
		Map<String, Object> restoreState = (Map<String, Object>) session.getAttribute(RESTORE_SESSION_KEY);
		Object restoreFlag = session.getAttribute("usingDecomposedAsOriginal");
		boolean restoreRequested = restoreFlag instanceof Boolean && (Boolean) restoreFlag && restoreState != null;
		if (Boolean.TRUE.equals(resetRequested)) {
			populateInitialNormalization(session, model);
			session.removeAttribute(RESET_SESSION_KEY);
			initialStatePopulated = true;
		}

		if (restoreRequested) {
			System.out.println("[PageController] Using restoreState: " + gson.toJson(restoreState));
			model.addAttribute("currentRelationsManualJson", gson.toJson(restoreState.getOrDefault("manualPerTable", Collections.emptyList())));
			model.addAttribute("currentRelationsColumnsJson", gson.toJson(restoreState.getOrDefault("columnsPerTable", Collections.emptyList())));
			model.addAttribute("currentRelationsFdsJson", gson.toJson(restoreState.getOrDefault("fdsPerTable", Collections.emptyList())));
			model.addAttribute("currentRelationsFdsOriginalJson", gson.toJson(restoreState.getOrDefault("fdsPerTableOriginal", Collections.emptyList())));
			model.addAttribute("currentRelationsRicJson", gson.toJson(restoreState.getOrDefault("ricPerTable", Collections.emptyList())));
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
			model.addAttribute("currentRelationsRicJson", gson.toJson(currentState.get("ricPerTable")));
			model.addAttribute("currentGlobalRicJson", gson.toJson(currentState.get("globalRic")));
			model.addAttribute("currentUnionColsJson", gson.toJson(currentState.get("unionCols")));
			model.addAttribute("currentGlobalManualRowsJson", gson.toJson(currentState.get("manualPerTable")));
			model.addAttribute("initialCalcTableJson", "[]");
			model.addAttribute("ricJson", "[]");
		} else if (!initialStatePopulated) {
			populateInitialNormalization(session, model);
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

	private void populateInitialNormalization(HttpSession session, Model model) {
		String initJson = (String) session.getAttribute("initialCalcTableJson");
		String ricJsonInit = (String) session.getAttribute("originalTableJson");
		model.addAttribute("initialCalcTableJson", initJson != null ? initJson : "[]");
		model.addAttribute("ricJson", ricJsonInit != null ? ricJsonInit : "[]");
		model.addAttribute("currentRelationsManualJson", "[]");
		model.addAttribute("currentRelationsColumnsJson", "[]");
		model.addAttribute("currentRelationsFdsJson", "[]");
		model.addAttribute("currentRelationsFdsOriginalJson", "[]");
		model.addAttribute("currentRelationsRicJson", "[]");
		model.addAttribute("currentGlobalRicJson", "[]");
		model.addAttribute("currentUnionColsJson", "[]");
		model.addAttribute("currentGlobalManualRowsJson", "[]");
	}
}