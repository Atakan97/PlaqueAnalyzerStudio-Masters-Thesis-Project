package com.project.plaque.plaque_calculator.model;

import java.util.Set;

public class FD {
	private final Set<String> lhs;
	private final Set<String> rhs;

	public FD(Set<String> lhs, Set<String> rhs) {
		this.lhs = lhs;
		this.rhs = rhs;
	}

	public Set<String> getLhs() { return lhs; }
	public Set<String> getRhs() { return rhs; }

	@Override
	public String toString() {
		return lhs + " → " + rhs;
	}
}
