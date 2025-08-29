package com.infomedia.abacox.telephonypricing.component.cdrprocessing;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Log4j2
@RequiredArgsConstructor
public class CdrFormatDetectorService {

    private final List<CdrProcessor> processors;
    private static final int PROBE_LINE_COUNT = 5; // Read the first lines to find a header

    /**
     * Detects the plant type ID by probing the initial content of a file.
     *
     * @param fileContent The byte array of the file content.
     * @return An Optional containing the plantTypeId if a matching processor is found, otherwise empty.
     */
    public Optional<Long> detectPlantType(byte[] fileContent) {
        if (fileContent == null || fileContent.length == 0) {
            log.warn("Cannot detect plant type from empty file content.");
            return Optional.empty();
        }

        List<String> initialLines = readInitialLines(fileContent);
        if (initialLines.isEmpty()) {
            log.warn("File content appears to be empty or contains no newlines.");
            return Optional.empty();
        }

        for (CdrProcessor processor : processors) {
            try {
                if (processor.probe(initialLines)) {
                    Long plantTypeId = processor.getPlantTypeIdentifier();
                    log.info("Detected file format as {} (Plant Type ID: {})", processor.getClass().getSimpleName(), plantTypeId);
                    return Optional.of(plantTypeId);
                }
            } catch (Exception e) {
                log.error("Error while probing with processor {}", processor.getClass().getSimpleName(), e);
            }
        }

        log.warn("Could not detect plant type for the provided file. No processor recognized the format.");
        return Optional.empty();
    }

    private List<String> readInitialLines(byte[] fileContent) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(fileContent), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() < PROBE_LINE_COUNT) {
                lines.add(line);
            }
        } catch (IOException e) {
            log.error("Failed to read initial lines from file content for probing.", e);
        }
        return lines;
    }
}