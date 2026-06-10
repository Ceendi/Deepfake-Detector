package com.deepfake.orchestrator.report;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;

import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import com.deepfake.orchestrator.dto.response.AnalysisResponse;

/**
 * Renders a one-page analysis report PDF from the API view. Semester 1: title + a field table over
 * safe scalar fields only (no user-controlled strings, no HTML) — Grad-CAM embeds land in semester 2.
 * Stateless, so the singleton is shared across requests.
 */
@Service
public class ReportPdfService {

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 11);
    private static final Font FOOTER = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8);

    public byte[] render(AnalysisResponse a) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 64, 56);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Paragraph title = new Paragraph("DeepfakeDetector — Analysis Report", TITLE);
            title.setSpacingAfter(16f);
            doc.add(title);

            doc.add(fields(a));

            Paragraph footer = new Paragraph("Generated at " + Instant.now(), FOOTER);
            footer.setSpacingBefore(24f);
            doc.add(footer);

            doc.close();
        } catch (Exception e) { // OpenPDF throws checked DocumentException; a render failure is a 500
            throw new IllegalStateException("PDF render failed for " + a.id(), e);
        }
        return out.toByteArray();
    }

    private PdfPTable fields(AnalysisResponse a) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        row(table, "Analysis ID", a.id().toString());
        row(table, "Type", a.type().name());
        row(table, "Status", a.status().name());
        row(table, "Verdict", a.verdict());
        row(table, "Confidence", plain(a.confidence()));
        row(table, "Video probability", plain(a.videoProb()));
        row(table, "Audio probability", plain(a.audioProb()));
        row(table, "Created at", String.valueOf(a.createdAt()));
        row(table, "Updated at", String.valueOf(a.updatedAt()));
        return table;
    }

    private void row(PdfPTable table, String label, String value) {
        table.addCell(cell(new Phrase(label, LABEL)));
        table.addCell(cell(new Phrase(value == null ? "—" : value, VALUE)));
    }

    private PdfPCell cell(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setPadding(6f);
        return cell;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
