package com.project.plaque.plaque_calculator.model;

import lombok.Getter;
import jakarta.persistence.*;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
public class LogEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// User information
	private String userName;

	// Process information
	// Ex: BCNF_SUCCESS
	private String activityType;
	private LocalDateTime timestamp = LocalDateTime.now();

	// Process duration
	private Long elapsedTimeSecs;

	// Detailed data (Input FDs, Output Tables, etc.)
	@Lob
	@Column(columnDefinition = "TEXT")
	private String detailsJson;

	// Number of attempts
	private Integer attempts;
}