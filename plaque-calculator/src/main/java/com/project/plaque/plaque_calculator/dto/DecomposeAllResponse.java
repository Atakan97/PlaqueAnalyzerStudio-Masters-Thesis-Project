package com.project.plaque.plaque_calculator.dto;

import java.util.List;

/**
 * Response for /decompose-all: per-table DecomposeResponse objects plus global dp/lj booleans
 */
public class DecomposeAllResponse {
	private double[][] globalRic;
	private List<DecomposeResponse> tableResults;
	private boolean dpPreserved;   // overall
	private boolean ljPreserved;   // overall
	// optional: list of missing column indices (0-based) if any
	private List<Integer> missingColumns;

	public DecomposeAllResponse() {}

	public double[][] getGlobalRic() { return globalRic; }
	public void setGlobalRic(double[][] globalRic) { this.globalRic = globalRic; }

	public List<DecomposeResponse> getTableResults() { return tableResults; }
	public void setTableResults(List<DecomposeResponse> tableResults) { this.tableResults = tableResults; }

	public boolean isDpPreserved() { return dpPreserved; }
	public void setDpPreserved(boolean dpPreserved) { this.dpPreserved = dpPreserved; }

	public boolean isLjPreserved() { return ljPreserved; }
	public void setLjPreserved(boolean ljPreserved) { this.ljPreserved = ljPreserved; }

	public List<Integer> getMissingColumns() { return missingColumns; }
	public void setMissingColumns(List<Integer> missingColumns) { this.missingColumns = missingColumns; }
}
