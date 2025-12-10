package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.model.FD;
import com.project.plaque.plaque_calculator.service.FDService;
import com.project.plaque.plaque_calculator.service.RicService;
import com.project.plaque.plaque_calculator.service.DecomposeService;
import com.project.plaque.plaque_calculator.util.CsvParsingUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.UUID;

@Controller
@RequestMapping("/compute")
public class ComputeController {

	private final FDService fdService;
	private final RicService ricService;
	private final DecomposeService decomposeService;
	private final Gson gson = new Gson();

	public ComputeController(FDService fdService, RicService ricService, DecomposeService decomposeService) {
		this.fdService = fdService;
		this.ricService = ricService;
		this.decomposeService = decomposeService;
	}

	@PostMapping
	public String compute(
			@RequestParam(required = false) String manualData,
			@RequestParam(required = false) String fds,
			@RequestParam(required = false, defaultValue = "false") boolean monteCarlo,
			@RequestParam(required = false, defaultValue = "100000") int samples,
			@RequestParam(required = false, defaultValue = "0") int duplicatesRemoved,
			HttpSession session,
			Model model
	) {

		clearNormalizationSessionState(session);

		// Converting user's input to safe strings
		String safeManual = sanitizeManualData(manualData);
		String safeFds = sanitizeFds(fds);

		// Call RicService with adaptive Monte Carlo fallbacks so we can degrade
		// from exact computation to approximations when the external jar hits timeouts.
		double[][] ricArr = new double[0][0];
		List<String> ricSteps = new ArrayList<>();
		String finalStrategy = null;
		try {
			RicService.RicComputationResult result = ricService.computeRicAdaptive(safeManual, safeFds, monteCarlo, samples);
			ricArr = result.matrix();
			ricSteps = result.steps();
			finalStrategy = result.finalStrategy();
		} catch (RicService.RicComputationException adaptiveEx) {
			ricSteps = adaptiveEx.getSteps();
			model.addAttribute("ricError", "Error while calculating information content: " + adaptiveEx.getMessage());
		} catch (Exception ex) {
			System.err.println("[ComputeController] Error during RIC computation: " + ex.getMessage());
			model.addAttribute("ricError", "Error while calculating information content: " + ex.getMessage());
		}
		// Persist the execution trail in both model and session so UI pages and subsequent
		// requests (e.g., decomposition flows) can display the full timeline of attempts.
		model.addAttribute("ricSteps", ricSteps);
		model.addAttribute("ricFinalStrategy", finalStrategy);
		session.setAttribute("ricComputationSteps", ricSteps);
		session.setAttribute("ricFinalStrategy", finalStrategy);

		persistResults(session, model, safeManual, safeFds, ricArr, ricSteps, finalStrategy, monteCarlo, samples, duplicatesRemoved);
		return "calc-results";
	}

