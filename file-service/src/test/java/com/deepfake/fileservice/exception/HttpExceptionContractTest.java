package com.deepfake.fileservice.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

class HttpExceptionContractTest {
    private final Probe controller = new Probe();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    static Stream<Arguments> clientErrors() {
        return Stream.of(
                Arguments.of(get("/probe/uuid/bad"), 400, "VALIDATION_FAILED"),
                Arguments.of(get("/probe/param").param("count", "bad"), 400, "VALIDATION_FAILED"),
                Arguments.of(get("/probe/param"), 400, "VALIDATION_FAILED"),
                Arguments.of(multipart("/probe/upload"), 400, "VALIDATION_FAILED"),
                Arguments.of(post("/probe/upload"), 400, "VALIDATION_FAILED"),
                Arguments.of(put("/probe/json"), 405, "METHOD_NOT_ALLOWED"),
                Arguments.of(post("/probe/json").contentType(MediaType.TEXT_PLAIN).content("x"), 415, "UNSUPPORTED_MEDIA_TYPE"));
    }

    @ParameterizedTest @MethodSource("clientErrors")
    void clientMistakesRetainTheirHttpStatus(RequestBuilder request, int status, String code)
            throws Exception {
        mvc.perform(request).andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    @Test void unexpectedErrorsHaveNeutralPolishMessages() throws Exception {
        controller.failure = new IllegalStateException("SELECT secret FROM internal_table");
        mvc.perform(get("/probe/failure")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Wystąpił wewnętrzny błąd serwera."));
    }

    @Test void responseStatusDoesNotExposeExceptionDetails() throws Exception {
        controller.failure = new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "HikariPool connection error");
        mvc.perform(get("/probe/failure")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Usługa jest chwilowo niedostępna. Spróbuj ponownie później."));
    }

    @Test void binaryOnlyClientsRetainErrorStatusWithoutNegotiationWarnings() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(
                "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver");
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            mvc.perform(get("/probe/uuid/bad").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isBadRequest()).andExpect(content().string(""));
            controller.failure = new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "not ready");
            mvc.perform(get("/probe/failure").accept(MediaType.APPLICATION_PDF))
                    .andExpect(status().isConflict()).andExpect(content().string(""));
            assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void unsupportedResponseTypeIs406WithoutBody() throws Exception {
        mvc.perform(get("/probe/json").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable()).andExpect(content().string(""));
    }

    @Test void methodRejectionRetainsAllowHeader() throws Exception {
        mvc.perform(put("/probe/json")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));
    }

    @Test void uploadOverflowIs413() throws Exception {
        controller.failure = new MaxUploadSizeExceededException(100);
        mvc.perform(get("/probe/failure")).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test void invalidPropertyIsValidationFailure() throws Exception {
        controller.failure = new PropertyReferenceException("unknown", TypeInformation.of(String.class), java.util.List.of());
        mvc.perform(get("/probe/failure")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test void asyncDisconnectAndTimeoutDoNotWriteBodyOrLogErrors() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            controller.failure = new AsyncRequestNotUsableException("peer disconnected");
            mvc.perform(get("/probe/failure")).andExpect(content().string(""));
            controller.failure = new AsyncRequestTimeoutException();
            mvc.perform(get("/probe/failure")).andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(""));
            assertThat(logs.list).isEmpty();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void multipartParseFailureIsLoggedWhileNonMultipartIsNot() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            mvc.perform(post("/probe/upload")).andExpect(status().isBadRequest());
            assertThat(logs.list).isEmpty();
            controller.failure = new org.springframework.web.multipart.MultipartException(
                    "Failed to parse multipart servlet request", new java.io.IOException("No space left on device"));
            mvc.perform(post("/probe/failure").contentType("multipart/form-data; boundary=x"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(logs.list).singleElement()
                    .satisfies(event -> assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @RestController static class Probe {
        Exception failure;
        @GetMapping("/probe/uuid/{id}") String uuid(@PathVariable UUID id) { return id.toString(); }
        @GetMapping("/probe/param") int param(@RequestParam int count) { return count; }
        @PostMapping("/probe/upload") String upload(@RequestPart MultipartFile file) { return "ok"; }
        @GetMapping(value="/probe/json", produces=MediaType.APPLICATION_JSON_VALUE)
        Map<String,String> json() { return Map.of("status", "ok"); }
        @PostMapping(value="/probe/json", consumes=MediaType.APPLICATION_JSON_VALUE)
        String postJson() { return "ok"; }
        @RequestMapping("/probe/failure") String failure() throws Exception { throw failure; }
    }
}
