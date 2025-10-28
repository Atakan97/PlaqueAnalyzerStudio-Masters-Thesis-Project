package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.model.FD;
import com.project.plaque.plaque_calculator.service.FDService;
import com.project.plaque.plaque_calculator.service.RicService;
import com.project.plaque.plaque_calculator.service.DecomposeService;
import com.project.plaque.plaque_calculator.service.LogService;
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

@Controller
@RequestMapping("/compute")
public class ComputeController {

	private final FDService fdService;
	private final RicService ricService;
	private final LogService logService;
	private final DecomposeService decomposeService;
	private final Gson gson = new Gson();

	// Adding RicService in addition to FDService
	public ComputeController(FDService fdService, RicService ricService, LogService logService, DecomposeService decomposeService) {
		this.fdService = fdService;
		this.ricService = ricService;
		this.logService = logService;
		this.decomposeService = decomposeService;
	}

	@PostMapping
	public String compute(
			@RequestParam(required = false) String manualData,
			@RequestParam(required = false) String fds,
			@RequestParam(required = false, defaultValue = "false") boolean monteCarlo,
			@RequestParam(required = false, defaultValue = "100000") int samples,
			HttpSession session,
			Model model
	) {

		clearNormalizationSessionState(session);

		// Converting user's input to safe strings
		String safeManual = sanitizeManualData(manualData);
		String safeFds = sanitizeFds(fds);

		// Call RicService with adaptive Monte Carlo fallbacks so we can gracefully degrade
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
			ex.printStackTrace();
			model.addAttribute("ricError", "Error while calculating information content: " + ex.getMessage());
		}
		// Persist the execution trail in both model and session so UI pages and subsequent
		// requests (e.g., decomposition flows) can display the full timeline of attempts.
		model.addAttribute("ricSteps", ricSteps);
		model.addAttribute("ricFinalStrategy", finalStrategy);
		session.setAttribute("ricComputationSteps", ricSteps);
		session.setAttribute("ricFinalStrategy", finalStrategy);

