package com.project.plaque.plaque_calculator.dto;

// Contains detailed information about lossless-join decomposition test results.
public class LosslessJoinDetail {
	private boolean isLossless;
	private String explanation;  // Explanation

	public LosslessJoinDetail() {
	}

	public boolean isLossless() {
		return isLossless;
	}

	public void setLossless(boolean lossless) {
		isLossless = lossless;
	}

	public String getExplanation() {
		return explanation;
	}

	public void setExplanation(String explanation) {
		this.explanation = explanation;
	}
}

