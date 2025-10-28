package com.project.plaque.plaque_calculator.dto;

public class DecomposeStreamInitResponse {
	private String token;

	public DecomposeStreamInitResponse(String token) {
		this.token = token;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}
}
