package com.inventoryerp.kernel.search;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /search?q=} (port of {@code search.controller.ts}). */
@RestController
@RequestMapping("/search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    public Map<String, Object> global(@RequestParam(required = false) String q) {
        if (q == null || q.trim().length() < 2) {
            return Map.of("data", List.of());
        }
        return Map.of("data", search.search(q));
    }
}
