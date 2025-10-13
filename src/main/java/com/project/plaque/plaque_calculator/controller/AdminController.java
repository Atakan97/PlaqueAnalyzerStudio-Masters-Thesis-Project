package com.project.plaque.plaque_calculator.controller;

import com.project.plaque.plaque_calculator.model.LogEntry;
import com.project.plaque.plaque_calculator.repository.LogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;


import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
public class AdminController {

	private final LogRepository logRepository;

	// Inject Admin Credentials from application.properties
	@Value("${admin.username}")
	private String adminUsername;

	@Value("${admin.password}")
	private String adminPassword;

	@Autowired
	public AdminController(LogRepository logRepository) {
		this.logRepository = logRepository;
	}

	// Existing method to view logs (requires a successful login check)
	@GetMapping("/logs")
	@Transactional
	public String viewLogs(
			HttpSession session,
			Model model,
			// userNameFilter filter
			@RequestParam(value = "userNameFilter", required = false) String userNameFilter
	) {
		// Simple security check
		if (session.getAttribute("isAdmin") == null) {
			return "redirect:/";
		}

		// Clean input
		String nameFilter = userNameFilter != null ? userNameFilter.trim() : "";

		// Take all logs (with the newest on top according to the timestamp)
		List<LogEntry> allLogs = logRepository.findAll(Sort.by(Sort.Direction.DESC, "timestamp"));

		List<LogEntry> filteredLogs;

		if (nameFilter.isEmpty()) {
			// If there is no filter, show all logs
			filteredLogs = allLogs;
		} else {
			// If there is a filter, show only BCNF logs and those matching the userName
			filteredLogs = allLogs.stream()
					.filter(log ->
							log.getUserName() != null &&
									log.getUserName().toLowerCase().contains(nameFilter.toLowerCase())
					)
					.collect(Collectors.toList());
		}

		// Add logs and filter value to the Model
		model.addAttribute("userNameFilter", nameFilter);
		// Adds logs to the model
		model.addAttribute("logs", filteredLogs);

		return "admin-logs";
	}

	// Log Deletion
	@PostMapping("/delete/{id}")
	@Transactional // Deletion requires a transaction
	public String deleteLogEntry(@PathVariable Long id, HttpSession session) {

		// Simple Security Check (Prevent unauthorized access)
		if (session.getAttribute("isAdmin") == null) {
			return "redirect:/";
		}

		try {
			// Delete the log entry by its ID
			logRepository.deleteById(id);
		} catch (Exception e) {
			// Log the error but continue to redirect
			System.err.println("Error deleting log entry with ID " + id + ": " + e.getMessage());
		}

		// Redirect back to the logs page after deletion to show the updated list
		return "redirect:/admin/logs";
	}

	// Handle admin login form submission
	@PostMapping("/login")
	public String handleAdminLogin(
			@RequestParam("adminUsername") String username,
			@RequestParam("adminPassword") String password,
			HttpSession session,
			Model model) {

		// Authentication check
		if (adminUsername.equals(username) && adminPassword.equals(password)) {

			// Successful login, set a flag in the session
			session.setAttribute("isAdmin", true);

			// Redirect to the logs page
			return "redirect:/admin/logs";

		} else {
			// Failed login, add an error message to the model (to be displayed on index.html)
			model.addAttribute("adminError", "Invalid username or password.");
			// Return to the index page to show the error
			return "index";
		}
	}

	// Handle Admin Logout
	@GetMapping("/logout")
	public String adminLogout(HttpSession session) {

		// Remove the "isAdmin" flag from the session and invalidate the session
		session.removeAttribute("isAdmin");
		session.invalidate();

		// Redirect to the main index page (login screen)
		return "redirect:/";
	}
}