package com.project.plaque.plaque_calculator.dto;

import java.util.List;

public class DecomposeResponse {
	private double[][] ricMatrix;
	private List<String> projectedFDs;
	private boolean dpPreserved;
	private boolean ljPreserved;

	// Constructor
	public DecomposeResponse(double[][] ricMatrix,
							 List<String> projectedFDs,
							 boolean dpPreserved,
							 boolean ljPreserved) {
		this.ricMatrix = ricMatrix;
		this.projectedFDs = projectedFDs;
		this.dpPreserved = dpPreserved;
		this.ljPreserved = ljPreserved;
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

	public boolean isDpPreserved() {
		return dpPreserved;
	}

	public void setDpPreserved(boolean dpPreserved) {
		this.dpPreserved = dpPreserved;
	}

	public boolean isLjPreserved() {
		return ljPreserved;
	}

	public void setLjPreserved(boolean ljPreserved) {
		this.ljPreserved = ljPreserved;
	}
}
