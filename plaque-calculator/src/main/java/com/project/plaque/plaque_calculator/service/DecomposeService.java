package com.project.plaque.plaque_calculator.service;

import com.google.gson.Gson;
import com.project.plaque.plaque_calculator.dto.DecomposeAllRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeAllResponse;
import com.project.plaque.plaque_calculator.dto.DecomposeRequest;
import com.project.plaque.plaque_calculator.dto.DecomposeResponse;
import com.project.plaque.plaque_calculator.model.FD;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
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

		// originalFDs list
		List<FD> originalFDs = getOriginalFDsOrThrow(session);
		System.out.println("DecomposeService: originalFDs = " + originalFDs);

		// originalAttrOrder
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
		System.out.println("DecomposeService: lossless-join = " + ljPreserved);

		// Calculating relational information content
		double[][] ric;
		if (req.getManualData() != null && !req.getManualData().isBlank()) {
			System.out.println("DecomposeService: using manualData from request for RIC");
			ric = ricService.computeRicFromManualData(req.getManualData());
		} else {
			System.out.println("DecomposeService: using session + columns for RIC");
			ric = ricService.computeRic(cols, session);
		}
		System.out.println("DecomposeService: ric matrix computed: " + Arrays.deepToString(ric));

		// FD strings
		List<String> fdsStr = projected.stream()
				.map(this::fdToString)
				.collect(Collectors.toList());

		// Response
		DecomposeResponse resp = new DecomposeResponse(ric, fdsStr, dpPreserved, ljPreserved);
		System.out.println("DecomposeService.decompose: done -> " + resp);
		return resp;
	}

	// DecomposeService.decomposeAll
	public DecomposeAllResponse decomposeAll(DecomposeAllRequest req, HttpSession session) {
		System.out.println("DecomposeService.decomposeAll: start");

		// original FDs & attrs
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

		// snapshot current incoming tables into session history
		pushDecompositionHistory(session, tables);

		// union of all column indices (0-based)
		LinkedHashSet<Integer> unionColIdx = new LinkedHashSet<>();
		for (DecomposeRequest dr : tables) {
			List<Integer> cols = dr.getColumns();
			if (cols != null) unionColIdx.addAll(cols);
		}

		// Check if there are any missing original columns
		int totalCols = originalAttrOrder.size();
		List<Integer> missing = new ArrayList<>();
		for (int i = 0; i < totalCols; i++) {
			if (!unionColIdx.contains(i)) missing.add(i);
		}
		if (!missing.isEmpty()) {

			throw new IllegalStateException("Missing columns in decomposed tables: " + missing.stream().map(Object::toString).collect(Collectors.joining(",")));
		}

		// Global ric calculation
		// Make deterministic order: sort ascending
		List<Integer> unionColsSorted = new ArrayList<>(unionColIdx);
		Collections.sort(unionColsSorted);

		// Decide manualData preference for global RIC:
		// if request includes top-level manualData (DecomposeAllRequest), prefer that
		// else fall back to ricService.computeRic(unionColsSorted, session)
		double[][] globalRic;
		if (req.getManualData() != null && !req.getManualData().isBlank()) {
			// if DecomposeAllRequest supports a global manualData override
			globalRic = ricService.computeRicFromManualData(req.getManualData());
		} else {
			// Use the data stored in session (initialCalcTableJson or originalTableJson inside RicService)
			globalRic = ricService.computeRic(unionColsSorted, session);
		}

		// if computeRic returned empty matrix, signal to caller
		if (globalRic == null) globalRic = new double[0][0];

		// Per-table: projected FDs etc.
		List<DecomposeResponse> perTableResponses = new ArrayList<>();
		List<FD> combinedProjectedFds = new ArrayList<>();
		LinkedHashSet<String> unionAttrNames = new LinkedHashSet<>();

		for (DecomposeRequest dr : tables) {
			List<Integer> cols = dr.getColumns() == null ? Collections.emptyList() : dr.getColumns();
			Set<String> attrs = cols.stream()
					.map(i -> originalAttrOrder.get(i))
					.collect(Collectors.toCollection(LinkedHashSet::new));

			// Project & minimize projected FDs
			List<FD> projected = projectFDsByClosure(attrs, originalFDs);
			projected = minimizeLhsForFds(projected, originalFDs);

			combinedProjectedFds.addAll(projected);
			unionAttrNames.addAll(attrs);

			// Per-table dp/lj checks
			boolean dpPerTable = checkDependencyPreserving(originalFDs, projected);

			Set<String> S = new LinkedHashSet<>(attrs);
			Set<String> complement = new LinkedHashSet<>(originalAttrs);
			complement.removeAll(S);
			List<Set<String>> schemas = List.of(S, complement);
			boolean ljPerTable = checkLosslessDecomposition(new LinkedHashSet<>(originalAttrOrder), schemas, originalFDs);

			List<String> projectedStr = projected.stream().map(this::fdToString).collect(Collectors.toList());
			DecomposeResponse drResp = new DecomposeResponse(new double[0][0], projectedStr, dpPerTable, ljPerTable);
			perTableResponses.add(drResp);
		}

		// global dp-preserved: does combinedProjectedFds imply originalFDs?
		Map<String, FD> uniq = new LinkedHashMap<>();
		for (FD fd : combinedProjectedFds) {
			String key = fd.getLhs().toString() + "->" + fd.getRhs().toString();
			uniq.putIfAbsent(key, fd);
		}
		List<FD> combinedProjectedUnique = new ArrayList<>(uniq.values());
		boolean dpPreservedGlobal = checkDependencyPreserving(originalFDs, combinedProjectedUnique);

		// Building list of schema sets from incoming tables
		List<Set<String>> schemaList = new ArrayList<>();
		for (DecomposeRequest dr : tables) {
			List<Integer> cols = dr.getColumns() == null ? Collections.emptyList() : dr.getColumns();
			Set<String> attrsSet = cols.stream()
					.map(i -> originalAttrOrder.get(i))
					.collect(Collectors.toCollection(LinkedHashSet::new));
			schemaList.add(attrsSet);
		}
		boolean ljPreservedGlobal = checkLosslessDecomposition(new LinkedHashSet<>(originalAttrOrder), schemaList, originalFDs);

		// Building response
		DecomposeAllResponse allResp = new DecomposeAllResponse();
		allResp.setTableResults(perTableResponses);
		allResp.setDpPreserved(dpPreservedGlobal);
		allResp.setLjPreserved(ljPreservedGlobal);

		// set global RIC matrix
		allResp.setGlobalRic(globalRic);

		System.out.println("DecomposeService.decomposeAll: done");
		return allResp;
	}

	// History / Undo helpers

	 // Push a snapshot of the given tables (list of DecomposeRequest) into session history
	 // Snapshot format: List<List<Integer>> (each inner list = columns list for a table)
	private void pushDecompositionHistory(HttpSession session, List<DecomposeRequest> tables) {
		if (tables == null) return;
		List<List<Integer>> snapshot = new ArrayList<>();
		for (DecomposeRequest dr : tables) {
			if (dr == null || dr.getColumns() == null) snapshot.add(Collections.emptyList());
			else snapshot.add(new ArrayList<>(dr.getColumns()));
		}
		String json = gson.toJson(snapshot);

		@SuppressWarnings("unchecked")
		List<String> history = (List<String>) session.getAttribute(HISTORY_SESSION_KEY);
		if (history == null) history = new ArrayList<>();
		history.add(json);

		// No capping — keep full history
		session.setAttribute(HISTORY_SESSION_KEY, history);
	}

	/**
	 * Undo last decomposition:
	 * removes the last snapshot from history (the most recent)
	 * returns Optional of the new current snapshot,
	 * parsed as List<List<Integer>>. If nothing remains then returns Optional.empty()
	 */
	public Optional<List<List<Integer>>> undoLastDecomposition(HttpSession session) {
		@SuppressWarnings("unchecked")
		List<String> history = (List<String>) session.getAttribute(HISTORY_SESSION_KEY);
		if (history == null || history.isEmpty()) {
			return Optional.empty();
		}
		// remove last (the current)
		history.remove(history.size() - 1);
		session.setAttribute(HISTORY_SESSION_KEY, history);

		if (history.isEmpty()) return Optional.of(Collections.emptyList());

		String lastJson = history.get(history.size() - 1);
		try {
			// parse into List<List<Integer>>
			List<?> parsed = gson.fromJson(lastJson, List.class);
			List<List<Integer>> out = new ArrayList<>();
			if (parsed != null) {
				for (Object o : parsed) {
					if (o instanceof List) {
						List<?> inner = (List<?>) o;
						List<Integer> row = new ArrayList<>();
						for (Object item : inner) {
							try {
								Number n = (Number) item;
								row.add(n.intValue());
							} catch (ClassCastException cce) {
								// try parse from string
								try {
									row.add(Integer.parseInt(String.valueOf(item)));
								} catch (Exception e) { }
							}
						}
						out.add(row);
					}
				}
			}
			return Optional.of(out);
		} catch (Exception ex) {
			// parsing failed
			return Optional.empty();
		}
	}

	public List<String> getDecompositionHistory(HttpSession session) {
		@SuppressWarnings("unchecked")
		List<String> history = (List<String>) session.getAttribute(HISTORY_SESSION_KEY);
		if (history == null) return Collections.emptyList();
		return new ArrayList<>(history);
	}

	public void clearDecompositionHistory(HttpSession session) {
		session.removeAttribute(HISTORY_SESSION_KEY);
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

	// CHASE-based lossless join test
	private boolean checkLosslessDecomposition(Set<String> R, List<Set<String>> schemas, List<FD> originalFDs) {
		if (R == null || R.isEmpty() || schemas == null || schemas.isEmpty()) return false;
		List<String> attrs = new ArrayList<>(R);

		List<FD> fds = new ArrayList<>();
		for (FD fd : originalFDs) {
			Set<String> lhs = new LinkedHashSet<>();
			for (String a : fd.getLhs()) if (R.contains(a)) lhs.add(a);
			Set<String> rhs = new LinkedHashSet<>();
			for (String a : fd.getRhs()) if (R.contains(a)) rhs.add(a);
			if (!lhs.isEmpty() && !rhs.isEmpty()) fds.add(new FD(lhs, rhs));
		}

		List<Map<String,String>> rows = new ArrayList<>();
		for (int i = 0; i < schemas.size(); i++) {
			Set<String> si = schemas.get(i);
			Map<String,String> row = new LinkedHashMap<>();
			for (String a : attrs) {
				if (si.contains(a)) {
					row.put(a, "A:" + a);
				} else {
					row.put(a, "x_" + i + "_" + a);
				}
			}
			rows.add(row);
		}

		boolean changed = true;
		while (changed) {
			changed = false;
			for (FD fd : fds) {
				Set<String> lhs = fd.getLhs();
				Set<String> rhs = fd.getRhs();
				if (lhs == null || lhs.isEmpty() || rhs == null || rhs.isEmpty()) continue;

				for (int p = 0; p < rows.size(); p++) {
					Map<String,String> rowP = rows.get(p);
					for (int q = 0; q < rows.size(); q++) {
						Map<String,String> rowQ = rows.get(q);
						boolean lhsEqual = true;
						for (String a : lhs) {
							String vp = rowP.get(a);
							String vq = rowQ.get(a);
							if (vp == null || vq == null || !vp.equals(vq)) { lhsEqual = false; break; }
						}
						if (!lhsEqual) continue;
						for (String y : rhs) {
							String vP = rowP.get(y);
							String vQ = rowQ.get(y);
							if (vP == null || vQ == null) continue;
							if (!vP.equals(vQ)) {
								rowQ.put(y, vP);
								changed = true;
							}
						}
					}
				}
			}
		}

		for (Map<String,String> r : rows) {
			boolean allDistinguished = true;
			for (String a : attrs) {
				String v = r.get(a);
				if (v == null || !v.equals("A:" + a)) { allDistinguished = false; break; }
			}
			if (allDistinguished) return true;
		}

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

		DecomposeResponse resp = new DecomposeResponse(emptyRic, fdsStr, dpPreserved, ljPreserved);
		System.out.println("DecomposeService.projectFDsOnly: done -> " + resp);
		return resp;
	}
}
