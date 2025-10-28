package com.project.plaque.plaque_calculator.dto;

import java.util.List;

public class DecomposeResponse {
	private double[][] ricMatrix;
	private List<String> projectedFDs;
	private List<String> steps;

	// Constructor
	public DecomposeResponse(double[][] ricMatrix,
							 List<String> projectedFDs) {
		this(ricMatrix, projectedFDs, null);
	}

	public DecomposeResponse(double[][] ricMatrix,
							 List<String> projectedFDs,
							 List<String> steps) {
		this.ricMatrix = ricMatrix;
		this.projectedFDs = projectedFDs;
		this.steps = steps;
	}

	// Getters & setters
	public double[][] getRicMatrix() {
		return ricMatrix;
	}
	public void setRicMatrix(double[][] ricMatrix) {
		this.ricMatrix = ricMatrix;
	}

	public List<String> getProjectedFDs() {
		return projectedFDs;
	}
	public void setProjectedFDs(List<String> projectedFDs) {
		this.projectedFDs = projectedFDs;
	}

	public List<String> getSteps() {
		return steps;
	}
	public void setSteps(List<String> steps) {
		this.steps = steps;
	}

}
