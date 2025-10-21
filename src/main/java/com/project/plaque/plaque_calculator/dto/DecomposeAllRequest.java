package com.project.plaque.plaque_calculator.dto;

import java.util.List;


 // Wrapper request to submit multiple DecomposeRequest objects (one per decomposed table)
 // Reuse DecomposeRequest for per-table options (columns, manualData, flags)
public class DecomposeAllRequest {
	private List<DecomposeRequest> tables;
	// optional global flags
	private boolean losslessJoin;
	private boolean dependencyPreserve;
	private int timeLimit;
	private boolean monteCarlo;
	private int samples;
	private String manualData;
	private String fds;
	// Limits checks to a subset of the original relation (used for nested normalization)
	private List<Integer> baseColumns;

	public DecomposeAllRequest() {}

	public List<DecomposeRequest> getTables() { return tables; }
	public void setTables(List<DecomposeRequest> tables) { this.tables = tables; }

	public boolean isLosslessJoin() { return losslessJoin; }
	public void setLosslessJoin(boolean losslessJoin) { this.losslessJoin = losslessJoin; }

	public boolean isDependencyPreserve() { return dependencyPreserve; }
	public void setDependencyPreserve(boolean dependencyPreserve) { this.dependencyPreserve = dependencyPreserve; }

	public int getTimeLimit() { return timeLimit; }
	public void setTimeLimit(int timeLimit) { this.timeLimit = timeLimit; }

	public boolean isMonteCarlo() { return monteCarlo; }
	public void setMonteCarlo(boolean monteCarlo) { this.monteCarlo = monteCarlo; }

	public int getSamples() { return samples; }
	public void setSamples(int samples) { this.samples = samples; }

	public String getManualData() { return manualData; }
	public void setManualData(String manualData) { this.manualData = manualData; }

	public String getFds() { return fds; }
	public void setFds(String fds) { this.fds = fds; }

	public List<Integer> getBaseColumns() { return baseColumns; }
	public void setBaseColumns(List<Integer> baseColumns) { this.baseColumns = baseColumns; }
}
