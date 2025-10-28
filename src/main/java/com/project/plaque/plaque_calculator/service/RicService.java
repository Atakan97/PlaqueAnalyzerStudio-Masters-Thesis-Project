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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

	private static record RicAttempt(boolean monteCarlo, int samples, int timeoutSeconds) { }

	public record RicComputationResult(double[][] matrix, String finalStrategy, List<String> steps) { }

	public static class RicComputationException extends RuntimeException {
		private final List<String> steps;

		public RicComputationException(String message, List<String> steps, Throwable cause) {
			super(message, cause);
			this.steps = steps == null ? List.of() : List.copyOf(steps);
		}

		public List<String> getSteps() {
			return steps;
		}
	}

	private static class RicTimeoutException extends RuntimeException {
		RicTimeoutException(String message) {
			super(message);
		}
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
	// One-arg version (API that front-end calls)
	public double[][] computeRicFromManualData(String manualData) {
		return computeRicFromManualData(manualData, "", false, 0);
	}
	// Double-arg version (manual data + top-level fds)
	public double[][] computeRicFromManualData(String manualEncoded, String topLevelFds) {
		return computeRicFromManualData(manualEncoded, topLevelFds, false, 0);
	}
	// (manual data + fds + monteCarlo flag + samples)
	public double[][] computeRicFromManualData(String manualEncoded, String topLevelFds, boolean monteCarlo, int samples) {
		return computeRicFromManualDataInternal(manualEncoded, topLevelFds, /*timeLimitSeconds*/30, monteCarlo, samples);
	}

	/**
	 * Compute the RIC matrix with an adaptive feature: start with the user's input,
	 * then proceed decreasing Monte Carlo sample sizes when timeouts occur. Each attempt is
	 * tracked in the app, so the UI can show progress to the user.
	 */
	public RicComputationResult computeRicAdaptive(String manualEncoded, String topLevelFds,
												   boolean initialMonteCarlo, int initialSamples) {
		return computeRicAdaptive(manualEncoded, topLevelFds, initialMonteCarlo, initialSamples, null);
	}

	public RicComputationResult computeRicAdaptive(String manualEncoded, String topLevelFds,
												   boolean initialMonteCarlo, int initialSamples,
												   Consumer<String> progressCallback) {
		List<RicAttempt> attempts = buildAttempts(initialMonteCarlo, initialSamples);
		List<String> steps = new ArrayList<>();
		RuntimeException lastException = null;

		Consumer<String> recordStep = message -> {
			steps.add(message);
			if (progressCallback != null) {
				try {
					progressCallback.accept(message);
				} catch (Exception ignored) {
					// ignore callback failures so computation can continue
				}
			}
		};

		for (RicAttempt attempt : attempts) {
			String description = describeAttempt(attempt);
			recordStep.accept("Starting " + description + ".");
			long startNs = System.nanoTime();
			try {
				double[][] matrix = computeRicFromManualDataInternal(
						manualEncoded,
						topLevelFds,
						attempt.timeoutSeconds(),
						attempt.monteCarlo(),
						attempt.samples()
				);
				long elapsedMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
				recordStep.accept("Completed " + description + " in " + formatDuration(elapsedMs) + ".");
				return new RicComputationResult(matrix, description, List.copyOf(steps));
			} catch (RicTimeoutException timeout) {
				recordStep.accept("Timed out while " + description + " after "
						+ attempt.timeoutSeconds() + " seconds; moving on to the next stage.");
				lastException = timeout;
			} catch (RuntimeException ex) {
				recordStep.accept("Failed while " + description + ": " + ex.getMessage());
				throw new RicComputationException("RIC computation failed during "
						+ description, List.copyOf(steps), ex);
			}
		}

		String failureMsg = "RIC computation did not finish after fallback strategies.";
		recordStep.accept(failureMsg);
		throw new RicComputationException(
				failureMsg,
				List.copyOf(steps),
				lastException
		);
	}

	/**
	 * Building the ordered list of strategies will be applied for the computation. Duplicates are filtered out so that
	 * the same Monte Carlo configuration will never be tried twice.
	 */
	private List<RicAttempt> buildAttempts(boolean initialMonteCarlo, int initialSamples) {
		List<RicAttempt> attempts = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		int normalizedInitialSamples = initialMonteCarlo ? Math.max(initialSamples, 1) : 0;
		addAttempt(attempts, seen, new RicAttempt(initialMonteCarlo, normalizedInitialSamples, 10));
		addAttempt(attempts, seen, new RicAttempt(true, 100_000, 10));
		addAttempt(attempts, seen, new RicAttempt(true, 10_000, 10));
		addAttempt(attempts, seen, new RicAttempt(true, 1_000, 10));

		return attempts;
	}

	/**
	 * Function that processes attempt configurations. Based on Monte Carlo approximation and
	 * sample count, since the timeout is adjusted to 10 seconds for every attempt.
	 */
	private void addAttempt(List<RicAttempt> attempts, Set<String> seen, RicAttempt attempt) {
		String key = attempt.monteCarlo() + "#" + attempt.samples();
		if (seen.add(key)) {
			attempts.add(attempt);
		}
	}

	// Producing a short summary for logging/UI to user.
	private String describeAttempt(RicAttempt attempt) {
		if (!attempt.monteCarlo()) {
			return "with exact values";
		}
		return "Monte Carlo approximation with " + String.format(Locale.US, "%,d", attempt.samples()) + " samples";
	}

	private String formatDuration(long elapsedMs) {
		if (elapsedMs < 1000) {
			return elapsedMs + " ms";
		}
		return String.format(Locale.US, "%.2f s", elapsedMs / 1000.0);
	}

	/**
	 * Core implementation function that prepares the external process call, enforces a timeout and parses
	 * the resulting ric matrix.
	 */
	private double[][] computeRicFromManualDataInternal(String manualEncoded, String topLevelFds,
															int timeLimitSeconds, boolean monteCarlo, int samples) {
		if (manualEncoded == null) manualEncoded = "";
		manualEncoded = manualEncoded.trim();

		if (!Files.exists(ricJar)) {
			throw new IllegalStateException("RIC jar not found at: " + ricJar.toAbsolutePath());
		}

		Path outFile = null;
		Process process = null;
		Thread outputReader = null;
		try {
			outFile = Files.createTempFile("ric-out-", ".csv");
		} catch (IOException e) {
			throw new RuntimeException("Cannot create temp file for RIC output", e);
		}

		List<String> args = new ArrayList<>();
		args.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
		args.add("-jar");
		args.add(ricJar.toAbsolutePath().toString());

		// Passing encoded table as single arg
		args.add(manualEncoded);

		args.add("-e");
		args.add("--closure");
		args.add("--name");
		args.add(outFile.toAbsolutePath().toString());
		args.add("-i");
		args.add("-s");


		// Adding Monte Carlo approximation option
		if (monteCarlo) {
			args.add("-r");
			args.add(String.valueOf(samples));
		}

		// Normalize and split FDs
		List<String> fdsList = new ArrayList<>();
		if (topLevelFds != null && !topLevelFds.trim().isEmpty()) {
			// Normalize some arrow symbols to simple ASCII form then split
			String norm = topLevelFds.replace('→', '-').replace("—", "-");
			// Split on semicolon or newline
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
			process = pb.start();
			final Process procRef = process;

			Thread reader = new Thread(() -> {
				try (BufferedReader in = new BufferedReader(new InputStreamReader(procRef.getInputStream()))) {
					String line;
					while ((line = in.readLine()) != null) {
						procOutput.append(line).append(System.lineSeparator());
					}
					// Ignore stream read issues
				} catch (IOException ignore) {

				}
			});
			reader.setDaemon(true);
			reader.start();
			outputReader = reader;

			boolean finished = process.waitFor(Math.max(1, timeLimitSeconds), TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				reader.join(Math.min(TimeUnit.SECONDS.toMillis(timeLimitSeconds), 2000));
				throw new RicTimeoutException("RIC process timed out after " + timeLimitSeconds + " seconds");
			}
			reader.join(TimeUnit.SECONDS.toMillis(2));
			int exit = process.exitValue();
			if (exit != 0) {
				throw new RuntimeException("RIC process exited with code " + exit + ". Output:\n" + procOutput.toString());
			}

			if (!Files.exists(outFile)) {
				// Fallback to stdout parsing
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
						// Skip non-numeric tokens
					} catch (NumberFormatException nfe) {
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
			return out;

		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("RIC process was interrupted", ex);
		} catch (IOException ex) {
			throw new RuntimeException("Failed to execute RIC jar: " + procOutput.toString(), ex);
		} finally {
			if (outputReader != null && outputReader.isAlive()) {
				outputReader.interrupt();
				try {
					outputReader.join(200);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
			try {
				if (outFile != null) {
					Files.deleteIfExists(outFile);
				}
			} catch (IOException ignore) {
				// ignore cleanup failure
			}
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
