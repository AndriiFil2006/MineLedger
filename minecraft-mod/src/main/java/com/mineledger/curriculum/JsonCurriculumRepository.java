package com.mineledger.curriculum;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads either a bundled data resource or a standalone file using the same validator. */
public final class JsonCurriculumRepository implements CurriculumRepository {
    public static final String RESOURCE = "/data/mineledger/curriculum/accounting_101.json";
    private final Path file;

    public JsonCurriculumRepository() { this.file = null; }
    public JsonCurriculumRepository(Path file) { this.file = file; }

    @Override
    public Curriculum load() throws IOException {
        String source = file == null ? RESOURCE : file.toString();
        try (var input = file == null ? JsonCurriculumRepository.class.getResourceAsStream(RESOURCE) : Files.newInputStream(file)) {
            if (input == null) throw new IOException("Missing required curriculum: " + source);
            byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("Curriculum is too large: " + source);
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return new CurriculumLoader().load(new StringReader(text), source);
        }
    }
}
