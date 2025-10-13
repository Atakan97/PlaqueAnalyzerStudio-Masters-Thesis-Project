package com.project.plaque.plaque_calculator.repository;

import com.project.plaque.plaque_calculator.model.LogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogRepository extends JpaRepository<LogEntry, Long> {

}