package com.mineledger.accounting.mastery;

import java.util.List;

public record MultipleChoiceQuestion(String id, String conceptId, String prompt,
        List<AnswerOption> options, String correctOptionId, String explanation) {
    public MultipleChoiceQuestion {
        options = List.copyOf(options);
        if (id == null || id.isBlank() || conceptId == null || conceptId.isBlank()
                || prompt == null || prompt.isBlank() || explanation == null || explanation.isBlank()
                || options.size() != 4 || options.stream().map(AnswerOption::id).distinct().count() != 4
                || options.stream().filter(option -> option.id().equals(correctOptionId)).count() != 1) {
            throw new IllegalArgumentException("A question requires four unique options and exactly one correct option ID");
        }
    }
}
