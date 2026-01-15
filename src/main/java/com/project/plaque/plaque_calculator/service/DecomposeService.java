package com.project.plaque.plaque_calculator.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.project.plaque.plaque_calculator.dto.DecomposeAllRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeAllResponse;
import com.project.plaque.plaque_calculator.dto.DecomposeRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeResponse;
import com.project.plaque.plaque_calculator.model.FD;
import com.project.plaque.plaque_calculator.util.CsvParsingUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class DecomposeService {

	private static final String HISTORY_SESSION_KEY = "decompositionHistory";
	private final FDService fdService;
	private final RicService ricService;
	private final NormalFormChecker normalFormChecker;
	private final Gson gson = new Gson();

	public DecomposeService(FDService fdService, RicService ricService, NormalFormChecker normalFormChecker) {
		this.fdService = fdService;
		this.ricService = ricService;
		this.normalFormChecker = normalFormChecker;
	}

	public DecomposeResponse decompose(DecomposeRequest req, HttpSession session) {
		return decomposeWithProgress(req, session, null, null);
	}

	public DecomposeResponse decomposeWithProgress(DecomposeRequest req,
			HttpSession session,
			Consumer<String> progressListener,
			String tableLabel) {
		System.out.println("DecomposeService.decomposeWithProgress: start");

		String computationId = req != null ? req.getComputationId() : null;
		System.out.println("DecomposeService: computationId from req = '" + computationId + "'");

		List<FD> originalFDs = getOriginalFDsOrThrow(session, computationId);
		List<String> originalAttrOrder = getOriginalAttrOrder(session, computationId);
		System.out.println("DecomposeService: originalFDs = " + originalFDs);
		System.out.println("DecomposeService: originalAttrOrder = " + originalAttrOrder);

		List<Integer> baseColumns = req.getBaseColumns() == null
				? Collections.emptyList()
				: req.getBaseColumns();
		System.out.println("req baseColumns = " + (baseColumns.isEmpty() ? "<empty>" : baseColumns));

		List<String> scopedAttrOrder;
		List<FD> scopedOriginalFds;
		Set<String> scopedOriginalAttrs;

		if (!baseColumns.isEmpty()) {
			List<Integer> normalized = baseColumns.stream()
					.filter(Objects::nonNull)
					.map(Number::intValue)
					.filter(idx -> idx >= 0 && idx < originalAttrOrder.size())
					.distinct()
					.sorted()
					.collect(Collectors.toList());

			if (normalized.isEmpty()) {
				throw new IllegalArgumentException("baseColumns contained no valid indices");
			}

			scopedAttrOrder = normalized.stream()
					.map(originalAttrOrder::get)
					.collect(Collectors.toCollection(ArrayList::new));
			scopedOriginalAttrs = new LinkedHashSet<>(scopedAttrOrder);

			List<FD> allFds = new ArrayList<>(originalFDs);
			Set<FD> transitive = new LinkedHashSet<>(fdService.findTransitiveFDs(originalFDs));
			allFds.addAll(transitive);
			scopedOriginalFds = allFds.stream()
					.filter(fd -> scopedOriginalAttrs.containsAll(fd.getLhs()) && scopedOriginalAttrs.containsAll(fd.getRhs()))
					.map(fd -> new FD(new LinkedHashSet<>(fd.getLhs()), new LinkedHashSet<>(fd.getRhs())))
					.collect(Collectors.toCollection(ArrayList::new));
		} else {
			scopedAttrOrder = new ArrayList<>(originalAttrOrder);
			scopedOriginalFds = new ArrayList<>(originalFDs);
			@SuppressWarnings("unchecked")
			Set<String> originalAttrs = (Set<String>) session.getAttribute("originalAttrs");
			if (originalAttrs == null) {
				originalAttrs = new LinkedHashSet<>(originalAttrOrder);
				session.setAttribute("originalAttrs", originalAttrs);
			}
			scopedOriginalAttrs = new LinkedHashSet<>(originalAttrs);
		}

		System.out.println("DecomposeService: scoped originalAttrOrder = " + scopedAttrOrder);
		System.out.println("DecomposeService: scoped originalFDs = " + scopedOriginalFds);

		List<Integer> cols = req.getColumns() == null ? Collections.emptyList() : req.getColumns();
		Set<String> attrs = cols.stream()
				.map(i -> {
					if (i < 0 || i >= originalAttrOrder.size()) {
						throw new IllegalArgumentException("Column index out of range: " + i);
					}
					return originalAttrOrder.get(i);
				})
				.collect(Collectors.toCollection(LinkedHashSet::new));
		System.out.println("DecomposeService: projected attrs = " + attrs);

		List<FD> projected = projectFDsByClosure(attrs, scopedOriginalFds);
		System.out.println("DecomposeService: projected (pre-minimize) = " + projected);
		projected = minimizeLhsForFds(projected, scopedOriginalFds);
		System.out.println("DecomposeService: projected (minimized) = " + projected);

		boolean dpPreserved = checkDependencyPreserving(scopedOriginalFds, projected);
		System.out.println("DecomposeService: dependency-preserved = " + dpPreserved);

		Set<String> originalAttrs;
		if (!baseColumns.isEmpty()) {
			originalAttrs = new LinkedHashSet<>(scopedOriginalAttrs);
		} else {
			@SuppressWarnings("unchecked")
			Set<String> sessionOriginalAttrs = (Set<String>) session.getAttribute("originalAttrs");
			if (sessionOriginalAttrs == null) {
				sessionOriginalAttrs = new LinkedHashSet<>(originalAttrOrder);
				session.setAttribute("originalAttrs", sessionOriginalAttrs);
			}
			originalAttrs = new LinkedHashSet<>(sessionOriginalAttrs);
		}
		Set<String> S = new LinkedHashSet<>(attrs);
		Set<String> complement = new LinkedHashSet<>(originalAttrs);
		complement.removeAll(S);
		List<Set<String>> schemas = new ArrayList<>();
		schemas.add(S);
		schemas.add(complement);
		boolean ljPreserved = checkLosslessDecomposition(originalAttrs, schemas, scopedOriginalFds);
		System.out.println("DecomposeService: lossless-join = " + ljPreserved);

		boolean manualProvided = req.getManualData() != null && !req.getManualData().isBlank();
		String manualDataPayload = manualProvided
				? sanitizeManualDataString(req.getManualData())
				: buildManualDataForColumns(cols, session);
		if (manualDataPayload == null) manualDataPayload = "";
		manualDataPayload = manualDataPayload.trim();
		if (manualDataPayload.isEmpty()) {
			throw new IllegalStateException("No manual data available for RIC computation.");
		}
		System.out.println("DecomposeService: manualData length = " + manualDataPayload.length());

		// RIC JAR expects numeric column indices (1-based) in FDs.
		// Decomposition logic uses attribute names; convert projected FDs to numeric for the current subtable.
		String ricFds = buildNumericRicFds(projected, attrs, originalAttrOrder);

		List<String> collectedSteps = new ArrayList<>();
		Consumer<String> internalCallback = message -> {
			if (message == null || message.isBlank()) return;
			String trimmed = message.trim();
			collectedSteps.add(trimmed);
			if (progressListener != null) {
				progressListener.accept(prefixStep(trimmed, tableLabel));
			}
		};

		RicService.RicComputationResult ricResult = ricService.computeRicAdaptive(
				manualDataPayload,
				ricFds,
				req.isMonteCarlo(),
				req.getSamples(),
				internalCallback
		);

		List<String> sourceSteps = ricResult.steps() != null ? ricResult.steps() : collectedSteps;
		List<String> prefixedSteps = sourceSteps.stream()
				.map(step -> prefixStep(step, tableLabel))
				.collect(Collectors.toList());

		double[][] ricMatrix = ricResult.matrix();

		List<String> fdsStr = projected.stream()
				.map(this::fdToString)
				.collect(Collectors.toList());

		// Calculate transitive closure FDs for the projected FDs
		List<FD> transitiveFDs = fdService.findTransitiveFDs(projected);
		List<String> transitiveFDsStr = transitiveFDs.stream()
				.map(this::fdToString)
				.collect(Collectors.toList());

		DecomposeResponse resp = new DecomposeResponse(ricMatrix, fdsStr, prefixedSteps);
		resp.setTransitiveFDs(transitiveFDsStr);
		System.out.println("DecomposeService.decomposeWithProgress: done -> " + resp);
		return resp;
	}

	// DecomposeService.decomposeAll - single clean method with computationId support
	public DecomposeAllResponse decomposeAll(DecomposeAllRequest req, HttpSession session) {
		System.out.println("DecomposeService.decomposeAll: start");

		String computationId = req != null ? req.getComputationId() : null;

		// Original FDs & attrs (computationId varsa prefixed session key'lerden okunur)
		List<FD> originalFDs = getOriginalFDsOrThrow(session, computationId);
		List<String> originalAttrOrder = getOriginalAttrOrder(session, computationId);

		// When present, baseColumns signals that validating a nested relation
		List<Integer> baseColumns = req.getBaseColumns();
		if (baseColumns == null) baseColumns = Collections.emptyList();
		List<String> scopedAttrOrder;
		List<FD> scopedOriginalFds;
		Set<String> scopedOriginalAttrs;

		if (!baseColumns.isEmpty()) {
			// Map incoming column indices to the original attribute names for the scoped relation
			List<Integer> normalized = baseColumns.stream()
				.filter(Objects::nonNull)
				.map(Number::intValue)
				.filter(idx -> idx >= 0 && idx < originalAttrOrder.size())
				.distinct()
				.sorted()
				.collect(Collectors.toList());

			if (normalized.isEmpty()) {
				throw new IllegalArgumentException("baseColumns contained no valid indices");
			}

			scopedAttrOrder = normalized.stream()
				.map(originalAttrOrder::get)
				.collect(Collectors.toCollection(ArrayList::new));

			scopedOriginalAttrs = new LinkedHashSet<>(scopedAttrOrder);

			// Include transitive FDs so that projected subsets retain necessary implications
			List<FD> allFds = new ArrayList<>(originalFDs);
			Set<FD> transitive = new LinkedHashSet<>(fdService.findTransitiveFDs(originalFDs));
			allFds.addAll(transitive);

			scopedOriginalFds = allFds.stream()
				.filter(fd -> scopedOriginalAttrs.containsAll(fd.getLhs()) && scopedOriginalAttrs.containsAll(fd.getRhs()))
				.map(fd -> new FD(new LinkedHashSet<>(fd.getLhs()), new LinkedHashSet<>(fd.getRhs())))
				.collect(Collectors.toCollection(ArrayList::new));
		} else {
			scopedAttrOrder = new ArrayList<>(originalAttrOrder);
			scopedOriginalFds = new ArrayList<>(originalFDs);
			@SuppressWarnings("unchecked")
			Set<String> originalAttrs = (Set<String>) session.getAttribute("originalAttrs");
			if (originalAttrs == null) {
				originalAttrs = new LinkedHashSet<>(originalAttrOrder);
				session.setAttribute("originalAttrs", originalAttrs);
			}
			scopedOriginalAttrs = new LinkedHashSet<>(scopedAttrOrder);
		}

		// Take tables request
		List<DecomposeRequest> tables = req.getTables();
		if (tables == null || tables.isEmpty()) {
			throw new IllegalStateException("No tables provided in request");
		}

		// Union of all column indices (0-based)
		LinkedHashSet<Integer> unionColIdx = new LinkedHashSet<>();
		for (DecomposeRequest dr : tables) {
			List<Integer> cols = dr.getColumns();
			if (cols != null) unionColIdx.addAll(cols);
		}

		// Deterministic ordered list of union columns
		List<Integer> unionColsSorted = new ArrayList<>(unionColIdx);
		Collections.sort(unionColsSorted);

		// Top-level FDs (normalized)
		String topFds = req.getFds();
		if (topFds == null) topFds = "";
		topFds = topFds.replace("\u2192", "->").replace("→", "->")
				.replaceAll("\\s*,\\s*", ",").replaceAll("\\s*->\\s*", "->")
				.replaceAll("-+>", "->").trim();
		// For global RIC, the jar requires numeric indices relative to the encoded table we pass.
		// That encoded table is built using unionColsSorted, so convert any incoming FD specs accordingly.
		String topFdsForRic = convertFdsToUnionNumeric(topFds, originalAttrOrder, unionColsSorted);

		// Build table attribute sets (mapped to attribute names) and canonicalize (ordering)
		List<Set<String>> tableAttrSets = new ArrayList<>(tables.size());
		for (DecomposeRequest dr : tables) {
			List<Integer> cols = dr.getColumns() == null ? Collections.emptyList() : dr.getColumns();
			// Normalize indices (numbers)
			List<Integer> colsNum = cols.stream().map(n -> n == null ? -1 : n).collect(Collectors.toList());

			LinkedHashSet<String> attrs = colsNum.stream()
					.map(i -> {
						if (i < 0 || i >= originalAttrOrder.size()) {
							throw new IllegalArgumentException("Column index out of range: " + i);
						}
						return originalAttrOrder.get(i);
					})
					// Preserves insertion
					.collect(Collectors.toCollection(LinkedHashSet::new));

			// Canonicalize by sorting attribute names
			List<String> tmp = new ArrayList<>(attrs);
			Collections.sort(tmp);
			LinkedHashSet<String> canonical = new LinkedHashSet<>(tmp);

			tableAttrSets.add(canonical);
		}

		// unionAttrs = union of all table attributes (should equal originalAttrs if validated above)
		LinkedHashSet<String> unionAttrs = new LinkedHashSet<>();
		for (Set<String> s : tableAttrSets) unionAttrs.addAll(s);
		if (!scopedOriginalAttrs.equals(unionAttrs)) {
			System.out.println("DecomposeService.decomposeAll: unionAttrs=" + unionAttrs + ", scopedOriginalAttrs=" + scopedOriginalAttrs);
		}

		// Build global manual rows with consistent column count (union columns)
		List<String> manualRowsList = new ArrayList<>();
		if (req.getManualData() != null && !req.getManualData().isBlank()) {
			List<List<String>> parsedRows = CsvParsingUtil.parseRows(req.getManualData());
			LinkedHashSet<String> set = new LinkedHashSet<>();
			for (List<String> row : parsedRows) {
				List<String> picked = new ArrayList<>();
				for (Integer colIdx : unionColsSorted) {
					int i = colIdx == null ? -1 : colIdx;
					String v = (i >= 0 && i < row.size()) ? row.get(i) : "";
					String sanitized = (v == null ? "" : v.replace(",", "|").replace(";", "|").replace("\"", "").trim());
					if (sanitized.isEmpty()) sanitized = "_";
					picked.add(sanitized);
				}
				String rowStr = String.join(",", picked);
				if (!rowStr.replace(",", "").trim().isEmpty()) {
					set.add(rowStr);
				}
			}
			manualRowsList.addAll(set);
		} else {
			// Build from session originalTuples using unionColsSorted (with computationId prefix)
			@SuppressWarnings("unchecked")
			List<List<String>> originalTuples = (List<List<String>>) session.getAttribute(resolveSessionKey("originalTuples", computationId));
			if (originalTuples == null || originalTuples.isEmpty()) {
				String origJson = (String) session.getAttribute(resolveSessionKey("originalTableJson", computationId));
				if (origJson != null && !origJson.isBlank()) {
					try {
						Type t = new TypeToken<List<List<String>>>(){}.getType();
						originalTuples = gson.fromJson(origJson, t);
						if (originalTuples == null) originalTuples = Collections.emptyList();
					} catch (Exception ex) {
						originalTuples = Collections.emptyList();
					}
				} else {
					originalTuples = Collections.emptyList();
				}
			}
			LinkedHashSet<String> seen = new LinkedHashSet<>();
			for (List<String> row : originalTuples) {
				List<String> picked = new ArrayList<>();
				for (Integer colIdx : unionColsSorted) {
					int i = colIdx == null ? -1 : colIdx;
					String v = (i >= 0 && i < row.size()) ? row.get(i) : "";
					// Sanitize cell value for RIC compatibility
					String sanitized = (v == null ? "" : v.replace(",", "|").replace(";", "|").replace("\"", "").trim());
					if (sanitized.isEmpty()) sanitized = "_";
					picked.add(sanitized);
				}
				String tup = String.join(",", picked);
				if (!tup.replace(",", "").trim().isEmpty()) seen.add(tup);
			}
			manualRowsList.addAll(seen);
		}

		String builtManual = String.join(";", manualRowsList).trim();
		System.out.println("DecomposeService.decomposeAll: built manualData for global RIC = " + builtManual);
		System.out.println("DecomposeService.decomposeAll: passing topFds = '" + topFdsForRic + "' to RicService");

		// Compute global RIC with adaptive fallbacks - skip if manual data is empty to avoid jar errors
		double[][] globalRic;
		if (builtManual.isEmpty()) {
			System.out.println("DecomposeService.decomposeAll: skipping global RIC computation (empty manual data)");
			globalRic = new double[0][0];
		} else {
			List<String> globalRicSteps = new ArrayList<>();
			RicService.RicComputationResult globalRicResult = ricService.computeRicAdaptive(
					builtManual,
					topFdsForRic,
					req.isMonteCarlo(),
					req.getSamples(),
					message -> {
						if (message != null && !message.isBlank()) {
							globalRicSteps.add(message.trim());
						}
					});
			globalRic = globalRicResult != null && globalRicResult.matrix() != null
					? globalRicResult.matrix()
					: new double[0][0];
		}

		// Per-table: project & minimize FDs (still return projected FD lists per table)
		List<DecomposeResponse> perTableResponses = new ArrayList<>();
		List<FD> combinedProjectedFds = new ArrayList<>();
		boolean allTablesBCNF = true; // BCNF bayrağı başlatıldı

		for (int i = 0; i < tables.size(); i++) {
			Set<String> attrs = tableAttrSets.get(i);

			// Project & minimize projected FDs for this table
			List<FD> projected = projectFDsByClosure(attrs, scopedOriginalFds);
			List<FD> minimizedProjected = minimizeLhsForFds(projected, scopedOriginalFds);

			combinedProjectedFds.addAll(minimizedProjected);

			// BCNF checking: Her tablo kendi projected FD'lerine göre kontrol edilmeli
			// (Tüm orijinal FD'ler yerine, sadece bu tabloya ait projected FD'ler kullanılır)
			boolean isBCNF = checkBCNF(attrs, minimizedProjected, fdService);
			if (!isBCNF) {
				allTablesBCNF = false;
			}

			// Check normal form for this table
			String normalForm = normalFormChecker.checkNormalForm(attrs, minimizedProjected);

			// Build response item with projected FDs and normal form
			List<String> projectedStr = minimizedProjected.stream().map(this::fdToString).collect(Collectors.toList());

			// Calculate transitive closure FDs for this table
			List<FD> transitiveFDs = fdService.findTransitiveFDs(minimizedProjected);
			List<String> transitiveFDsStr = transitiveFDs.stream()
					.map(this::fdToString)
					.collect(Collectors.toList());

			DecomposeResponse drResp = new DecomposeResponse(new double[0][0], projectedStr);
			drResp.setNormalForm(normalForm);
			drResp.setTransitiveFDs(transitiveFDsStr);
			perTableResponses.add(drResp);
		}

		// global dp-preserved (combined projected FDs imply original)
		Map<String, FD> uniq = new LinkedHashMap<>();
		for (FD fd : combinedProjectedFds) {
			String key = fd.getLhs().toString() + "->" + fd.getRhs().toString();
			uniq.putIfAbsent(key, fd);
		}
		List<FD> combinedProjectedUnique = new ArrayList<>(uniq.values());
		// Evaluate dependency preservation / lossless join against the scoped population of FDs and attributes
		boolean dpPreservedGlobal = checkDependencyPreserving(scopedOriginalFds, combinedProjectedUnique);

		// Calculate missing FDs (FDs that were not preserved in decomposition)
		List<String> missingFDs = new ArrayList<>();
		if (!dpPreservedGlobal) {
			for (FD originalFd : scopedOriginalFds) {
				Set<String> closure = fdService.computeClosure(originalFd.getLhs(), combinedProjectedUnique);
				if (!closure.containsAll(originalFd.getRhs())) {
					// This FD was not preserved
					missingFDs.add(fdToString(originalFd));
				}
			}
		}
		System.out.println("DecomposeService.decomposeAll: missingFDs = " + missingFDs);

		// Build schemaList deterministically
		List<Set<String>> schemaList = tableAttrSets.stream()
				.map(s -> new LinkedHashSet<>(s))
				.collect(Collectors.toList());
		schemaList.sort(Comparator.comparing(s -> String.join(",", s)));

		// Use detailed lossless-join check to provide diagnostic information
		com.project.plaque.plaque_calculator.dto.LosslessJoinDetail ljDetails =
			checkLosslessDecompositionWithDetails(new LinkedHashSet<>(scopedAttrOrder), schemaList, scopedOriginalFds);
		boolean ljPreservedGlobal = ljDetails.isLossless();

		System.out.println("DecomposeService.decomposeAll: dpPreservedGlobal=" + dpPreservedGlobal + " ljPreservedGlobal=" + ljPreservedGlobal);

		// Build response
		DecomposeAllResponse allResp = new DecomposeAllResponse();
		allResp.setTableResults(perTableResponses);
		allResp.setDpPreserved(dpPreservedGlobal);
		allResp.setLjPreserved(ljPreservedGlobal);
		allResp.setLjDetails(ljDetails);  // Add detailed lossless-join diagnostic info
		allResp.setBCNFDecomposition(allTablesBCNF);
		allResp.setMissingFDs(missingFDs);

		// set global RIC matrix and manual rows (for frontend mapping) and unionCols
		allResp.setGlobalRic(globalRic);
		allResp.setGlobalManualRows(manualRowsList);
		allResp.setUnionCols(unionColsSorted);

		System.out.println("DecomposeService.decomposeAll: done");
		return allResp;
	}

	private String resolveSessionKey(String baseKey, String computationId) {
		if (computationId == null || computationId.isBlank()) {
			return baseKey;
		}
		return "computation_" + computationId + "_" + baseKey;
	}

	// Helper methods

	@SuppressWarnings("unchecked")
	private List<FD> getOriginalFDsOrThrow(HttpSession session, String computationId) {
		String key = (computationId == null || computationId.isBlank())
			? "originalFDs"
			: ("computation_" + computationId + "_originalFDs");
		List<FD> originalFDs = (List<FD>) session.getAttribute(key);
		if (originalFDs == null) {
			throw new IllegalStateException("Original FDs not found in session. Run compute first.");
		}
		return originalFDs;
	}

	@SuppressWarnings("unchecked")
	private List<String> getOriginalAttrOrder(HttpSession session, String computationId) {
		String key = (computationId == null || computationId.isBlank())
			? "originalAttrOrder"
			: ("computation_" + computationId + "_originalAttrOrder");
		Object obj = session.getAttribute(key);
		if (obj == null) {
			throw new IllegalStateException("originalAttrOrder not found in session. Run compute first.");
		}
		List<?> raw = (List<?>) obj;
		return raw.stream().map(Object::toString).collect(Collectors.toList());
	}

	private String sanitizeManualDataString(String manualData) {
		List<List<String>> rows = CsvParsingUtil.parseRows(manualData);
		return CsvParsingUtil.toRicCompatibleString(rows);
	}

	private String normalizeFds(String fds) {
		if (fds == null || fds.isBlank()) {
			return "";
		}
		return Arrays.stream(fds.split("[;\r\n]+"))
				.map(s -> s.replace("→", "->").replace("\u2192", "->"))
				.map(s -> s.replaceAll("\\s*,\\s*", ","))
				.map(s -> s.replaceAll("\\s*->\\s*", "->"))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.joining(";"));
	}

	private String buildNumericRicFds(List<FD> projectedFds, Set<String> projectedAttrs, List<String> originalAttrOrder) {
		if (projectedFds == null || projectedFds.isEmpty() || projectedAttrs == null || projectedAttrs.isEmpty()) {
			return "";
		}

		// Column order must match the manualDataPayload that we pass to the RIC jar.
		// manualDataPayload is built using the requested column indices (preserving their order),
		// and projectedAttrs is built in the same order (LinkedHashSet).
		List<String> subAttrOrder = new ArrayList<>(projectedAttrs);
		Map<String, Integer> indexMap = new LinkedHashMap<>();
		for (int i = 0; i < subAttrOrder.size(); i++) {
			indexMap.put(subAttrOrder.get(i), i + 1); // 1-based for RIC jar
		}

		List<String> out = new ArrayList<>();
		for (FD fd : projectedFds) {
			if (fd == null) continue;
			Set<String> lhsAttrs = fd.getLhs();
			Set<String> rhsAttrs = fd.getRhs();
			if (lhsAttrs == null || lhsAttrs.isEmpty() || rhsAttrs == null || rhsAttrs.size() != 1) {
				continue;
			}

			List<Integer> lhsIdx = new ArrayList<>();
			boolean ok = true;
			for (String a : lhsAttrs) {
				Integer idx = indexMap.get(a);
				if (idx == null) {
					ok = false;
					break;
				}
				lhsIdx.add(idx);
			}
			if (!ok) continue;
			Collections.sort(lhsIdx);

			String rhsAttr = rhsAttrs.iterator().next();
			Integer rhsIdx = indexMap.get(rhsAttr);
			if (rhsIdx == null) continue;

			String lhsStr = lhsIdx.stream().map(String::valueOf).collect(Collectors.joining(","));
			out.add(lhsStr + "->" + rhsIdx);
		}

		// Separate FDs with semicolon so RicService can split them into separate CLI args.
		return String.join(";", out);
	}

	private String convertFdsToUnionNumeric(String fds, List<String> originalAttrOrder, List<Integer> unionColsSorted) {
		if (fds == null || fds.isBlank()) {
			return "";
		}
		if (originalAttrOrder == null || originalAttrOrder.isEmpty() || unionColsSorted == null || unionColsSorted.isEmpty()) {
			return normalizeFds(fds);
		}

		Map<String, Integer> attrNameToOrigIdx1Based = new LinkedHashMap<>();
		for (int i = 0; i < originalAttrOrder.size(); i++) {
			String name = originalAttrOrder.get(i);
			if (name == null) continue;
			String key = name.trim();
			if (key.isEmpty()) continue;
			attrNameToOrigIdx1Based.putIfAbsent(key, i + 1);
		}

		Map<Integer, Integer> origIdxToUnionPos1Based = new LinkedHashMap<>();
		for (int pos = 0; pos < unionColsSorted.size(); pos++) {
			Integer col0 = unionColsSorted.get(pos);
			if (col0 == null) continue;
			origIdxToUnionPos1Based.put(col0 + 1, pos + 1);
		}

		String norm = normalizeFds(fds);
		List<String> out = new ArrayList<>();
		for (String fdStr : norm.split(";")) {
			if (fdStr == null) continue;
			String tok = fdStr.trim();
			if (tok.isEmpty()) continue;
			String[] parts = tok.split("->", 2);
			if (parts.length != 2) continue;
			String lhsRaw = parts[0].trim();
			String rhsRaw = parts[1].trim();
			if (lhsRaw.isEmpty() || rhsRaw.isEmpty()) continue;

			String[] rhsParts = rhsRaw.split(",");
			for (String rhsToken : rhsParts) {
				String rhsT = rhsToken == null ? "" : rhsToken.trim();
				if (rhsT.isEmpty()) continue;
				Integer rhsOrigIdx = rhsT.matches("\\d+")
						? Integer.parseInt(rhsT)
						: attrNameToOrigIdx1Based.get(rhsT);
				if (rhsOrigIdx == null) continue;
				Integer rhsUnion = origIdxToUnionPos1Based.get(rhsOrigIdx);
				if (rhsUnion == null) continue;

				List<Integer> lhsUnionIdx = new ArrayList<>();
				boolean ok = true;
				for (String lhsToken : lhsRaw.split(",")) {
					String lhsT = lhsToken == null ? "" : lhsToken.trim();
					if (lhsT.isEmpty()) continue;
					Integer lhsOrigIdx = lhsT.matches("\\d+")
							? Integer.parseInt(lhsT)
							: attrNameToOrigIdx1Based.get(lhsT);
					if (lhsOrigIdx == null) {
						ok = false;
						break;
					}
					Integer lhsUnion = origIdxToUnionPos1Based.get(lhsOrigIdx);
					if (lhsUnion == null) {
						ok = false;
						break;
					}
					lhsUnionIdx.add(lhsUnion);
				}
				if (!ok || lhsUnionIdx.isEmpty()) continue;
				Collections.sort(lhsUnionIdx);
				String lhsStr = lhsUnionIdx.stream().map(String::valueOf).collect(Collectors.joining(","));
				out.add(lhsStr + "->" + rhsUnion);
			}
		}

		return String.join(";", out);
	}

	private String prefixStep(String step, String tableLabel) {
		if (step == null || step.isBlank()) {
			return step;
		}
		if (tableLabel == null || tableLabel.isBlank()) {
			return step;
		}
		return tableLabel + ": " + step;
	}


	private String buildManualDataForColumns(List<Integer> cols, HttpSession session) {
		@SuppressWarnings("unchecked")
		List<List<String>> originalTuples = (List<List<String>>) session.getAttribute("originalTuples");
		if (originalTuples == null) {
			return "";
		}
		List<List<String>> projected = new ArrayList<>();
		for (List<String> row : originalTuples) {
			List<String> picked = new ArrayList<>();
			for (Integer colIdx : cols) {
				int i = colIdx == null ? -1 : colIdx;
				picked.add(i >= 0 && i < row.size() ? row.get(i) : "");
			}
			projected.add(picked);
		}
		return CsvParsingUtil.toRicCompatibleString(projected);
	}

	// Numbering all non-empty subsets X of attrs and compute closure(X) under originalFDs
	// For each A in (closure ∩ attrs) \ X produce FD X -> A (RHS atomic)
	private List<FD> projectFDsByClosure(Set<String> attrs, List<FD> originalFDs) {
		List<FD> out = new ArrayList<>();
		List<String> attrList = new ArrayList<>(attrs);
		int n = attrList.size();

		int total = 1 << n;
		for (int mask = 1; mask < total; mask++) {
			Set<String> X = new LinkedHashSet<>();
			for (int i = 0; i < n; i++) {
				if ((mask & (1 << i)) != 0) X.add(attrList.get(i));
			}
			Set<String> closure = fdService.computeClosure(X, originalFDs);
			Set<String> rhs = new LinkedHashSet<>(closure);
			rhs.retainAll(attrs);
			rhs.removeAll(X);
			for (String a : rhs) {
				out.add(new FD(new LinkedHashSet<>(X), new LinkedHashSet<>(Set.of(a))));
			}
		}

		// Deduplicate
		Map<String, FD> uniq = new LinkedHashMap<>();
		for (FD fd : out) {
			String key = fd.getLhs().toString() + "->" + fd.getRhs().toString();
			uniq.putIfAbsent(key, fd);
		}
		return new ArrayList<>(uniq.values());
	}

	// Minimizing LHS, for each FD remove extra attributes using closure under originalFDs
	private List<FD> minimizeLhsForFds(List<FD> fds, List<FD> originalFDs) {
		List<FD> result = new ArrayList<>();
		for (FD fd : fds) {
			Set<String> lhs = new LinkedHashSet<>(fd.getLhs());
			Set<String> rhs = new LinkedHashSet<>(fd.getRhs());
			boolean changed;
			do {
				changed = false;
				for (String a : new ArrayList<>(lhs)) {
					if (lhs.size() == 1) break;
					Set<String> reduced = new LinkedHashSet<>(lhs);
					reduced.remove(a);
					Set<String> closure = fdService.computeClosure(reduced, originalFDs);
					if (closure.containsAll(rhs)) {
						lhs.remove(a);
						changed = true;
						break;
					}
				}
			} while (changed);
			result.add(new FD(new LinkedHashSet<>(lhs), new LinkedHashSet<>(rhs)));
		}

		Map<String, FD> uniq = new LinkedHashMap<>();
		for (FD fd : result) {
			String key = fd.getLhs().toString() + "->" + fd.getRhs().toString();
			uniq.putIfAbsent(key, fd);
		}
		return new ArrayList<>(uniq.values());
	}

	// Check dependency preservation: for every original FD X->Y check closure_{projected}(X)
	private boolean checkDependencyPreserving(List<FD> original, List<FD> projected) {
		for (FD fd : original) {
			Set<String> closure = fdService.computeClosure(fd.getLhs(), projected);
			if (!closure.containsAll(fd.getRhs())) {
				return false;
			}
		}
		return true;
	}

	// Lossless-join test - represents to detailed version
	private boolean checkLosslessDecomposition(Set<String> R, List<Set<String>> schemas, List<FD> originalFDs) {
		return checkLosslessDecompositionWithDetails(R, schemas, originalFDs).isLossless();
	}

	/**
	 * Lossless-join test with detailed information.
	 * Returns a LosslessJoinDetail object containing user-friendly explanation.
	 */
	private com.project.plaque.plaque_calculator.dto.LosslessJoinDetail checkLosslessDecompositionWithDetails(
			Set<String> R, List<Set<String>> schemas, List<FD> originalFDs) {

		com.project.plaque.plaque_calculator.dto.LosslessJoinDetail detail =
			new com.project.plaque.plaque_calculator.dto.LosslessJoinDetail();

		if (R == null || R.isEmpty() || schemas == null || schemas.isEmpty()) {
			detail.setLossless(false);
			detail.setExplanation("Cannot perform lossless-join test: empty relation or schemas.");
			return detail;
		}

		// Create the initial matrix
		List<String> allAttributes = new ArrayList<>(R);
		int numAttrs = allAttributes.size();
		int numSchemas = schemas.size();
		String[][] matrix = new String[numSchemas][numAttrs];

		// Initialize matrix
		for (int i = 0; i < numSchemas; i++) {
			Set<String> schema_i = schemas.get(i);
			for (int j = 0; j < numAttrs; j++) {
				String attr_j = allAttributes.get(j);
				if (schema_i.contains(attr_j)) {
					// Distinguished variable
					matrix[i][j] = "a" + (j + 1);
				} else {
					// Non-distinguished variable
					matrix[i][j] = "b" + (i + 1) + (j + 1);
				}
			}
		}

		// Apply Chase algorithm
		boolean changed;
		do {
			changed = false;
			for (FD fd : originalFDs) {
				Set<String> lhs = fd.getLhs();
				Set<String> rhs = fd.getRhs();

				// Find rows with the same LHS values
				List<Integer> lhsIndices = lhs.stream()
						.map(allAttributes::indexOf)
						.collect(Collectors.toList());

				if (lhsIndices.contains(-1)) {
					continue;
				}

				Map<List<String>, List<Integer>> groups = new HashMap<>();
				for (int i = 0; i < numSchemas; i++) {
					List<String> key = new ArrayList<>();
					for (int index : lhsIndices) {
						key.add(matrix[i][index]);
					}
					groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
				}

				for (List<Integer> rowIndices : groups.values()) {
					if (rowIndices.size() < 2) continue;

					// Equalize values in RHS columns
					for (String attr_y : rhs) {
						int y_idx = allAttributes.indexOf(attr_y);
						if (y_idx == -1) continue;

						String distinguishedValue = null;
						for (int rowIndex : rowIndices) {
							if (matrix[rowIndex][y_idx].startsWith("a")) {
								distinguishedValue = matrix[rowIndex][y_idx];
								break;
							}
						}

						if (distinguishedValue != null) {
							for (int rowIndex : rowIndices) {
								if (!matrix[rowIndex][y_idx].equals(distinguishedValue)) {
									matrix[rowIndex][y_idx] = distinguishedValue;
									changed = true;
								}
							}
						} else {
							String firstValue = matrix[rowIndices.get(0)][y_idx];
							for (int rowIndex : rowIndices) {
								if (!matrix[rowIndex][y_idx].equals(firstValue)) {
									matrix[rowIndex][y_idx] = firstValue;
									changed = true;
								}
							}
						}
					}
				}
			}
		} while (changed);

		// Check if lossless
		boolean isLossless = false;
		for (int i = 0; i < numSchemas; i++) {
			boolean allDistinguished = true;
			for (int j = 0; j < numAttrs; j++) {
				if (!matrix[i][j].startsWith("a")) {
					allDistinguished = false;
					break;
				}
			}
			if (allDistinguished) {
				isLossless = true;
				break;
			}
		}

		detail.setLossless(isLossless);

		// Generate user-friendly explanation
		StringBuilder explanation = new StringBuilder();
		if (isLossless) {
			explanation.append("✓ The decomposition is lossless-join, meaning no information will be lost when joining the decomposed tables back together.");
		} else {
			explanation.append("What does this mean?\n");
			explanation.append("When you join the decomposed tables back together, you may get extra tuples (spurious tuples) ");
			explanation.append("that were not in the original relation.\n\n");

			// Analyze common attributes between schemas
			explanation.append("Why is this happening?\n");
			explanation.append("The problem is with how your tables share common attributes:\n\n");

			// Find overlapping attributes between schemas
			Map<String, List<Integer>> attrToSchemas = new HashMap<>();
			for (int i = 0; i < numSchemas; i++) {
				Set<String> schema_i = schemas.get(i);
				for (String attr : schema_i) {
					attrToSchemas.computeIfAbsent(attr, k -> new ArrayList<>()).add(i);
				}
			}

			// Find common attributes (intersection columns)
			List<String> commonAttrs = new ArrayList<>();
			for (Map.Entry<String, List<Integer>> entry : attrToSchemas.entrySet()) {
				if (entry.getValue().size() > 1) {
					commonAttrs.add(entry.getKey());
				}
			}

			if (commonAttrs.isEmpty()) {
				explanation.append("• Your decomposed tables have NO common attributes!\n");
				explanation.append("  Without common attributes, there's no way to join them meaningfully.\n\n");
				explanation.append("How to fix:\n");
				explanation.append("  → Ensure that decomposed tables share at least one common attribute.\n");
				explanation.append("  → The common attributes should form a key (or superkey) in at least one table.\n");
			} else {
				explanation.append("• Common attributes between tables: ").append(String.join(", ", commonAttrs)).append("\n");
				explanation.append("• However, these common attributes do not form a sufficient key based on your functional dependencies.\n\n");

				explanation.append("Your decomposed tables:\n");
				for (int i = 0; i < numSchemas; i++) {
					List<String> schemaAttrs = schemas.get(i).stream().sorted().collect(Collectors.toList());
					explanation.append("  • R").append(i + 1).append(": {")
							.append(String.join(", ", schemaAttrs)).append("}\n");
				}

				explanation.append("\nHow to fix:\n");
				explanation.append("  → Make sure common attributes between tables include a key (or superkey) of at least one table.\n");
				explanation.append("  → Consider including additional attributes to form a proper key.\n");
				explanation.append("  → Check if one of your decomposed tables can be the 'original relation' itself.\n");
			}
		}

		detail.setExplanation(explanation.toString());
		return detail;
	}


	private String fdToString(FD fd) {
		String lhs = fd.getLhs().stream().sorted().collect(Collectors.joining(","));
		String rhs = fd.getRhs().stream().sorted().collect(Collectors.joining(","));
		return lhs + "→" + rhs;
	}

	public DecomposeResponse projectFDsOnly(DecomposeRequest req, HttpSession session) {
		System.out.println("DecomposeService.projectFDsOnly: start");

		String computationId = req != null ? req.getComputationId() : null;

		// original FDs
		List<FD> originalFDs = getOriginalFDsOrThrow(session, computationId);
		System.out.println("DecomposeService.projectFDsOnly: originalFDs = " + originalFDs);

		// originalAttrOrder
		List<String> originalAttrOrder = getOriginalAttrOrder(session, computationId);
		System.out.println("DecomposeService.projectFDsOnly: originalAttrOrder = " + originalAttrOrder);

		// Convert incoming column indexes to attribute names
		List<Integer> cols = req.getColumns() == null ? Collections.emptyList() : req.getColumns();
		Set<String> attrs = cols.stream()
				.map(i -> {
					if (i < 0 || i >= originalAttrOrder.size()) {
						throw new IllegalArgumentException("Column index out of range: " + i);
					}
					return originalAttrOrder.get(i);
				})
				.collect(Collectors.toCollection(LinkedHashSet::new));
		System.out.println("DecomposeService.projectFDsOnly: projected attrs = " + attrs);

		// Projection using closure
		List<FD> projected = projectFDsByClosure(attrs, originalFDs);
		System.out.println("DecomposeService.projectFDsOnly: projected (pre-minimize) = " + projected);

		// Minimalizing LHS
		projected = minimizeLhsForFds(projected, originalFDs);
		System.out.println("DecomposeService.projectFDsOnly: projected (minimized) = " + projected);

		// Checking dependency preserving
		boolean dpPreserved = checkDependencyPreserving(originalFDs, projected);
		System.out.println("DecomposeService.projectFDsOnly: dpPreserved = " + dpPreserved);

		// Checking lossless join
		@SuppressWarnings("unchecked")
		Set<String> originalAttrs = (Set<String>) session.getAttribute("originalAttrs");
		if (originalAttrs == null) {
			originalAttrs = new LinkedHashSet<>(originalAttrOrder);
			session.setAttribute("originalAttrs", originalAttrs);
		}
		// Build complement (R \ S) to create a 2-way decomposition
		Set<String> S = new LinkedHashSet<>(attrs);
		Set<String> complement = new LinkedHashSet<>(originalAttrs);
		complement.removeAll(S);
		List<Set<String>> schemas = new ArrayList<>();
		schemas.add(S);
		schemas.add(complement);
		boolean ljPreserved = checkLosslessDecomposition(originalAttrs, schemas, originalFDs);
		System.out.println("DecomposeService.projectFDsOnly: ljPreserved = " + ljPreserved);

		// Convert FDs to string
		List<String> fdsStr = projected.stream()
				.map(this::fdToString)
				.collect(Collectors.toList());

		// Calculate transitive closure FDs for the projected FDs
		List<FD> transitiveFDs = fdService.findTransitiveFDs(projected);
		List<String> transitiveFDsStr = transitiveFDs.stream()
				.map(this::fdToString)
				.collect(Collectors.toList());

		// Just showing functional dependencies, no calculating ric so return 0
		double[][] emptyRic = new double[0][0];

		DecomposeResponse resp = new DecomposeResponse(emptyRic, fdsStr);
		resp.setTransitiveFDs(transitiveFDsStr);
		System.out.println("DecomposeService.projectFDsOnly: done -> " + resp);
		return resp;
	}

	/**
	 * Check if a relation is in BCNF
	 * Delegates to NormalFormChecker for comprehensive BCNF check
	 *
	 * @param attributes Set of attributes in the relation
	 * @param allOriginalFds List of functional dependencies
	 * @param fdService FDService instance (kept for backward compatibility, not used)
	 * @return true if relation is in BCNF
	 */
	public boolean checkBCNF(Set<String> attributes, List<FD> allOriginalFds, FDService fdService) {
		// Delegate to NormalFormChecker for comprehensive BCNF check
		return normalFormChecker.isBCNFComprehensive(attributes, allOriginalFds);
	}
}

