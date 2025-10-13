package com.project.plaque.plaque_calculator.service;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * RicService: Computing relational information content(ric) matrix
 * - computeRic(columns, session) : Uses the initial/original table in the session
 * - computeRicFromManualData(manualData) : Uses the manualData string directly from the frontend
 * Uses ProcessBuilder to call external relational_information_content jar.
 */
@Service
public class RicService {

	//JAR path
	@Value("${ric.jar.path:libs/relational_information_content-1.0-SNAPSHOT-jar-with-dependencies.jar}")
	private String ricJarPath;

	private Path ricJar;

	private final Gson gson = new Gson();

	public RicService() {
		// ricJarPath injected by Spring, init in @PostConstruct
	}

	@PostConstruct
	private void init() {
		this.ricJar = Paths.get(ricJarPath);
		System.out.println("RicService.init -> ricJar = " + ricJar.toAbsolutePath());
	}

	public double[][] computeRic(List<Integer> columns, HttpSession session) {
		String initJson = (String) session.getAttribute("initialCalcTableJson");
		if (initJson == null || initJson.trim().isEmpty() || "[]".equals(initJson)) {
			initJson = (String) session.getAttribute("originalTableJson");
		}
		if (initJson == null || initJson.trim().isEmpty() || "[]".equals(initJson)) {
			System.out.println("RicService.computeRic -> No table JSON found in session");
			return new double[0][0];
		}

		List<List<String>> table;
		try {
			Type t = new TypeToken<List<List<String>>>(){}.getType();
			table = gson.fromJson(initJson, t);
			if (table == null) table = Collections.emptyList();
		} catch (Exception ex) {
			ex.printStackTrace();
			return new double[0][0];
		}

		String manualData = table.stream()
				.map(row -> columns.stream()
						.map(idx -> {
							int i = idx == null ? -1 : idx;
							return (i >= 0 && i < row.size()) ? String.valueOf(row.get(i)) : "";
						})
						.collect(Collectors.joining(",")))
				.collect(Collectors.joining(";"));
		return computeRicFromManualData(manualData);
	}

	// one-arg version (API that front-end calls)
	public double[][] computeRicFromManualData(String manualData) {
		// default: no fds, no monteCarlo
		return computeRicFromManualData(manualData, "", false, 0);
	}

	// double-arg version (manual data + top-level fds)
	public double[][] computeRicFromManualData(String manualEncoded, String topLevelFds) {
		return computeRicFromManualData(manualEncoded, topLevelFds, false, 0);
	}

	// (manual data + fds + monteCarlo flag + samples)
	public double[][] computeRicFromManualData(String manualEncoded, String topLevelFds, boolean monteCarlo, int samples) {
		return computeRicFromManualDataInternal(manualEncoded, topLevelFds, /*timeLimitSeconds*/30, monteCarlo, samples);
	}

