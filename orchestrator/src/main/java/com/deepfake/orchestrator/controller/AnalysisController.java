package com.deepfake.orchestrator.controller;

import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisService service;

    @PostMapping
    public ResponseEntity<Analysis> create(@RequestBody CreateAnalysisRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, "dev-user"));
    }

    @GetMapping("/{id}")
    public Analysis get(@PathVariable UUID id) {
        return service.get(id);
    }
}
