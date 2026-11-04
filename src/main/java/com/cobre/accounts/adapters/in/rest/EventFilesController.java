package com.cobre.accounts.adapters.in.rest;

import com.cobre.accounts.adapters.in.file.EventFileReader;
import com.cobre.accounts.application.port.in.EventFile;
import com.cobre.accounts.application.port.in.LoadEventFilesUseCase;
import com.cobre.accounts.application.port.in.LoadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /v1/event-files} (D2, spec §8.2): one load with one or more files in the multipart
 * field {@code files}. The files are read only once the load holds its lock (spec §7.3), in
 * parallel with virtual threads, and the report is written by {@link ApiJson}.
 */
@RestController
public class EventFilesController {

    private final LoadEventFilesUseCase loads;
    private final EventFileReader reader;
    private final long maxEventsPerLoad;

    public EventFilesController(LoadEventFilesUseCase loads, EventFileReader reader,
            @Value("${cbmm.upload.max-events-per-load:1000000}") long maxEventsPerLoad) {
        this.loads = Objects.requireNonNull(loads, "loads");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.maxEventsPerLoad = maxEventsPerLoad;
    }

    @Operation(summary = "Load one or more CBMM event files and get the load report (spec §8.1)")
    @ApiResponse(responseCode = "200", description = "The load report",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LoadReport.class)))
    @ApiResponse(responseCode = "400", description = "NO_FILES")
    @ApiResponse(responseCode = "409", description = "LOAD_IN_PROGRESS")
    @ApiResponse(responseCode = "413", description = "PAYLOAD_TOO_LARGE: a limit of spec §6.2")
    @PostMapping(path = "/v1/event-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> load(@RequestParam(name = "files", required = false) List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw ApiException.noFiles();
        }
        LoadResult result = loads.load(() -> read(files));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ApiJson.write(LoadReport.from(result)));
    }

    /** Called by the load under its lock: a refused load (409) reads nothing. */
    private List<EventFile> read(List<MultipartFile> files) {
        List<EventFile> read = new ArrayList<>(files.size());
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<EventFile>> pending = files.stream()
                    .map(file -> threads.submit(() -> reader.read(fileName(file), bytes(file))))
                    .toList();
            for (Future<EventFile> future : pending) {
                read.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the files", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        }
        long events = read.stream().mapToLong(file -> file.events().size()).sum();
        if (events > maxEventsPerLoad) {
            throw ApiException.tooManyEvents(maxEventsPerLoad);
        }
        return read;
    }

    /**
     * The name as sent; a missing or blank one is {@code ""}. It is only a label of the report and a
     * D4 tie-breaker (after which the payload hash decides, §7.3); it is never used as a path.
     */
    static String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "" : name;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the uploaded file", e);
        }
    }
}