	@PostMapping(value = "/stream-init", produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public Map<String, String> initStream(
			@RequestParam String manualData,
			@RequestParam(required = false) String fds,
			@RequestParam(required = false, defaultValue = "false") boolean monteCarlo,
			@RequestParam(required = false, defaultValue = "100000") int samples,
			@RequestParam(required = false, defaultValue = "0") int duplicatesRemoved,
			HttpSession session
	) {
		try {
			// Do not clear normalization state here; actual stream will do that.
			// Keep original format for UI/session storage
			String originalManual = manualData;
			// Convert to RIC-compatible format for JAR
			String ricManual = sanitizeManualData(manualData);
			String safeFds = sanitizeFds(fds);

			String token = UUID.randomUUID().toString();
			Map<String, Object> payload = new HashMap<>();
			payload.put("originalManualData", originalManual);  // Original format with quotes
			payload.put("manualData", ricManual);               // RIC format with | instead of ,
			payload.put("fds", safeFds);
			payload.put("monteCarlo", monteCarlo);
			payload.put("samples", samples);
			payload.put("duplicatesRemoved", duplicatesRemoved);
			storeComputeRequest(session, token, payload);
			return Map.of("token", token);
		} catch (Exception ex) {
			throw ex;
		}
	}

	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@ResponseBody
	public SseEmitter streamComputation(
			@RequestParam(required = false) String manualData,
			@RequestParam(required = false) String fds,
			@RequestParam(required = false, defaultValue = "false") boolean monteCarlo,
			@RequestParam(required = false, defaultValue = "100000") int samples,
			@RequestParam(required = false) String token,
			HttpSession session) {

		Map<String, Object> payloadFromToken = null;
		if (token != null && !token.isBlank()) {
			payloadFromToken = takeComputeRequest(session, token);
			if (payloadFromToken == null) {
				SseEmitter bad = new SseEmitter(0L);
				sendEvent(bad, "error", Map.of("message", "Invalid or expired token."));
				bad.complete();
				return bad;
			}
		}

		clearNormalizationSessionState(session);
		String ricManual;      // RIC format for JAR (commas replaced with |)
		String originalManual; // Original format for UI/session
		String safeFds;
		boolean mc;
		int smp;
		int duplicatesRemoved;
		try {
			if (payloadFromToken != null) {
				// Data from token: ricManual is already in RIC format, originalManual is original
				ricManual = String.valueOf(payloadFromToken.getOrDefault("manualData", ""));
				originalManual = String.valueOf(payloadFromToken.getOrDefault("originalManualData", ""));
				// If originalManualData wasn't stored (backward compatibility), use ricManual
				if (originalManual.isEmpty()) {
					originalManual = ricManual;
				}
				safeFds = String.valueOf(payloadFromToken.getOrDefault("fds", ""));
				Object mcObj = payloadFromToken.get("monteCarlo");
				mc = mcObj instanceof Boolean ? (Boolean) mcObj : Boolean.parseBoolean(String.valueOf(mcObj));
				Object spObj = payloadFromToken.get("samples");
				smp = spObj instanceof Integer ? (Integer) spObj : Integer.parseInt(String.valueOf(spObj));
				Object dupObj = payloadFromToken.get("duplicatesRemoved");
				duplicatesRemoved = dupObj instanceof Integer ? (Integer) dupObj : Integer.parseInt(String.valueOf(dupObj));
				System.out.println("[ComputeController] ricManual length: " + ricManual.length());
				System.out.println("[ComputeController] safeFds: " + safeFds);
				System.out.println("[ComputeController] mc: " + mc + ", smp: " + smp);
				System.out.flush();
			} else {
				originalManual = manualData;
				ricManual = sanitizeManualData(manualData);
				safeFds = sanitizeFds(fds);
				mc = monteCarlo;
				smp = samples;
				duplicatesRemoved = 0;
			}
		} catch (Exception ex) {
			SseEmitter bad = new SseEmitter(0L);
			sendEvent(bad, "error", Map.of("message", "Failed to sanitize input: " + ex.getMessage()));
			bad.complete();
			return bad;
		}

		String validated = validateAndReturnOrError(ricManual);
		System.out.println("[ComputeController] Validation result: " + (validated.equals("__INCONSISTENT__") ? "INCONSISTENT" : (validated.isEmpty() ? "EMPTY" : "OK, length=" + validated.length())));
		System.out.flush();

		// Use a longer timeout (5 minutes) for large computations
		SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

		if (validated.equals("__INCONSISTENT__")) {
			sendEvent(emitter, "error", Map.of("message", "Inconsistent column counts detected across rows."));
			emitter.complete();
			return emitter;
		}
		if (validated.isEmpty()) {
			sendEvent(emitter, "error", Map.of("message", "Table data is required for computation."));
			emitter.complete();
			return emitter;
		}

		final String finalRicManual = validated;        // RIC format for JAR
		final String finalOriginalManual = originalManual; // Original format for session/UI

		// Calculate row/col count safely
		String[] rows = finalRicManual.split(";");
		int rowCount = rows.length;
		int colCount = rows.length > 0 ? rows[0].split(",", -1).length : 0;
		System.out.println("[ComputeController] RIC Stream starting. rows=" + rowCount + ", cols=" + colCount + ", length=" + finalRicManual.length());
		System.out.flush();

		// Send initial keep-alive event immediately
		try {
			sendEvent(emitter, "progress", Map.of("message", "Initializing computation..."));
		} catch (Exception e) {
			System.err.println("[ComputeController] Failed to send initial event: " + e.getMessage());
		}

		CompletableFuture.runAsync(() -> {
			List<String> progressSteps = new ArrayList<>();
			Consumer<String> progressCallback = step -> {
				progressSteps.add(step);
				try {
					sendEvent(emitter, "progress", Map.of("message", step));
				} catch (Exception ignored) {
				}
			};
			try {
				System.out.println("[ComputeController] Starting RIC computation...");
				// Use RIC format for JAR computation
				RicService.RicComputationResult result = ricService.computeRicAdaptive(finalRicManual, safeFds, mc, smp, progressCallback);
				System.out.println("[ComputeController] RIC computation completed, persisting results...");
				List<String> finalSteps = result.steps() != null ? result.steps() : progressSteps;
				// Use ORIGINAL format for session storage (so UI shows correct values)
				persistResults(session, null, finalOriginalManual, safeFds, result.matrix(), finalSteps, result.finalStrategy(), mc, smp, duplicatesRemoved);
				System.out.println("[ComputeController] Results persisted, sending complete event...");
				sendEvent(emitter, "complete", Map.of("finalStrategy", result.finalStrategy(), "redirectUrl", "/calc-results"));
				emitter.complete();
				System.out.println("[ComputeController] Stream completed successfully.");
			} catch (RicService.RicComputationException adaptiveEx) {
				System.err.println("[ComputeController] RicComputationException: " + adaptiveEx.getMessage());
				List<String> steps = adaptiveEx.getSteps() != null ? adaptiveEx.getSteps() : progressSteps;
				steps.forEach(step -> sendEvent(emitter, "progress", Map.of("message", step)));
				sendEvent(emitter, "error", Map.of("message", adaptiveEx.getMessage()));
				emitter.complete();
			} catch (Exception ex) {
				System.err.println("[ComputeController] Unexpected exception in async block: " + ex.getClass().getName() + " - " + ex.getMessage());
				ex.printStackTrace();
				try {
					sendEvent(emitter, "error", Map.of("message", ex.getMessage() == null ? "Unexpected error" : ex.getMessage()));
					emitter.complete();
				} catch (Exception e) {
					System.err.println("[ComputeController] Failed to send error event: " + e.getMessage());
					emitter.completeWithError(ex);
				}
			}
		});
		return emitter;
	}

	private String sanitizeFds(String fds) {
		return Optional.ofNullable(fds).orElse("").trim();
	}

	private void clearNormalizationSessionState(HttpSession session) {
		if (session == null) return;
		session.removeAttribute("normalizationHistory");
		session.removeAttribute("normalizationRestoreState");
		session.removeAttribute("usingDecomposedAsOriginal");
		session.removeAttribute("currentRelationsManual");
		session.removeAttribute("currentRelationsColumns");
		session.removeAttribute("currentRelationsFds");
		session.removeAttribute("currentRelationsRic");
		session.removeAttribute("currentGlobalRic");
		session.removeAttribute("currentGlobalManualRows");
		session.removeAttribute("currentUnionCols");
		session.removeAttribute("alreadyBcnf");
		session.removeAttribute("normalizationSessionStart");
		session.removeAttribute("bcnfSummary");
		session.removeAttribute("bcnfAttempts");
		session.removeAttribute("bcnfElapsedTime");
		session.removeAttribute("bcnfTableCount");
		session.removeAttribute("bcnfDependencyPreserved");
		session.removeAttribute("_lastDecomposeResult");
		session.removeAttribute("_lastBcnfMeta");
	}

	private void persistResults(HttpSession session,
						 Model model,
						 String safeManual,
						 String safeFds,
						 double[][] ricArr,
						 List<String> steps,
						 String finalStrategy,
						 boolean monteCarlo,
						 int samples,
						 int duplicatesRemoved) {
		List<String[]> matrixForModel = convertMatrixToStrings(ricArr);
		String originalTableJson = gson.toJson(matrixForModel);
		int ricColCount = matrixForModel.isEmpty() ? 0 : matrixForModel.get(0).length;
		List<String> originalAttrOrder = extractAttrOrder(safeManual);
		// Parse FDs with index support - converts column indexes (1-based) to attribute names
		List<FD> originalFDs = fdService.parseFDStringWithIndexes(safeFds, originalAttrOrder);
		Set<String> attributeSet = new LinkedHashSet<>(originalAttrOrder);
		boolean alreadyBcnf = attributeSet.isEmpty()
			? originalFDs.isEmpty()
			: decomposeService.checkBCNF(attributeSet, originalFDs, fdService);

		List<String> safeSteps = steps == null ? List.of() : List.copyOf(steps);

		session.setAttribute("originalTableJson", originalTableJson);
		session.setAttribute("calcResultsRicMatrix", matrixForModel);
		session.setAttribute("calcResultsRicColCount", ricColCount);
		session.setAttribute("calcResultsRicJson", originalTableJson);
		session.setAttribute("ricComputationSteps", safeSteps);
		session.setAttribute("ricFinalStrategy", finalStrategy);
		session.setAttribute("fdList", safeFds);
		session.setAttribute("calcResultsInputData", safeManual);
		// Also store as JSON for proper restoration in calc.html
		List<List<String>> parsedData = CsvParsingUtil.parseRows(safeManual);
		session.setAttribute("calcResultsInputDataJson", gson.toJson(parsedData));
		session.setAttribute("calcResultsFdList", safeFds);
		session.setAttribute("calcResultsRicSteps", safeSteps);
		session.setAttribute("calcResultsRicFinalStrategy", finalStrategy);
		session.setAttribute("calcResultsMonteCarloSelected", monteCarlo);
		session.setAttribute("calcResultsMonteCarloSamples", samples);
		session.setAttribute("alreadyBcnf", alreadyBcnf);
		session.setAttribute("duplicatesRemoved", duplicatesRemoved);

		if (model != null) {
			model.addAttribute("ricMatrix", matrixForModel);
			model.addAttribute("ricColCount", ricColCount);
			model.addAttribute("ricJson", originalTableJson);
			model.addAttribute("ricSteps", safeSteps);
			model.addAttribute("ricFinalStrategy", finalStrategy);
			model.addAttribute("inputData", safeManual);
			model.addAttribute("fdList", safeFds);
			model.addAttribute("monteCarloSelected", monteCarlo);
			model.addAttribute("monteCarloSamples", samples);
			model.addAttribute("alreadyBcnf", alreadyBcnf);
			model.addAttribute("duplicatesRemoved", duplicatesRemoved);
		}

		List<List<String>> initialCalcTable = buildInitialCalcTable(safeManual);
		String initJson = gson.toJson(initialCalcTable);
		session.setAttribute("initialCalcTableJson", initJson);

		List<List<String>> dedupedOriginalTuples = dedupeRows(initialCalcTable);
		session.setAttribute("originalTuples", dedupedOriginalTuples);

		session.setAttribute("originalAttrOrder", originalAttrOrder);
		session.setAttribute("originalAttrIndices", createAttrIndices(originalAttrOrder.size()));

		session.setAttribute("originalFDs", originalFDs);

		// Parse original FD strings for display (keep original index format like "1,2,3->5")
		List<String> originalFdStringsForDisplay = parseOriginalFdStringsForDisplay(safeFds);
		session.setAttribute("originalFdStringsForDisplay", originalFdStringsForDisplay);

		List<FD> transitiveFDs = fdService.findTransitiveFDs(originalFDs);
		// For internal use (with attribute names)
		List<String> originalFdStrings = originalFDs.stream().map(FD::toString).sorted().collect(Collectors.toList());
		List<String> transitiveFdStrings = transitiveFDs.stream().map(FD::toString).sorted().collect(Collectors.toList());
		List<String> distinctSortedList = new ArrayList<>(new LinkedHashSet<>(combineLists(originalFdStrings, transitiveFdStrings)));

		// Calculate transitive FDs in display format (using indices)
		// We parse the original display FDs (index-based), find transitive FDs, and convert back to display format
		List<FD> originalFDsForDisplay = fdService.parseFdsFromDisplayStrings(originalFdStringsForDisplay);
		List<FD> transitiveFDsForDisplay = fdService.findTransitiveFDs(originalFDsForDisplay);
		List<String> transitiveFdStringsForDisplay = transitiveFDsForDisplay.stream()
				.map(FD::toString)
				.sorted()
				.collect(Collectors.toList());
		session.setAttribute("transitiveFdStringsForDisplay", transitiveFdStringsForDisplay);

		session.setAttribute("fdListWithClosure", String.join(";", distinctSortedList));
		session.setAttribute("calcResultsAllFdStrings", distinctSortedList);
		session.setAttribute("calcResultsTransitiveFds", transitiveFdStrings);

		if (model != null) {
			model.addAttribute("allFdStringsToShow", distinctSortedList);
			model.addAttribute("transitiveFdStrings", transitiveFdStrings);
		}
	}

	private List<String[]> convertMatrixToStrings(double[][] ricArr) {
		List<String[]> rows = new ArrayList<>();
		if (ricArr == null) {
			return rows;
		}
		for (double[] row : ricArr) {
			if (row == null) {
				continue;
			}
			String[] rowStr = new String[row.length];
			for (int i = 0; i < row.length; i++) {
				rowStr[i] = String.valueOf(row[i]);
			}
			rows.add(rowStr);
		}
		return rows;
	}

	private List<List<String>> buildInitialCalcTable(String safeManual) {
		return CsvParsingUtil.parseRows(safeManual);
	}
	private List<List<String>> dedupeRows(List<List<String>> rows) {
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<List<String>> deduped = new ArrayList<>();
		for (List<String> row : rows) {
			List<String> cleaned = row.stream().map(cell -> cell == null ? "" : cell.trim()).collect(Collectors.toList());
			String key = String.join("|", cleaned);
			if (seen.add(key)) {
				deduped.add(cleaned);
			}
		}
		return deduped;
	}


	private List<String> extractAttrOrder(String safeManual) {
		List<List<String>> rows = CsvParsingUtil.parseRows(safeManual);
		if (rows.isEmpty()) {
			return List.of();
		}
		return rows.get(0).stream()
				.map(cell -> cell == null ? "" : cell.trim())
				.filter(s -> !s.isEmpty())
				.collect(Collectors.toList());
	}

	private List<Integer> createAttrIndices(int size) {
		return java.util.stream.IntStream.range(0, size)
				.boxed()
				.collect(Collectors.toList());
	}

	private <T> List<T> combineLists(List<T> first, List<T> second) {
		List<T> combined = new ArrayList<>(first);
		combined.addAll(second);
		return combined;
	}

	@SuppressWarnings("unchecked")
	private void storeComputeRequest(HttpSession session, String token, Map<String, Object> payload) {
		Object attr = session.getAttribute("computeRequests");
		Map<String, Map<String, Object>> map;
		if (attr instanceof Map) {
			map = (Map<String, Map<String, Object>>) attr;
		} else {
			map = new HashMap<>();
			session.setAttribute("computeRequests", map);
		}
		map.put(token, payload);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> takeComputeRequest(HttpSession session, String token) {
		Object attr = session.getAttribute("computeRequests");
		if (!(attr instanceof Map)) return null;
		Map<String, Map<String, Object>> map = (Map<String, Map<String, Object>>) attr;
		return map.remove(token);
	}

	private void sendEvent(SseEmitter emitter, String eventName, Object data) {
		try {
			emitter.send(SseEmitter.event().name(eventName).data(data));
		} catch (IOException ex) {
			emitter.completeWithError(ex);
		}
	}

	// Replace current sanitizeManualData with lightweight version
	private String sanitizeManualData(String manualData) {
		if (manualData == null || manualData.trim().isEmpty()) return "";
		try {
			// Parse and re-serialize using RIC-compatible format
			// This handles values containing commas by replacing them with a safe character
			List<List<String>> rows = CsvParsingUtil.parseRows(manualData);
			System.out.println("[ComputeController] sanitizeManualData: parsed " + rows.size() + " rows");
			if (!rows.isEmpty()) {
				System.out.println("[ComputeController] First row columns: " + rows.get(0).size());
				// Check for inconsistent column counts during parse
				int expectedCols = rows.get(0).size();
				for (int i = 0; i < rows.size(); i++) {
					if (rows.get(i).size() != expectedCols) {
						System.out.println("[ComputeController] WARNING: Row " + i + " has " + rows.get(i).size() + " columns (expected " + expectedCols + ")");
						System.out.println("[ComputeController] Row " + i + " content: " + rows.get(i));
					}
				}
			}
			if (rows.isEmpty()) {
				return "";
			}
			return CsvParsingUtil.toRicCompatibleString(rows);
		} catch (Exception ex) {
			// Rethrow to make the error visible
			throw new RuntimeException("Failed to sanitize manual data: " + ex.getMessage(), ex);
		}
	}

	// Simplify validation: just consistent column counts
	// Note: Input is expected to be in RIC-compatible format (commas replaced with |, semicolon row separator)
	private String validateAndReturnOrError(String safeManual) {
		if (safeManual == null || safeManual.isEmpty()) return "";

		// Use CsvParsingUtil to properly parse the data respecting quotes
		// This ensures semicolons inside quoted values are not treated as row separators
		List<List<String>> parsedRows = CsvParsingUtil.parseRows(safeManual);

		if (parsedRows.isEmpty()) {
			return "";
		}

		// Check column count consistency
		int expectedCols = parsedRows.get(0).size();

		for (int i = 0; i < parsedRows.size(); i++) {
			List<String> row = parsedRows.get(i);
			if (row.size() != expectedCols) {
				return "__INCONSISTENT__";
			}
		}

		// Return the RIC-compatible string
		return CsvParsingUtil.toRicCompatibleString(parsedRows);
	}

	/**
	 * Parse FD strings for display, keeping original format (e.g., "1,2,3->5")
	 * Normalizes arrow format to →
	 */
	private List<String> parseOriginalFdStringsForDisplay(String fdStr) {
		if (fdStr == null || fdStr.isBlank()) {
			return Collections.emptyList();
		}
		List<String> result = new ArrayList<>();
		// Normalize arrows to →
		fdStr = fdStr.replace("->", "→");
		for (String part : fdStr.split(";")) {
			part = part.trim();
			if (part.isEmpty()) continue;
			// Normalize whitespace around commas
			part = part.replaceAll("\\s*,\\s*", ",");
			result.add(part);
		}
		return result;
	}
}
