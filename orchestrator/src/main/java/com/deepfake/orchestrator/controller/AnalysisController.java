package com.deepfake.orchestrator.controller;

import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.security.AuthenticatedUser;
import com.deepfake.orchestrator.security.CurrentUser;
import com.deepfake.orchestrator.service.AnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class AnalysisController {
    private final AnalysisService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnalysisResponse create(@CurrentUser AuthenticatedUser user,
                                   @Valid @RequestBody CreateAnalysisRequest req) {
        return service.create(req, user.id());
    }

    // PagedModel, not the raw Page/PageImpl whose JSON shape is unstable across versions.
    @GetMapping
    public PagedModel<AnalysisSummary> list(
            @CurrentUser AuthenticatedUser user,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return new PagedModel<>(service.list(user.id(), pageable));
    }

    @GetMapping("/{id}")
    public AnalysisResponse get(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        return service.get(id, user.id());
    }
}
