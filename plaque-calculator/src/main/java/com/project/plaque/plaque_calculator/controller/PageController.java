package com.project.plaque.plaque_calculator.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Controller
public class PageController {

	@GetMapping("/")
	public String index() {
		return "index";  // templates/index.html
	}

	@GetMapping("/calc")
	public String calcForm() {
		return "calc";   // templates/calc.html
	}

	@GetMapping("/normalization")
	public String normalizePage(HttpSession session, Model model) {
		// Reset
		session.removeAttribute("normalizeAttempts");

		// Get the JSON of the first spreadsheet
		String initJson = (String) session.getAttribute("initialCalcTableJson");
		model.addAttribute("initialCalcTableJson", initJson != null ? initJson : "[]");

		// Add the RIC matrix to normalization.js as well
		String ricJson = (String) session.getAttribute("originalTableJson");
		model.addAttribute("ricJson", ricJson != null ? ricJson : "[]");

		// Read user fdList and computed fdListWithClosure
		String fdList = (String) session.getAttribute("fdList");
		String fdWithClosure = (String) session.getAttribute("fdListWithClosure");

		// Normalize user list into a set for comparison (no spaces, arrow ->)
		Set<String> userFds = new LinkedHashSet<>();
		if (fdList != null && !fdList.isBlank()) {
			String[] parts = fdList.split("[;\\r\\n]+");
			for (String p : parts) {
				String t = p == null ? "" : p.trim();
				if (t.isEmpty()) continue;
				t = t.replace('→', '-').replaceAll("-+>", "->");
				t = t.replaceAll("\\s*,\\s*", ",");
				userFds.add(t);
			}
		}

		List<String> fdItems = new ArrayList<>();
		Set<String> inferred = new LinkedHashSet<>();
		if (fdWithClosure != null && !fdWithClosure.isBlank()) {
			// fdWithClosure is expected to be ';' separated (ComputeController sets it)
			String[] parts = fdWithClosure.split("[;\\r\\n]+");
			for (String p : parts) {
				String t = p == null ? "" : p.trim();
				if (t.isEmpty()) continue;
				t = t.replace('→', '-').replaceAll("-+>", "->");
				t = t.replaceAll("\\s*,\\s*", ",");
				fdItems.add(t);
				if (!userFds.contains(t)) inferred.add(t);
			}
		} else {
			// fallback: show user-provided only
			fdItems.addAll(userFds);
		}

		model.addAttribute("fdItems", fdItems);
		model.addAttribute("fdInferred", inferred);

		return "normalization";
	}

	@PostMapping("/normalization/undo")
	@ResponseBody
	public List<String> undoDecomposition(HttpSession session) {
		List<String> history = (List<String>) session.getAttribute("decompositionHistory");
		if (history != null && !history.isEmpty()) {
			history.remove(history.size() - 1); // remove last state
		}
		session.setAttribute("decompositionHistory", history);
		return history; // return updated history (as JSON)
	}
}
