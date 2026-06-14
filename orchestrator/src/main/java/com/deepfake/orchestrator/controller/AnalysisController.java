package com.deepfake.orchestrator.controller;

import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.dto.response.UserStatsResponse;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.report.ReportPdfService;
import com.deepfake.orchestrator.security.AuthenticatedUser;
import com.deepfake.orchestrator.security.CurrentUser;
import com.deepfake.orchestrator.service.AnalysisService;
import com.deepfake.orchestrator.service.ArtifactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class AnalysisController {
    private final AnalysisService service;
    private final ReportPdfService reportPdfService;
    private final ArtifactService artifactService;

    @Operation(summary = "Start an analysis for an uploaded file")
    @ApiResponse(responseCode = "201", description = "Analysis created (PENDING)")
    @ApiResponse(responseCode = "429", description = "Backpressure — in-flight analysis limit reached")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnalysisResponse create(@CurrentUser AuthenticatedUser user,
                                   @Valid @RequestBody CreateAnalysisRequest req) {
        return service.create(req, user.id());
    }

    // PagedModel, not the raw Page/PageImpl whose JSON shape is unstable across versions.
    @Operation(summary = "List the caller's analyses (paginated, newest first)")
    @GetMapping
    public PagedModel<AnalysisSummary> list(
            @CurrentUser AuthenticatedUser user,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return new PagedModel<>(service.list(user.id(), pageable));
    }

    // The exact "/stats" pattern outranks the "/{id}" template, so this never resolves as an id.
    @Operation(summary = "Aggregate stats of the caller's analyses (homepage dashboard)")
    @GetMapping("/stats")
    public UserStatsResponse stats(@CurrentUser AuthenticatedUser user) {
        return service.stats(user.id());
    }

    @Operation(summary = "Get one analysis by id")
    @ApiResponse(responseCode = "200", description = "The owned analysis")
    @ApiResponse(responseCode = "404", description = "Missing or not owned (IDOR)")
    @GetMapping("/{id}")
    public AnalysisResponse get(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        return service.get(id, user.id());
    }

    @Operation(summary = "Stream progress + final result for one analysis (SSE)")
    @ApiResponse(responseCode = "200", description = "text/event-stream")
    @ApiResponse(responseCode = "404", description = "Missing or not owned (IDOR)")
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        return service.openStream(id, user.id());
    }

    @Operation(summary = "Download the analysis report as PDF (COMPLETED only)")
    @ApiResponse(responseCode = "200", description = "application/pdf")
    @ApiResponse(responseCode = "409", description = "Report not ready (analysis not COMPLETED)")
    @ApiResponse(responseCode = "404", description = "Missing or not owned (IDOR)")
    @GetMapping(value = "/{id}/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> report(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        AnalysisResponse a = service.get(id, user.id()); // reuses the IDOR 404 guard
        if (a.status() != AnalysisStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "report available only for COMPLETED analyses");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(reportPdfService.render(a));
    }

    @Operation(summary = "Download a Grad-CAM artifact (PNG) referenced by details.gradcamKeys")
    @ApiResponse(responseCode = "200", description = "image/png")
    @ApiResponse(responseCode = "404", description = "Missing, not owned (IDOR), or not a recorded artifact")
    @ApiResponse(responseCode = "503", description = "Artifact storage unavailable")
    @GetMapping(value = "/{id}/artifacts/{source}/{name}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> artifact(@PathVariable UUID id, @PathVariable String source,
                                           @PathVariable String name, @CurrentUser AuthenticatedUser user) {
        byte[] png = artifactService.download(id, source, name, user.id());
        return ResponseEntity.ok()
                // Artifacts are immutable per analysis; let the browser cache its own heatmaps.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400, immutable")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @Operation(summary = "Cancel an in-progress analysis")
    @ApiResponse(responseCode = "200", description = "Cancelled")
    @ApiResponse(responseCode = "409", description = "Already finished (COMPLETED/FAILED)")
    @ApiResponse(responseCode = "404", description = "Missing or not owned (IDOR)")
    @DeleteMapping("/{id}")
    public AnalysisResponse cancel(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        return service.cancel(id, user.id());
    }

    // Distinct from cancel (DELETE /{id}): that soft-stops a running analysis; this permanently
    // removes a finished one from history. They map to two different UI affordances and must not
    // collide — overloading DELETE /{id} would turn a retried/double-clicked cancel on an already
    // CANCELLED analysis (idempotent 200 today) into a silent hard delete.
    @Operation(summary = "Permanently delete a finished analysis from the caller's history")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "409", description = "Still in progress — cancel it first")
    @ApiResponse(responseCode = "404", description = "Missing or not owned (IDOR)")
    @DeleteMapping("/{id}/record")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        // delete() commits the row removal and returns the analysis's Grad-CAM object keys; reclaim
        // them from storage afterwards (post-commit, best-effort — a failed object delete just leaves
        // an orphan for a later sweep and must not turn a successful delete into a 5xx).
        artifactService.deleteArtifacts(service.delete(id, user.id()));
    }
}
