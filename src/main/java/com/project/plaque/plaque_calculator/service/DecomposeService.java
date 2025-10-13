package com.project.plaque.plaque_calculator.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.project.plaque.plaque_calculator.dto.DecomposeAllRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeAllResponse;
import com.project.plaque.plaque_calculator.dto.DecomposeRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeResponse;
import com.project.plaque.plaque_calculator.model.FD;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DecomposeService {

	private static final String HISTORY_SESSION_KEY = "decompositionHistory";
	private final FDService fdService;
	private final RicService ricService;
	private final Gson gson = new Gson();

	public DecomposeService(FDService fdService, RicService ricService) {
		this.fdService = fdService;
		this.ricService = ricService;
	}

	public DecomposeResponse decompose(DecomposeRequest req, HttpSession session) {
		System.out.println("DecomposeService.decompose: start");

		// OriginalFDs list
		List<FD> originalFDs = getOriginalFDsOrThrow(session);
		System.out.println("DecomposeService: originalFDs = " + originalFDs);

		// OriginalAttrOrder
		List<String> originalAttrOrder = getOriginalAttrOrder(session);
		System.out.println("DecomposeService: originalAttrOrder = " + originalAttrOrder);

		// Mapping incoming column indices to attribute names
		List<Integer> cols = req.getColumns() == null ? Collections.emptyList() : req.getColumns();
		Set<String> attrs = cols.stream()
				.map(i -> {
					if (i < 0 || i >= originalAttrOrder.size()) {
						throw new IllegalArgumentException("Column index out of range: " + i);
					}
					return originalAttrOrder.get(i);
				})
				// Keep insertion order
				.collect(Collectors.toCollection(LinkedHashSet::new));
		System.out.println("DecomposeService: projected attrs = " + attrs);

		// Computing projected FDs with closure
		List<FD> projected = projectFDsByClosure(attrs, originalFDs);
		System.out.println("DecomposeService: projected (pre-minimize) = " + projected);

		// Minimizing LHS for each projected FD
		projected = minimizeLhsForFds(projected, originalFDs);
		System.out.println("DecomposeService: projected (minimized) = " + projected);

		// Checking dependency-preserving
		boolean dpPreserved = checkDependencyPreserving(originalFDs, projected);
		System.out.println("DecomposeService: dependency-preserved = " + dpPreserved);

		// Checking lossless-join
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
		System.out.println("DecomposeService: lossless-join = " + ljPreserved);

		// Calculating relational information content
		double[][] ric;
		if (req.getManualData() != null && !req.getManualData().isBlank()) {
			System.out.println("DecomposeService: using manualData from request for RIC");
			// Use request FDs if provided (normalize arrows and whitespace)
			String reqFds = req.getFds() == null ? "" : req.getFds();
			reqFds = reqFds.replace("\u2192", "->").replace("→", "->")
					.replaceAll("\\s*,\\s*", ",").replaceAll("\\s*->\\s*", "->")
					.replaceAll("-+>", "->").trim();
			if (reqFds.isEmpty()) {
				ric = ricService.computeRicFromManualData(req.getManualData());
			} else {
				ric = ricService.computeRicFromManualData(req.getManualData(), reqFds);
			}
		} else {
			System.out.println("DecomposeService: using session + columns for RIC");
			ric = ricService.computeRic(cols, session);
		}

		// FD strings
		List<String> fdsStr = projected.stream()
				.map(this::fdToString)
				.collect(Collectors.toList());

		// Response
		DecomposeResponse resp = new DecomposeResponse(ric, fdsStr);
		System.out.println("DecomposeService.decompose: done -> " + resp);
		return resp;
	}

	// DecomposeService.decomposeAll
	public DecomposeAllResponse decomposeAll(DecomposeAllRequest req, HttpSession session) {
		System.out.println("DecomposeService.decomposeAll: start");
		System.out.println("decomposeAll req fds = " + req.getFds());

		// Original FDs & attrs
		List<FD> originalFDs = getOriginalFDsOrThrow(session);
		List<String> originalAttrOrder = getOriginalAttrOrder(session);

		@SuppressWarnings("unchecked")
		Set<String> originalAttrs = (Set<String>) session.getAttribute("originalAttrs");
		if (originalAttrs == null) {
			originalAttrs = new LinkedHashSet<>(originalAttrOrder);
			session.setAttribute("originalAttrs", originalAttrs);
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

		// Build global manual rows, prefer top-level manualData if provided
		List<String> manualRowsList = new ArrayList<>();
		if (req.getManualData() != null && !req.getManualData().isBlank()) {
			// Dedupe preserving order
			LinkedHashSet<String> set = new LinkedHashSet<>();
			for (String part : req.getManualData().split(";", -1)) {
				String p = part == null ? "" : part.trim();
				if (!p.isEmpty()) set.add(p);
			}
			manualRowsList.addAll(set);
		} else {
			// Build from session originalTuples using unionColsSorted
			@SuppressWarnings("unchecked")
			List<List<String>> originalTuples = (List<List<String>>) session.getAttribute("originalTuples");
			if (originalTuples == null || originalTuples.isEmpty()) {
				String origJson = (String) session.getAttribute("originalTableJson");
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
					picked.add(v == null ? "" : v);
				}
				String tup = String.join(",", picked);
				if (!tup.isEmpty()) seen.add(tup);
			}
			manualRowsList.addAll(seen);
		}

		String builtManual = String.join(";", manualRowsList);
		System.out.println("DecomposeService.decomposeAll: built manualData for global RIC = " + builtManual);
		System.out.println("DecomposeService.decomposeAll: passing topFds = '" + topFds + "' to RicService");

		// Compute global RIC, passing top-level fds if any
		double[][] globalRic = ricService.computeRicFromManualData(builtManual, topFds);
		if (globalRic == null) globalRic = new double[0][0];

		// Per-table: project & minimize FDs (still return projected FD lists per table)
		List<DecomposeResponse> perTableResponses = new ArrayList<>();
		List<FD> combinedProjectedFds = new ArrayList<>();
		boolean allTablesBCNF = true; // BCNF bayrağı başlatıldı

		for (int i = 0; i < tables.size(); i++) {
			Set<String> attrs = tableAttrSets.get(i);

			// Project & minimize projected FDs for this table
			List<FD> projected = projectFDsByClosure(attrs, originalFDs);
			List<FD> minimizedProjected = minimizeLhsForFds(projected, originalFDs);

			combinedProjectedFds.addAll(minimizedProjected);

			// BCNF checking
			if (!checkBCNF(attrs, originalFDs, fdService)) {
				allTablesBCNF = false;
			}

			// Build response item with projected FDs only (no per-table dp/lj)
			List<String> projectedStr = minimizedProjected.stream().map(this::fdToString).collect(Collectors.toList());
			DecomposeResponse drResp = new DecomposeResponse(new double[0][0], projectedStr);
			perTableResponses.add(drResp);

			System.out.println("DecomposeService.decomposeAll: per-table idx=" + i + " attrs=" + attrs +
					" projected=" + projectedStr);
		}

		// global dp-preserved (combined projected FDs imply original)
		Map<String, FD> uniq = new LinkedHashMap<>();
		for (FD fd : combinedProjectedFds) {
			String key = fd.getLhs().toString() + "->" + fd.getRhs().toString();
			uniq.putIfAbsent(key, fd);
		}
		List<FD> combinedProjectedUnique = new ArrayList<>(uniq.values());
		boolean dpPreservedGlobal = checkDependencyPreserving(originalFDs, combinedProjectedUnique);

		// Build schemaList deterministically
		List<Set<String>> schemaList = tableAttrSets.stream()
				.map(s -> new LinkedHashSet<>(s))
				.collect(Collectors.toList());
		schemaList.sort(Comparator.comparing(s -> String.join(",", s)));

		boolean ljPreservedGlobal = checkLosslessDecomposition(new LinkedHashSet<>(originalAttrOrder), schemaList, originalFDs);

		System.out.println("DecomposeService.decomposeAll: dpPreservedGlobal=" + dpPreservedGlobal + " ljPreservedGlobal=" + ljPreservedGlobal);

		// Build response
		DecomposeAllResponse allResp = new DecomposeAllResponse();
		allResp.setTableResults(perTableResponses);
		allResp.setDpPreserved(dpPreservedGlobal);
		allResp.setLjPreserved(ljPreservedGlobal);
		allResp.setBCNFDecomposition(allTablesBCNF); // BCNF bayrağını set et

		// set global RIC matrix and manual rows (for frontend mapping) and unionCols
		allResp.setGlobalRic(globalRic);
		allResp.setGlobalManualRows(manualRowsList);
		allResp.setUnionCols(unionColsSorted);

		System.out.println("DecomposeService.decomposeAll: done");
		return allResp;
	}

	// Helper methods
	@SuppressWarnings("unchecked")
	private List<FD> getOriginalFDsOrThrow(HttpSession session) {
		List<FD> originalFDs = (List<FD>) session.getAttribute("originalFDs");
		if (originalFDs == null) {
			throw new IllegalStateException("Original FDs not found in session. Run compute first.");
		}
		return originalFDs;
	}

	@SuppressWarnings("unchecked")
	private List<String> getOriginalAttrOrder(HttpSession session) {
		Object obj = session.getAttribute("originalAttrOrder");
		if (obj == null) {
			throw new IllegalStateException("originalAttrOrder not found in session. Run compute first.");
		}
		List<?> raw = (List<?>) obj;
		return raw.stream().map(Object::toString).collect(Collectors.toList());
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

	// Lossless-join test
	private boolean checkLosslessDecomposition(Set<String> R, List<Set<String>> schemas, List<FD> originalFDs) {
		if (R == null || R.isEmpty() || schemas == null || schemas.isEmpty()) {
			return false;
		}

		// Create the initial matrix
		List<String> allAttributes = new ArrayList<>(R);
		int numAttrs = allAttributes.size();
		int numSchemas = schemas.size();
		String[][] matrix = new String[numSchemas][numAttrs];

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

					// Equalize values ​​in RHS columns
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

		// Test and control the results
		// If there is a row in the matrix including entirely of 'a' symbols, so it is lossless
		for (int i = 0; i < numSchemas; i++) {
			boolean allDistinguished = true;
			for (int j = 0; j < numAttrs; j++) {
				if (!matrix[i][j].startsWith("a")) {
					allDistinguished = false;
					break;
				}
			}
			if (allDistinguished) {
				// Lossless join
				return true;
			}
		}
		// Lossy join
		return false;
	}

	private String fdToString(FD fd) {
		String lhs = fd.getLhs().stream().sorted().collect(Collectors.joining(","));
		String rhs = fd.getRhs().stream().sorted().collect(Collectors.joining(","));
		return lhs + "→" + rhs;
	}

	public DecomposeResponse projectFDsOnly(DecomposeRequest req, HttpSession session) {
		System.out.println("DecomposeService.projectFDsOnly: start");

		// original FDs
		List<FD> originalFDs = getOriginalFDsOrThrow(session);
		System.out.println("DecomposeService.projectFDsOnly: originalFDs = " + originalFDs);

		// originalAttrOrder
		List<String> originalAttrOrder = getOriginalAttrOrder(session);
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

		// Just showing functional dependencies, no calculating ric so return 0
		double[][] emptyRic = new double[0][0];

		DecomposeResponse resp = new DecomposeResponse(emptyRic, fdsStr);
		System.out.println("DecomposeService.projectFDsOnly: done -> " + resp);
		return resp;
	}

	public boolean checkBCNF(Set<String> attributes, List<FD> allOriginalFds, FDService fdService) {
		// Create the full set of FDs to be used for inclusion (F+ = Original + Transitive)
		List<FD> transitiveFDs = fdService.findTransitiveFDs(allOriginalFds);
		Set<FD> allFDsForClosure = new HashSet<>(allOriginalFds);
		allFDsForClosure.addAll(transitiveFDs);
		List<FD> fdsListForClosure = new ArrayList<>(allFDsForClosure);

		// Call the robust method that checks all subsets
		return isRelationBCNF(attributes, fdsListForClosure);
	}

	private boolean isRelationBCNF(Set<String> attributes, List<FD> allFdsForClosure) {
		Set<String> allAttributes = new HashSet<>(attributes);
		List<String> attrList = new ArrayList<>(attributes);
		// Number of attributes in the table
		int n = attrList.size();

		// Loop through 2^n - 1 (all non-empty subsets)
		for (int mask = 1; mask < (1 << n); mask++) {
			Set<String> X = new LinkedHashSet<>();
			for (int i = 0; i < n; i++) {
				// Potential determinant X
				if ((mask & (1 << i)) != 0) X.add(attrList.get(i));
			}
			// If the entire attribute set X is (R_i), skip (X != R_i)
			if (X.size() == allAttributes.size()) {
				continue;
			}
			// Calculate Closure: F+ (allFdsForClosure) is used for closure
			Set<String> closureOfX = fdService.computeClosure(X, allFdsForClosure);

			// Intersect the closure of X with the attributes R_i (X+ ∩ R_i)
			Set<String> closureRestrictedToRi = new HashSet<>(closureOfX);
			closureRestrictedToRi.retainAll(allAttributes);

			// Non-triviality check: Check if X implies something non-trivial in R_i
			Set<String> impliedNonTrivial = new HashSet<>(closureRestrictedToRi);
			impliedNonTrivial.removeAll(X);

			// If X implies something non-trivial
			if (!impliedNonTrivial.isEmpty()) {

				// Check: Is X a Super Key? (X+ ∩ R_i = R_i?)
				if (!closureRestrictedToRi.containsAll(allAttributes)) {
					// X is not a super key, then BCNF violation
					return false;
				}
			}
		}
		// All non-trivial determinants are super keys
		return true;
	}
}