		persistResults(session, model, safeManual, safeFds, ricArr, ricSteps, finalStrategy, monteCarlo, samples);
		return "calc-results";
	}

	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@ResponseBody
	public SseEmitter streamComputation(
			@RequestParam String manualData,
			@RequestParam(required = false) String fds,
			@RequestParam(required = false, defaultValue = "false") boolean monteCarlo,
			@RequestParam(required = false, defaultValue = "100000") int samples,
			HttpSession session
	) {
		clearNormalizationSessionState(session);

		String safeManual = sanitizeManualData(manualData);
		String safeFds = sanitizeFds(fds);

		SseEmitter emitter = new SseEmitter(0L);

		if (safeManual.isEmpty()) {
			sendEvent(emitter, "error", Map.of("message", "Table data is required for computation."));
			emitter.complete();
			return emitter;
		}

		CompletableFuture.runAsync(() -> {
			List<String> progressSteps = new ArrayList<>();
			Consumer<String> progressCallback = step -> {
				progressSteps.add(step);
				sendEvent(emitter, "progress", Map.of("message", step));
			};

			try {
				RicService.RicComputationResult result = ricService.computeRicAdaptive(
						safeManual,
						safeFds,
						monteCarlo,
						samples,
						progressCallback
				);

				List<String> finalSteps = result.steps() != null ? result.steps() : progressSteps;
				persistResults(session, null, safeManual, safeFds, result.matrix(), finalSteps, result.finalStrategy(), monteCarlo, samples);
				sendEvent(emitter, "complete", Map.of(
						"finalStrategy", result.finalStrategy(),
						"redirectUrl", "/calc-results"
				));
				emitter.complete();
			} catch (RicService.RicComputationException adaptiveEx) {
				List<String> steps = adaptiveEx.getSteps() != null ? adaptiveEx.getSteps() : progressSteps;
				steps.forEach(step -> sendEvent(emitter, "progress", Map.of("message", step)));
				sendEvent(emitter, "error", Map.of("message", adaptiveEx.getMessage()));
				emitter.complete();
			} catch (Exception ex) {
				sendEvent(emitter, "error", Map.of("message", ex.getMessage() == null ? "Unexpected error" : ex.getMessage()));
				emitter.complete();
			}
		});

		return emitter;
	}

	// Convert (and parsing) a string like "A->B;C->D;E->F,G" to List<FD>
	private List<FD> parseFdsString(String fds) {
		if (fds == null || fds.isBlank()) {
			return Collections.emptyList();
		}
		List<FD> result = new ArrayList<>();
		for (String part : fds.split(";")) {
			String[] sides = part.split("->");
			if (sides.length != 2) continue;
			Set<String> lhs = Arrays.stream(sides[0].split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.collect(Collectors.toSet());
			Set<String> rhs = Arrays.stream(sides[1].split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.collect(Collectors.toSet());
			if (!lhs.isEmpty() && !rhs.isEmpty()) {
				result.add(new FD(lhs, rhs));
			}
		}
		return result;
	}

	private String sanitizeManualData(String manualData) {
		String safeManual = Optional.ofNullable(manualData).orElse("").trim();
		if (safeManual.isEmpty()) {
			return safeManual;
		}
		return Arrays.stream(safeManual.split(";"))
				.map(String::trim)
				.filter(row -> !row.replace(",", "").trim().isEmpty())
				.collect(Collectors.joining(";"));
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
						 int samples) {
		List<String[]> matrixForModel = convertMatrixToStrings(ricArr);
		String originalTableJson = gson.toJson(matrixForModel);
		int ricColCount = matrixForModel.isEmpty() ? 0 : matrixForModel.get(0).length;
		List<FD> originalFDs = parseFdsString(safeFds);
		List<String> originalAttrOrder = extractAttrOrder(safeManual);
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
		session.setAttribute("calcResultsFdList", safeFds);
		session.setAttribute("calcResultsRicSteps", safeSteps);
		session.setAttribute("calcResultsRicFinalStrategy", finalStrategy);
		session.setAttribute("calcResultsMonteCarloSelected", monteCarlo);
		session.setAttribute("calcResultsMonteCarloSamples", samples);
		session.setAttribute("alreadyBcnf", alreadyBcnf);

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
		}

		List<List<String>> initialCalcTable = buildInitialCalcTable(safeManual);
		String initJson = gson.toJson(initialCalcTable);
		session.setAttribute("initialCalcTableJson", initJson);

		List<List<String>> dedupedOriginalTuples = dedupeRows(initialCalcTable);
		session.setAttribute("originalTuples", dedupedOriginalTuples);

		session.setAttribute("originalAttrOrder", originalAttrOrder);
		session.setAttribute("originalAttrIndices", createAttrIndices(originalAttrOrder.size()));

		session.setAttribute("originalFDs", originalFDs);

		List<FD> transitiveFDs = fdService.findTransitiveFDs(originalFDs);
		List<String> originalFdStrings = originalFDs.stream().map(FD::toString).sorted().collect(Collectors.toList());
		List<String> transitiveFdStrings = transitiveFDs.stream().map(FD::toString).sorted().collect(Collectors.toList());
		List<String> distinctSortedList = new ArrayList<>(new LinkedHashSet<>(combineLists(originalFdStrings, transitiveFdStrings)));

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
		if (safeManual == null || safeManual.isBlank()) {
			return List.of();
		}
		return Arrays.stream(safeManual.split(";"))
				.filter(row -> row != null && !row.isBlank())
				.map(row -> Arrays.stream(row.split(","))
						.map(String::trim)
						.collect(Collectors.toList()))
				.collect(Collectors.toList());
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
		if (safeManual == null || safeManual.isBlank()) {
			return List.of();
		}
		String firstRow = safeManual.split(";", 2)[0];
		return Arrays.stream(firstRow.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.toList());
	}

	private List<Integer> createAttrIndices(int size) {
		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			indices.add(i);
		}
		return indices;
	}

	private <T> List<T> combineLists(List<T> first, List<T> second) {
		List<T> combined = new ArrayList<>(first);
		combined.addAll(second);
		return combined;
	}

	private void sendEvent(SseEmitter emitter, String eventName, Object data) {
		try {
			emitter.send(SseEmitter.event().name(eventName).data(data));
		} catch (IOException ex) {
			emitter.completeWithError(ex);
		}
	}
}
