package com.project.plaque.plaque_calculator.controller;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.model.FD;
import com.project.plaque.plaque_calculator.service.FDService;
import com.project.plaque.plaque_calculator.service.RicService;
import com.project.plaque.plaque_calculator.service.LogService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/compute")
public class ComputeController {

	private final FDService fdService;
	private final RicService ricService;
	private final LogService logService;
	private final Gson gson = new Gson();

	// Adding RicService in addition to FDService
	public ComputeController(FDService fdService, RicService ricService, LogService logService) {
		this.fdService = fdService;
		this.ricService = ricService;
		this.logService = logService;
	}

	@PostMapping
	public String compute(
			@RequestParam(required = false) String manualData,
			@RequestParam(required = false) String fds,
			@RequestParam(required = false, defaultValue = "false") boolean monteCarlo,
			@RequestParam(required = false, defaultValue = "50000") int samples,
			HttpSession session,
			Model model
	) {

		// Clear old normalization history when a new calculation starts
		session.removeAttribute("normalizationHistory");
		session.removeAttribute("fdList");
		session.removeAttribute("fdListWithClosure");
		session.removeAttribute("originalFDs");
		session.removeAttribute("usingDecomposedAsOriginal");
		session.removeAttribute("currentRelationsManual");
		session.removeAttribute("currentRelationsColumns");
		session.removeAttribute("currentRelationsFds");

		// Converting user's input to safe strings
		String safeManual = Optional.ofNullable(manualData).orElse("").trim();
		String safeFds = Optional.ofNullable(fds).orElse("").trim();

		// Checking whether the incoming data is empty after processing the rows
		// If there are empty rows, remove them and get the filled rows
		if (!safeManual.isEmpty()) {
			safeManual = Arrays.stream(safeManual.split(";"))
					.map(String::trim)
					.filter(row -> !row.replace(",", "").trim().isEmpty())
					.collect(Collectors.joining(";"));
		}

		// Call RicService (monteCarlo and samples forwarded)
		double[][] ricArr = new double[0][0];
		String errorMessage = null;
		try {
			ricArr = ricService.computeRicFromManualData(safeManual, safeFds, monteCarlo, samples);
		} catch (Exception ex) {
			ex.printStackTrace();
			model.addAttribute("ricError", "Error while calculating information content: " + ex.getMessage());
		}

		// Converting double[][] -> List<String[]> for model and session
		List<String[]> matrixForModel = new ArrayList<>();
		for (double[] row : ricArr) {
			String[] rowStr = new String[row.length];
			for (int i = 0; i < row.length; i++) {
				rowStr[i] = String.valueOf(row[i]);
			}
			matrixForModel.add(rowStr);
		}

		// Put matrix into session & model
		String originalTableJson = gson.toJson(matrixForModel);
		session.setAttribute("originalTableJson", originalTableJson);

		model.addAttribute("ricMatrix", matrixForModel);
		int ricColCount = matrixForModel.isEmpty() ? 0 : matrixForModel.get(0).length;
		model.addAttribute("ricColCount", ricColCount);
		model.addAttribute("ricJson", originalTableJson);

		// initialCalcTable (remove initial table from manualData)
		List<List<String>> initialCalcTable = Arrays.stream(
						safeManual.isEmpty() ? new String[0] : safeManual.split(";")
				)
				.filter(s -> s != null && !s.isEmpty())
				.map(row -> Arrays.stream(row.split(","))
						.map(String::trim)
						.collect(Collectors.toList()))
				.collect(Collectors.toList());

		String initJson = gson.toJson(initialCalcTable);
		session.setAttribute("initialCalcTableJson", initJson);

		// deduped originalTuples
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<List<String>> dedupedOriginalTuples = new ArrayList<>();
		for (List<String> row : initialCalcTable) {
			List<String> cleaned = row.stream().map(s -> s == null ? "" : s.trim()).collect(Collectors.toList());
			String key = String.join("|", cleaned);
			if (seen.add(key)) {
				dedupedOriginalTuples.add(cleaned);
			}
		}
		session.setAttribute("originalTuples", dedupedOriginalTuples);

		// originalAttrOrder (first row values -> name/tag list)
		List<String> originalAttrOrder = new ArrayList<>();
		if (!safeManual.isEmpty()) {
			String firstRow = safeManual.split(";", 2)[0];
			originalAttrOrder = Arrays.stream(firstRow.split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.collect(Collectors.toList());
		}
		session.setAttribute("originalAttrOrder", originalAttrOrder);

		// attribute indices
		List<Integer> originalAttrIndices = new ArrayList<>();
		for (int i = 0; i < originalAttrOrder.size(); i++) originalAttrIndices.add(i);
		session.setAttribute("originalAttrIndices", originalAttrIndices);

		// store fdList in session
		session.setAttribute("fdList", safeFds);

		// parse and set originalFDs
		List<FD> originalFDs = parseFdsString(safeFds);
		session.setAttribute("originalFDs", originalFDs);

		// Find FDs derived only by transitivity
		List<FD> transitiveFDs = fdService.findTransitiveFDs(originalFDs);

		// First convert the original list to string and sort it
		List<String> originalFdStrings = originalFDs.stream()
				.map(FD::toString)
				.sorted()
				.collect(Collectors.toList());

		// Then convert the derived list to string and sort it
		List<String> transitiveFdStrings = transitiveFDs.stream()
				.map(FD::toString)
				.sorted()
				.collect(Collectors.toList());

		// Merge the two lists, this will ensure that the originals always come first
		List<String> allFdStringsToShow = new ArrayList<>(originalFdStrings);
		allFdStringsToShow.addAll(transitiveFdStrings);

		// Clear duplicate elements with using LinkedHashSet
		List<String> distinctSortedList = new ArrayList<>(new LinkedHashSet<>(allFdStringsToShow));

		// Save the merged and distinct version for session
		session.setAttribute("fdListWithClosure", String.join(";", distinctSortedList));

		// Add the transitive closure FDs list and the combined list to the model for results page access
		model.addAttribute("allFdStringsToShow", distinctSortedList); // Combined list
		model.addAttribute("transitiveFdStrings", transitiveFdStrings); // Only Transitive FDs for coloring

		model.addAttribute("inputData", safeManual);
		model.addAttribute("fdList", safeFds);

		// Return results page
		return "calc-results";
	}

	// Convert(and parsing) a string like "A->B;C->D;E->F,G" to List<FD>
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
}
