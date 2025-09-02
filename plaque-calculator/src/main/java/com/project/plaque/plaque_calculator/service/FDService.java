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
}
