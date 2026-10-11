package com.deepfake.orchestrator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;

import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import org.junit.jupiter.api.Test;

import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

/** render() produces a valid (%PDF-prefixed) non-empty document, including when a prob is null. */
class ReportPdfServiceTest {

    private final ReportPdfService service = new ReportPdfService();

    @Test
    void rendersValidPdfForCompletedAnalysis() {
        byte[] pdf = service.render(completed(new BigDecimal("0.87"), new BigDecimal("0.41")));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    void rendersWhenASourceProbIsNull() {
        byte[] pdf = service.render(completed(new BigDecimal("0.87"), null)); // VIDEO-only: no audio prob

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    void renderedReportExplainsNoSpeechVideoOnlyBasisWithoutAudioScore() throws Exception {
        Instant now = Instant.parse("2026-10-11T10:00:00Z");
        AnalysisResponse analysis = new AnalysisResponse(UUID.randomUUID(), "alice", "f", "k",
                AnalysisType.FULL, AnalysisStatus.COMPLETED, "FAKE", new BigDecimal("0.6"),
                new BigDecimal("0.8"), null, Map.of("audio", Map.of("status", "NOT_APPLICABLE",
                    "reason", "NO_SPEECH", "message", "No model evidence")), null, now, now);
        try (PdfReader reader = new PdfReader(service.render(analysis))) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", " ");
            assertThat(text).contains("Not applicable (NO_SPEECH)", "Video only (no audio score)",
                    "confidence uses video only", "Audio probability—");
            assertThat(text).contains("0.8", "0.6");
        }
    }

    @Test
    void ordinaryFullReportHasNoNoSpeechExplanation() throws Exception {
        try (PdfReader reader = new PdfReader(service.render(completed(new BigDecimal("0.87"), new BigDecimal("0.41"))))) {
            assertThat(new PdfTextExtractor(reader).getTextFromPage(1))
                    .doesNotContain("Not applicable", "Video only");
        }
    }

    private AnalysisResponse completed(BigDecimal videoProb, BigDecimal audioProb) {
        Instant now = Instant.parse("2026-06-09T10:00:00Z");
        return new AnalysisResponse(UUID.randomUUID(), "alice", "file-1", "key-1",
                AnalysisType.FULL, AnalysisStatus.COMPLETED, "FAKE", new BigDecimal("0.74"),
                videoProb, audioProb, null, null, now, now);
    }
}
