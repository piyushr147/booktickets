package com.paytm.booktickets.controllers;

import com.paytm.booktickets.api.request.CreateShowRequest;
import com.paytm.booktickets.api.response.ShowResponse;
import com.paytm.booktickets.service.ShowService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {
	private final ShowService shows;

	public ShowController(ShowService shows) {
		this.shows = shows;
	}

	@PostMapping
	public ResponseEntity<ShowResponse> create(@RequestBody CreateShowRequest request) {
		ShowResponse created = shows.create(request);
		return ResponseEntity.created(URI.create("/shows/" + created.id())).body(created);
	}

	@GetMapping("/{showId}")
	public ShowResponse get(@PathVariable UUID showId) {
		return shows.get(showId);
	}
}
