package com.project.plaque.plaque_calculator.dto;

import java.util.List;

public class DecomposeRequest {

	private String manualData;
	private List<Integer> columns;
	private String fds;
	private int timeLimit;
	private boolean monteCarlo;
	private int samples;
	private List<Integer> baseColumns;

	// No-arg constructor
	public DecomposeRequest() {}

	// Getters & Setters
	public String getManualData() { return manualData; }
	public void setManualData(String manualData) { this.manualData = manualData; }

	public List<Integer> getColumns() { return columns; }
	public void setColumns(List<Integer> columns) { this.columns = columns; }

	public String getFds() { return fds; }
	public void setFds(String fds) { this.fds = fds; }

	public int getTimeLimit() { return timeLimit; }
	public void setTimeLimit(int timeLimit) { this.timeLimit = timeLimit; }

	public boolean isMonteCarlo() { return monteCarlo; }
	public void setMonteCarlo(boolean monteCarlo) { this.monteCarlo = monteCarlo; }

	public int getSamples() { return samples; }
	public void setSamples(int samples) { this.samples = samples; }

	public List<Integer> getBaseColumns() { return baseColumns; }
	public void setBaseColumns(List<Integer> baseColumns) { this.baseColumns = baseColumns; }
}
