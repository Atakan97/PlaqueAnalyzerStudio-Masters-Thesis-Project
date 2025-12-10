package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.model.FD;
import com.project.plaque.plaque_calculator.service.FDService;
import com.project.plaque.plaque_calculator.service.NormalFormChecker;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.*;

@Controller
public class PageController {

	private final NormalizationController normalizationController;
	private final FDService fdService;
	private final NormalFormChecker normalFormChecker;
	private final Gson gson = new Gson();
	private static final String RESTORE_SESSION_KEY = "normalizationRestoreState";
	private static final String RESET_SESSION_KEY = "normalizationReset";

	public PageController(NormalizationController normalizationController, FDService fdService, NormalFormChecker normalFormChecker) {
		this.normalizationController = normalizationController;
		this.fdService = fdService;
		this.normalFormChecker = normalFormChecker;
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
			@RequestParam(value = "inputDataJson", required = false) String inputDataJson,
			@RequestParam(value = "fdList", required = false) String fdList,
			@RequestParam(value = "monteCarlo", required = false) String monteCarlo,
			@RequestParam(value = "samples", required = false) String samples,
			Model model) {

		// Prefer JSON format for restoration (avoids semicolon splitting issues)
		if (inputDataJson != null && !inputDataJson.isEmpty()) {
			model.addAttribute("restoredInputDataJson", inputDataJson);
		} else if (inputData != null && !inputData.isEmpty()) {
			// Fallback to legacy format
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

	@org.springframework.web.bind.annotation.PostMapping("/calc")
	public String postCalcPage(
			@RequestParam(value = "inputData", required = false) String inputData,
			@RequestParam(value = "inputDataJson", required = false) String inputDataJson,
			@RequestParam(value = "fdList", required = false) String fdList,
			@RequestParam(value = "monteCarlo", required = false) String monteCarlo,
			@RequestParam(value = "samples", required = false) String samples,
			Model model) {

		// Prefer JSON format for restoration (avoids semicolon splitting issues)
		if (inputDataJson != null && !inputDataJson.isEmpty()) {
			model.addAttribute("restoredInputDataJson", inputDataJson);
		} else if (inputData != null && !inputData.isEmpty()) {
			// Fallback to legacy format
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
		Integer duplicatesRemoved = (Integer) session.getAttribute("duplicatesRemoved");

		// Get properly parsed table data from session (parsed by CsvParsingUtil)
		String initialCalcTableJson = (String) session.getAttribute("initialCalcTableJson");
		@SuppressWarnings("unchecked")
		List<List<String>> parsedInputData = (List<List<String>>) session.getAttribute("originalTuples");

		if (ricMatrix == null || inputData == null) {
			return "redirect:/calc";
		}

		model.addAttribute("ricMatrix", ricMatrix);
		model.addAttribute("ricColCount", ricColCount != null ? ricColCount : 0);
		model.addAttribute("ricJson", ricJson != null ? ricJson : "[]");
		model.addAttribute("inputData", inputData);
		// Add JSON format for proper restoration in calc.html (avoids semicolon splitting issues)
		String inputDataJson = (String) session.getAttribute("calcResultsInputDataJson");
		model.addAttribute("inputDataJson", inputDataJson != null ? inputDataJson : "[]");
		model.addAttribute("fdList", fdList != null ? fdList : "");
		model.addAttribute("ricSteps", ricSteps != null ? ricSteps : List.of());
		model.addAttribute("ricFinalStrategy", ricFinalStrategy);
		model.addAttribute("monteCarloSelected", monteCarloSelected != null && monteCarloSelected);
		model.addAttribute("monteCarloSamples", monteCarloSamples != null ? monteCarloSamples : 100000);
		model.addAttribute("allFdStringsToShow", allFdStrings != null ? allFdStrings : List.of());
		model.addAttribute("transitiveFdStrings", transitiveFds != null ? transitiveFds : List.of());
		model.addAttribute("duplicatesRemoved", duplicatesRemoved != null ? duplicatesRemoved : 0);
		// Add properly parsed input data for display
		model.addAttribute("parsedInputData", parsedInputData != null ? parsedInputData : List.of());
		model.addAttribute("initialCalcTableJson", initialCalcTableJson != null ? initialCalcTableJson : "[]");

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

			// Calculate normal forms for each relation
			List<String> normalForms = calculateNormalFormsForRelations(restoreState, session);
			model.addAttribute("currentRelationsNormalFormsJson", gson.toJson(normalForms));

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

			// Calculate normal forms for each relation
			List<String> normalForms = calculateNormalFormsForRelations(currentState, session);
			model.addAttribute("currentRelationsNormalFormsJson", gson.toJson(normalForms));

			model.addAttribute("initialCalcTableJson", "[]");
			model.addAttribute("ricJson", "[]");
		} else if (!initialStatePopulated) {
			populateInitialNormalization(session, model);
		}

		// Get the original FD strings for display (in index format like "1,2,3→5")
		@SuppressWarnings("unchecked")
		List<String> originalFdStringsForDisplay = (List<String>) session.getAttribute("originalFdStringsForDisplay");
		if (originalFdStringsForDisplay == null) {
			originalFdStringsForDisplay = new ArrayList<>();
		}

		// Get transitive FD strings for display (in index format)
		@SuppressWarnings("unchecked")
		List<String> transitiveFdStringsForDisplay = (List<String>) session.getAttribute("transitiveFdStringsForDisplay");
		if (transitiveFdStringsForDisplay == null) {
			transitiveFdStringsForDisplay = new ArrayList<>();
		}

		model.addAttribute("fdItems", originalFdStringsForDisplay);

		// Only derived ones will be shown in red
		model.addAttribute("fdInferred", transitiveFdStringsForDisplay);
		model.addAttribute("transitiveFdStrings", transitiveFdStringsForDisplay);

		return "normalization";
	}

	private void populateInitialNormalization(HttpSession session, Model model) {
		String initJson = (String) session.getAttribute("initialCalcTableJson");
		String ricJsonInit = (String) session.getAttribute("originalTableJson");

		// Calculate original table's normal form
		String originalNormalForm = "1NF"; // Default
		try {
			@SuppressWarnings("unchecked")
			List<FD> originalFDs = (List<FD>) session.getAttribute("originalFDs");
			@SuppressWarnings("unchecked")
			List<String> originalAttrOrder = (List<String>) session.getAttribute("originalAttrOrder");

			if (originalFDs != null && originalAttrOrder != null && !originalAttrOrder.isEmpty()) {
				Set<String> attributes = new LinkedHashSet<>(originalAttrOrder);
				originalNormalForm = normalFormChecker.checkNormalForm(attributes, originalFDs);
			}
		} catch (Exception e) {
			System.err.println("Error calculating original normal form: " + e.getMessage());
		}

		model.addAttribute("originalNormalForm", originalNormalForm);
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

	// Calculate normal forms for each relation in the state
	private List<String> calculateNormalFormsForRelations(Map<String, Object> state, HttpSession session) {
		List<String> normalForms = new ArrayList<>();

		try {
			@SuppressWarnings("unchecked")
			List<String> fdsPerTableOriginal = (List<String>) state.get("fdsPerTableOriginal");
			@SuppressWarnings("unchecked")
			List<List<Integer>> columnsPerTable = (List<List<Integer>>) state.get("columnsPerTable");
			@SuppressWarnings("unchecked")
			List<String> originalAttrOrder = (List<String>) session.getAttribute("originalAttrOrder");

			if (fdsPerTableOriginal == null || columnsPerTable == null) {
				return normalForms;
			}

			// For each table
			for (int i = 0; i < columnsPerTable.size(); i++) {
				String normalForm = "1NF"; // Default

				try {
					List<Integer> cols = columnsPerTable.get(i);
					String fdStr = i < fdsPerTableOriginal.size() ? fdsPerTableOriginal.get(i) : "";

					// Build attributes set
					Set<String> attributes = new LinkedHashSet<>();
					if (originalAttrOrder != null && cols != null) {
						for (Integer colIdx : cols) {
							if (colIdx >= 0 && colIdx < originalAttrOrder.size()) {
								attributes.add(originalAttrOrder.get(colIdx));
							}
						}
					}

					// Parse FDs for this table
					List<FD> tableFDs = parseFDString(fdStr, originalAttrOrder);

					// Calculate normal form
					if (!attributes.isEmpty()) {
						normalForm = normalFormChecker.checkNormalForm(attributes, tableFDs);
					}
				} catch (Exception e) {
					System.err.println("Error calculating normal form for table " + i + ": " + e.getMessage());
				}

				normalForms.add(normalForm);
			}
		} catch (Exception e) {
			System.err.println("Error calculating normal forms: " + e.getMessage());
		}

		return normalForms;
	}

	/**
	 * Parse FD string to FD objects
	 * Format: "1->2;1,3->4" or "1→2;1,3→4"
	 * Delegates to FDService for parsing
	 */
	private List<FD> parseFDString(String fdStr, List<String> originalAttrOrder) {
		// Delegate to FDService
		return fdService.parseFDStringWithIndexes(fdStr, originalAttrOrder);
	}
}