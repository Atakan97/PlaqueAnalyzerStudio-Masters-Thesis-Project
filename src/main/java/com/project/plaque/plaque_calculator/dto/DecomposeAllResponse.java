package com.project.plaque.plaque_calculator.dto;

import java.util.ArrayList;
import java.util.List;


 // Response for /decompose-all: per-table DecomposeResponse objects plus global dp/lj booleans
public class DecomposeAllResponse {
	private double[][] globalRic;
	private List<DecomposeResponse> tableResults;
	private boolean dpPreserved;   // overall
	private boolean ljPreserved;   // overall
	// list of missing column indices (0-based) if any
	private List<Integer> missingColumns;
	private List<String> globalManualRows = new ArrayList<>();
	//  order used to build globalManualRows / globalRic columns
	private List<Integer> unionCols = new ArrayList<>();
	private boolean isBCNFDecomposition;
	// List of FDs that were lost in decomposition (not preserved)
	private List<String> missingFDs = new ArrayList<>();
	// Detailed information about lossless-join test
	private LosslessJoinDetail ljDetails;



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

	public List<String> getGlobalManualRows() { return globalManualRows;}
	public void setGlobalManualRows(List<String> rows) { this.globalManualRows = rows == null ? new ArrayList<>() : rows; }

	public List<Integer> getUnionCols() { return unionCols; }
	public void setUnionCols(List<Integer> unionCols) { this.unionCols = unionCols == null ? new ArrayList<>() : unionCols; }

	public boolean isBCNFDecomposition() {
		return isBCNFDecomposition;
	}

	public void setBCNFDecomposition(boolean BCNFDecomposition) {
		isBCNFDecomposition = BCNFDecomposition;
	}

	public List<String> getMissingFDs() {
		return missingFDs;
	}

	public void setMissingFDs(List<String> missingFDs) {
		this.missingFDs = missingFDs == null ? new ArrayList<>() : missingFDs;
	}

	public LosslessJoinDetail getLjDetails() {
		return ljDetails;
	}

	public void setLjDetails(LosslessJoinDetail ljDetails) {
		this.ljDetails = ljDetails;
	}

}
