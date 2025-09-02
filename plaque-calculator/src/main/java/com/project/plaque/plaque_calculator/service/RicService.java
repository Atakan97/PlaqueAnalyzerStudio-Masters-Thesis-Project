package com.project.plaque.plaque_calculator.service;

import jakarta.servlet.http.HttpSession;
import org.example.ric.Main; // repo'daki hesaplayıcı
import org.springframework.stereotype.Service;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RicService: Computing relational information content(ric) matrix
 * - computeRic(columns, session) : Uses the initial/original table in the session
 * - computeRicFromManualData(manualData) : Uses the manualData string directly from the frontend
 */
@Service
public class RicService {

	private final Gson gson = new Gson();

	public double[][] computeRic(List<Integer> columns, HttpSession session) {
		// Take initialCalcTableJson or originalTableJson
		String initJson = (String) session.getAttribute("initialCalcTableJson");
		if (initJson == null || initJson.trim().isEmpty() || initJson.equals("[]")) {
			initJson = (String) session.getAttribute("originalTableJson");
		}
		if (initJson == null || initJson.trim().isEmpty() || initJson.equals("[]")) {
			System.out.println("RicService.computeRic -> No table JSON found in session");
			return new double[0][0];
		}

		// JSON -> List<List<String>>
		List<List<String>> table;
		try {
			Type t = new TypeToken<List<List<String>>>(){}.getType();
			table = gson.fromJson(initJson, t);
			if (table == null) table = List.of();
		} catch (Exception ex) {
			ex.printStackTrace();
			return new double[0][0];
		}

		// Creating manualData format
		String manualData = table.stream()
				.map(row -> columns.stream()
						.map(idx -> {
							int i = idx;
							return (i < row.size() ? String.valueOf(row.get(i)) : "");
						})
						.collect(Collectors.joining(",")))
				.collect(Collectors.joining(";"));

		System.out.println("RicService.computeRic -> built manualData: " + manualData);
		return computeRicFromManualDataInternal(manualData, columns.size());
	}

	public double[][] computeRicFromManualData(String manualData) {
		int cols = 0;
		if (manualData != null && !manualData.isBlank()) {
			String firstRow = manualData.split(";", -1)[0];
			cols = firstRow.isEmpty() ? 0 : firstRow.split(",", -1).length;
		}
		return computeRicFromManualDataInternal(manualData, cols);
	}

	private double[][] computeRicFromManualDataInternal(String manualData, int expectedCols) {
		if (manualData == null) manualData = "";

		// CL args
		String[] args = new String[] { manualData, "-e", "--closure", "-i", "-s" };

		// stdout
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
		PrintStream old = System.out;
		System.setOut(ps);
		try {
			Main.main(args);
		} catch (Throwable ex) {
			ex.printStackTrace();
		} finally {
			System.out.flush();
			System.setOut(old);
		}
		String output = baos.toString(StandardCharsets.UTF_8);

		System.out.println("RicService.computeRicFromManualData -> Main output:\n" + output);

		// Filtering operation
		List<String> lines = Arrays.stream(output.split("\\r?\\n"))
				.filter(l -> !l.contains("transitive closure") && !l.startsWith("Runtime:") && !l.startsWith("FDs:"))
				.collect(Collectors.toList());

		int rows = lines.size();
		int cols = expectedCols;
		double[][] ric = new double[Math.max(0, rows)][Math.max(0, cols)];
		for (int r = 0; r < rows; r++) {
			String[] parts = lines.get(r).trim().split("\\s+|,");
			for (int c = 0; c < Math.min(parts.length, cols); c++) {
				try {
					ric[r][c] = Double.parseDouble(parts[c]);
				} catch (NumberFormatException nfe) {
					ric[r][c] = 1.0;
				}
			}
			for (int c = parts.length; c < cols; c++) ric[r][c] = 1.0;
		}

		System.out.println("RicService.computeRicFromManualData -> rows=" + rows + " cols=" + cols +
				" ric=" + Arrays.deepToString(ric));
		return ric;
	}
}
