package com.project.plaque.plaque_calculator.service;

import com.project.plaque.plaque_calculator.model.FD;
import org.springframework.stereotype.Service;
import java.util.*;

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
}
