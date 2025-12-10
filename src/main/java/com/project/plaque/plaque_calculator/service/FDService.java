package com.project.plaque.plaque_calculator.service;

import com.project.plaque.plaque_calculator.model.FD;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FDService {

	// Calculates the closure of the set X under FDs.
	public Set<String> computeClosure(Set<String> X, List<FD> fds) {
		Set<String> closure = new HashSet<>(X);
		boolean changed;
		do {
			changed = false;
			for (FD fd : fds) {
				if (closure.containsAll(fd.getLhs())
						&& !closure.containsAll(fd.getRhs())) {
					closure.addAll(fd.getRhs());
					changed = true;
				}
			}
		} while (changed);
		return closure;
	}

	// A function that finds functional dependencies obtained by transitive closure
	// originalFDs -> List of original FDs, entered by the user or read from file
	public List<FD> findTransitiveFDs(List<FD> originalFDs) {
		Set<FD> transitiveFDs = new HashSet<>();
		Set<FD> knownFDs = new HashSet<>(originalFDs);

		// A list to keep track of new finds each round
		List<FD> newlyFoundInLastIteration = new ArrayList<>(originalFDs);

		while (!newlyFoundInLastIteration.isEmpty()) {
			Set<FD> foundInThisIteration = new HashSet<>();
			// Compare each newly found FD with all known FDs
			for (FD newFd : newlyFoundInLastIteration) { // A -> B
				for (FD existingFd : knownFDs) { // C -> D

					// Rule 1: e.g., (A->B) ve (B->C) => (A->C)
					if (newFd.getRhs().equals(existingFd.getLhs())) {
						FD candidate = new FD(new HashSet<>(newFd.getLhs()), new HashSet<>(existingFd.getRhs()));
						if (!knownFDs.contains(candidate)) {
							foundInThisIteration.add(candidate);
						}
					}

					// Rule 2: e.g., (C->D) ve (D->A) => (C->A)
					if (existingFd.getRhs().equals(newFd.getLhs())) {
						FD candidate = new FD(new HashSet<>(existingFd.getLhs()), new HashSet<>(newFd.getRhs()));
						if (!knownFDs.contains(candidate)) {
							foundInThisIteration.add(candidate);
						}
					}
				}
			}

			// Prepare new finds for the next round
			newlyFoundInLastIteration = new ArrayList<>(foundInThisIteration);

			// Add to main lists
			knownFDs.addAll(newlyFoundInLastIteration);
			transitiveFDs.addAll(newlyFoundInLastIteration);
		}
		// Clear trivial dependencies (e.g. 1->1 or 1,2->1)
		transitiveFDs.removeIf(fd -> fd.getLhs().containsAll(fd.getRhs()));
		return new ArrayList<>(transitiveFDs);
	}

	/**
	 * Parse FD string to FD objects (attribute names only)
	 * Format: "A,B->C;D->E" or "A,B→C;D→E"
	 *
	 * @param fdStr FD string with attribute names
	 * @return List of FD objects
	 */
	public List<FD> parseFDString(String fdStr) {
		if (fdStr == null || fdStr.isBlank()) {
			return Collections.emptyList();
		}

		// Normalize arrows
		fdStr = fdStr.replace("→", "->");

		List<FD> result = new ArrayList<>();
		for (String part : fdStr.split(";")) {
			part = part.trim();
			if (part.isEmpty()) continue;

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

	/**
	 * Parse FD string to FD objects (supports column indexes)
	 * Format: "1,2->3;4->5" or "A,B->C;D->E"
	 *
	 * @param fdStr FD string with column indexes (1-based) or attribute names
	 * @param attributeOrder List of attribute names for column index conversion
	 * @return List of FD objects
	 */
	public List<FD> parseFDStringWithIndexes(String fdStr, List<String> attributeOrder) {
		if (fdStr == null || fdStr.isBlank() || attributeOrder == null) {
			return Collections.emptyList();
		}

		// Normalize arrows
		fdStr = fdStr.replace("→", "->");

		List<FD> result = new ArrayList<>();
		for (String part : fdStr.split(";")) {
			part = part.trim();
			if (part.isEmpty()) continue;

			String[] sides = part.split("->");
			if (sides.length != 2) continue;

			String lhsStr = sides[0].trim();
			String rhsStr = sides[1].trim();
			if (lhsStr.isEmpty() || rhsStr.isEmpty()) continue;

			// Parse LHS (left hand side)
			Set<String> lhs = parseAttributeSet(lhsStr, attributeOrder);

			// Parse RHS (right hand side)
			Set<String> rhs = parseAttributeSet(rhsStr, attributeOrder);

			// Create FD if both sides are valid
			if (!lhs.isEmpty() && !rhs.isEmpty()) {
				result.add(new FD(lhs, rhs));
			}
		}
		return result;
	}

	/**
	 * Parse attribute set from string (supports column indexes or attribute names)
	 *
	 * @param attrStr Attribute string (e.g., "1,2" or "A,B")
	 * @param attributeOrder List of attribute names for column index conversion
	 * @return Set of attribute names
	 */
	private Set<String> parseAttributeSet(String attrStr, List<String> attributeOrder) {
		Set<String> attrs = new LinkedHashSet<>();
		String[] parts = attrStr.split(",");

		for (String part : parts) {
			part = part.trim();
			if (part.isEmpty()) continue;

			// Try to convert column index to attribute name
			try {
				int colIdx = Integer.parseInt(part) - 1; // 1-based → 0-based
				if (colIdx >= 0 && colIdx < attributeOrder.size()) {
					attrs.add(attributeOrder.get(colIdx));
				}
			} catch (NumberFormatException e) {
				// Already an attribute name
				attrs.add(part);
			}
		}

		return attrs;
	}

	/**
	 * Parse display format FD strings (e.g., "1,2,3→5") into FD objects.
	 * Keeps the original format without converting indices to attribute names.
	 * Used for calculating transitive FDs while preserving index-based display format.
	 *
	 * @param fdStrings List of FD strings in display format
	 * @return List of FD objects
	 */
	public List<FD> parseFdsFromDisplayStrings(List<String> fdStrings) {
		if (fdStrings == null || fdStrings.isEmpty()) {
			return Collections.emptyList();
		}
		List<FD> result = new ArrayList<>();
		for (String fdStr : fdStrings) {
			String normalized = fdStr.replace("→", "->");
			String[] sides = normalized.split("->");
			if (sides.length != 2) continue;
			Set<String> lhs = Arrays.stream(sides[0].split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.collect(Collectors.toCollection(LinkedHashSet::new));
			Set<String> rhs = Arrays.stream(sides[1].split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.collect(Collectors.toCollection(LinkedHashSet::new));
			if (!lhs.isEmpty() && !rhs.isEmpty()) {
				result.add(new FD(lhs, rhs));
			}
		}
		return result;
	}
}
