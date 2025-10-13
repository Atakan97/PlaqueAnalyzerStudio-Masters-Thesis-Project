package com.project.plaque.plaque_calculator.model;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class FD {
	private final Set<String> lhs;
	private final Set<String> rhs;

	public FD(Set<String> lhs, Set<String> rhs) {
		// By copying sets to TreeSet (ordered set), make the internal representation canonical (standardized)
		this.lhs = new TreeSet<>(lhs);
		this.rhs = new TreeSet<>(rhs);
	}

	public Set<String> getLhs() { return lhs; }
	public Set<String> getRhs() { return rhs; }

	@Override
	public String toString() {
		//Ensuring consistent output by sorting LHS and RHS elements alphabetically
		String lhsSorted = lhs.stream().sorted().collect(Collectors.joining(","));
		String rhsSorted = rhs.stream().sorted().collect(Collectors.joining(","));
		return lhsSorted + "→" + rhsSorted;
	}

	// Equality control (according to FD's content)
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FD fd = (FD) o;
		// For FDs to be equal, the contents of the LHS and RHS sets must be equal
		return Objects.equals(lhs, fd.lhs) && Objects.equals(rhs, fd.rhs);
	}

	// Hashcode production (produced from LHS and RHS)
	@Override
	public int hashCode() {
		// Determines the hashcode of the object based on the content of the sets
		return Objects.hash(lhs, rhs);
	}
}
