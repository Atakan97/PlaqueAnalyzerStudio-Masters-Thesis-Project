package com.project.plaque.plaque_calculator.controller;

import org.example.ric.Main;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.stream.Collectors;
import org.springframework.ui.Model;
import com.project.plaque.plaque_calculator.model.FD;
import com.project.plaque.plaque_calculator.service.FDService;

@Controller
@RequestMapping("/compute")
public class ComputeController {

	private final FDService fdService;

	// Implementing FDService
	public ComputeController(FDService fdService) {
		this.fdService = fdService;
	}

	@PostMapping
	public String compute(
			@RequestParam(required=false) String manualData,
			@RequestParam(required=false) String fds,
			@RequestParam(required=false, defaultValue="false") boolean monteCarlo,
			@RequestParam(required=false, defaultValue="50000") int samples,
			HttpSession session,
			Model model
	) throws IOException {
		// Preparing CL args
		List<String> args = new ArrayList<>();
		args.add(Optional.ofNullable(manualData).orElse("").trim());
		// Constant flags
		args.addAll(List.of("-e","--closure","-i","-s"));
		if (monteCarlo) {
			args.addAll(List.of("-r", String.valueOf(samples)));
		}
		if (fds!=null && !fds.isBlank()) {
			for (String raw : fds.split(";")) {
				if (!raw.isBlank()) args.add(raw.trim());
			}
		}

		// Run Main.main and capture stdout
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
		PrintStream old = System.out;
		System.setOut(ps);
		try {
			Main.main(args.toArray(new String[0]));
		} catch (Exception ex) {
			throw new IllegalStateException("Computation failed", ex);
		} finally {
			System.out.flush();
			System.setOut(old);
		}
		String output = baos.toString(StandardCharsets.UTF_8);
		String[] lines = Arrays.stream(output.split("\\r?\\n"))
				.filter(l->!l.contains("transitive closure") && !l.startsWith("Runtime:") && !l.startsWith("FDs:"))
				.toArray(String[]::new);

		// Put original table to JSON
		List<List<String>> ricTable = new ArrayList<>();
		for (String line : lines) {
			ricTable.add(Arrays.asList(line.trim().split("\\s+")));
		}

		String originalTableJson = new Gson().toJson(ricTable);
		session.setAttribute("originalTableJson", originalTableJson);

		// Remove the initial table from manualData
		List<List<String>> initialCalcTable = Arrays.stream(
						Optional.ofNullable(manualData).orElse("").split(";")
				)
				.map(row -> Arrays.asList(row.split(",")))
				.collect(Collectors.toList());

		// Convert to JSON and add to session
		String initJson = new Gson().toJson(initialCalcTable);
		session.setAttribute("initialCalcTableJson", initJson);

		// existing initialCalcTable kullanılarak deduped originalTuples oluştur
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<List<String>> dedupedOriginalTuples = new ArrayList<>();
		for (List<String> row : initialCalcTable) {
			// Cleaning and doing trim
			List<String> cleaned = row.stream().map(s -> s == null ? "" : s.trim()).collect(Collectors.toList());
			String key = String.join("|", cleaned);
			if (seen.add(key)) {
				dedupedOriginalTuples.add(cleaned);
			}
		}
		// Put in the session
		session.setAttribute("originalTuples", dedupedOriginalTuples);

		// Sample snippet, creating originalAttrOrder (first row values -> name/tag list)
		String safeManual = Optional.ofNullable(manualData).orElse("").trim();
		List<String> originalAttrOrder = new ArrayList<>();
		if (!safeManual.isEmpty()) {
			// Just take first row (split with limit for performance)
			String firstRow = safeManual.split(";", 2)[0];
			originalAttrOrder = Arrays.stream(firstRow.split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.collect(Collectors.toList());
		}
		session.setAttribute("originalAttrOrder", originalAttrOrder);
		System.out.println("DEBUG: originalAttrOrder -> " + originalAttrOrder);

		// A list of 0-based indexes can also be stored — for future convenience
		List<Integer> originalAttrIndices = new ArrayList<>();
		for (int i = 0; i < originalAttrOrder.size(); i++) originalAttrIndices.add(i);
		session.setAttribute("originalAttrIndices", originalAttrIndices);
		System.out.println("DEBUG: originalAttrIndices -> " + originalAttrIndices);

		session.setAttribute("fdList", fds != null ? fds : "");

		// Add data to the Model to use in the template
		model.addAttribute("inputData", manualData);
		model.addAttribute("fdList", fds);

		// ricMatrix: array of arrays with each row split by whitespace
		List<String[]> matrix = new ArrayList<>();
		for (String line : lines) {
			matrix.add(line.trim().split("\\s+"));
		}
		model.addAttribute("ricMatrix", matrix);
		String ricMatrixJson = new Gson().toJson(matrix);
		model.addAttribute("ricJson", ricMatrixJson);

		// originalAttrs from manualData
		Set<String> originalAttrs = Arrays.stream(
						Optional.ofNullable(manualData).orElse("").split(";")
				)
				.flatMap(r -> Arrays.stream(r.split(",")))
				.collect(Collectors.toSet());
		session.setAttribute("originalAttrs", originalAttrs);

		List<FD> originalFDs = parseFdsString(fds);
		session.setAttribute("originalFDs", originalFDs);

		// generate merged fdListWithClosure and store in session
		try {
			String fdListWithClosure = buildFdListWithClosure(fds, originalFDs, originalAttrOrder);
			session.setAttribute("fdListWithClosure", fdListWithClosure);
			System.out.println("DEBUG: fdListWithClosure -> " + fdListWithClosure);
		} catch (Exception ex) {
			// don't break the page if closure generation fails
			ex.printStackTrace();
			session.setAttribute("fdListWithClosure", Optional.ofNullable(fds).orElse(""));
		}

		// Rendering calc.html
		return "calc";
	}

	// Convert a string like "A->B;C->D;E->F,G" to List<FD>
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

	// Helpers for FD closure-list generation
	private String normalizeFd(String s) {
		if (s == null) return "";
		String t = s.replace('→','-').replaceAll("-+>", "->").trim();
		t = t.replaceAll("\\s*,\\s*", ",");    // remove spaces around commas
		t = t.replaceAll("\\s+", " ");         // normalize inner spaces
		return t;
	}

	// Build merged fdListWithClosure, user-provided fds and closure-derived FDs are appended
	private String buildFdListWithClosure(String fds, List<FD> originalFDs, List<String> originalAttrOrder) {
		// normalize user-provided fds
		List<String> user = new ArrayList<>();
		if (fds != null && !fds.isBlank()) {
			for (String p : fds.split(";")) {
				String t = normalizeFd(p);
				if (!t.isEmpty()) user.add(t);
			}
		}

		// compute closure-derived atomic FDs (X -> A for A in closure(X)\X)
		LinkedHashSet<String> closureFds = new LinkedHashSet<>();
		int n = originalAttrOrder.size();
		// iterate subsets (non-empty)
		for (int mask = 1; mask < (1 << Math.max(0, n)); mask++) {
			LinkedHashSet<String> X = new LinkedHashSet<>();
			for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) X.add(originalAttrOrder.get(i));
			Set<String> clos = fdService.computeClosure(X, originalFDs);
			for (String a : clos) {
				if (!X.contains(a) && originalAttrOrder.contains(a)) {
					List<String> lhs = new ArrayList<>(X);
					Collections.sort(lhs);
					String fdStr = String.join(",", lhs) + "->" + a;
					closureFds.add(fdStr);
				}
			}
		}
		// merge keeping user fds first, dedup overall
		LinkedHashSet<String> merged = new LinkedHashSet<>();
		merged.addAll(user);
		merged.addAll(closureFds);
		return String.join(";", merged);
	}
}
