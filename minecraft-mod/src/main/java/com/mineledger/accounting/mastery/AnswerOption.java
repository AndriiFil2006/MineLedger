package com.mineledger.accounting.mastery;

public record AnswerOption(String id, String text) {
    public AnswerOption {
        if (id == null || id.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("Option ID and text are required");
        }
    }
}