	private double[][] computeRicFromManualDataInternal(String manualEncoded, String topLevelFds,
														int timeLimitSeconds, boolean monteCarlo, int samples) {
		if (manualEncoded == null) manualEncoded = "";
		manualEncoded = manualEncoded.trim();

		if (!Files.exists(ricJar)) {
			throw new IllegalStateException("RIC jar not found at: " + ricJar.toAbsolutePath());
		}

		Path outFile;
		try {
			outFile = Files.createTempFile("ric-out-", ".csv");
		} catch (IOException e) {
			throw new RuntimeException("Cannot create temp file for RIC output", e);
		}

		List<String> args = new ArrayList<>();
		args.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
		args.add("-jar");
		args.add(ricJar.toAbsolutePath().toString());

		// passing encoded table as single arg
		args.add(manualEncoded);

		args.add("-e");
		args.add("--closure");
		args.add("--name");
		args.add(outFile.toAbsolutePath().toString());
		args.add("-i");
		args.add("-s");


		// adding monte carlo option
		if (monteCarlo) {
			args.add("-r");
			args.add(String.valueOf(samples));
		}

		// normalize and split FDs
		List<String> fdsList = new ArrayList<>();
		if (topLevelFds != null && !topLevelFds.trim().isEmpty()) {
			// normalize some arrow symbols to simple ASCII form then split
			String norm = topLevelFds.replace('→', '-').replace("—", "-");
			// split on semicolon or newline
			String[] fdParts = norm.split("[;\\r\\n]+");
			for (String seg : fdParts) {
				String tok = seg == null ? "" : seg.trim();
				if (!tok.isEmpty()) fdsList.add(tok);
			}
		}

		if (!fdsList.isEmpty()) args.addAll(fdsList);

		// Using processbuilder
		ProcessBuilder pb = new ProcessBuilder(args);
		pb.redirectErrorStream(true);
		pb.directory(Paths.get(".").toFile());

		StringBuilder procOutput = new StringBuilder();
		try {
			Process p = pb.start();

			try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = in.readLine()) != null) {
					procOutput.append(line).append(System.lineSeparator());
				}
			}

			boolean finished = p.waitFor(Math.max(60, timeLimitSeconds + 30), TimeUnit.SECONDS);
			if (!finished) {
				p.destroyForcibly();
				throw new RuntimeException("RIC process timed out");
			}
			int exit = p.exitValue();
			if (exit != 0) {
				throw new RuntimeException("RIC process exited with code " + exit + ". Output:\n" + procOutput.toString());
			}

			if (!Files.exists(outFile)) {
				// fallback to stdout parsing
				return parseRicFromStdout(procOutput.toString());
			}

			List<String> lines = Files.readAllLines(outFile);
			List<String> numericLines = lines.stream()
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.collect(Collectors.toList());

			List<double[]> rows = new ArrayList<>();
			for (String l : numericLines) {
				String[] parts = l.split("[,\\s]+");
				List<Double> vals = new ArrayList<>();
				for (String tok : parts) {
					if (tok == null || tok.isBlank()) continue;
					try {
						vals.add(Double.parseDouble(tok));
					} catch (NumberFormatException nfe) {
						// skip non-numeric tokens
					}
				}
				if (!vals.isEmpty()) {
					double[] darr = new double[vals.size()];
					for (int i = 0; i < vals.size(); i++) darr[i] = vals.get(i);
					rows.add(darr);
				}
			}

			if (rows.isEmpty()) {
				return parseRicFromStdout(procOutput.toString());
			}

			int cols = rows.get(0).length;
			double[][] out = new double[rows.size()][cols];
			for (int r = 0; r < rows.size(); r++) {
				double[] rr = rows.get(r);
				if (rr.length != cols) {
					double[] tmp = new double[cols];
					Arrays.fill(tmp, 1.0);
					System.arraycopy(rr, 0, tmp, 0, Math.min(rr.length, cols));
					out[r] = tmp;
				} else {
					out[r] = rr;
				}
			}

			try { Files.deleteIfExists(outFile); } catch (IOException ignore) {}
			return out;

		} catch (IOException | InterruptedException ex) {
			throw new RuntimeException("Failed to execute RIC jar: " + procOutput.toString(), ex);
		}
	}

	private double[][] parseRicFromStdout(String stdout) {
		List<double[]> rows = new ArrayList<>();
		for (String line : stdout.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) continue;
			if (trimmed.matches(".*[A-Za-z].*")) continue;
			String[] toks = trimmed.split("[\\t\\s]+");
			List<Double> vals = new ArrayList<>();
			for (String t : toks) {
				try { vals.add(Double.parseDouble(t)); }
				catch (NumberFormatException nfe) { /* skip */ }
			}
			if (!vals.isEmpty()) {
				double[] arr = vals.stream().mapToDouble(Double::doubleValue).toArray();
				rows.add(arr);
			}
		}
		if (rows.isEmpty()) return new double[0][0];
		int cols = rows.get(0).length;
		double[][] out = new double[rows.size()][cols];
		for (int r = 0; r < rows.size(); r++) out[r] = rows.get(r);
		return out;
	}
}
