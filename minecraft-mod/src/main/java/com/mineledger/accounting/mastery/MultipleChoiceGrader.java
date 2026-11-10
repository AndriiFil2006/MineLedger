package com.mineledger.accounting.mastery;

import com.mineledger.accounting.mastery.GradingResult.Status;

public final class MultipleChoiceGrader {
    public GradingResult grade(MultipleChoiceQuestion question, String selectedOptionId) {
        if (selectedOptionId == null || question.options().stream().noneMatch(option -> option.id().equals(selectedOptionId))) {
            return new GradingResult(Status.INVALID_OPTION, "Select one of this question's four answers before submitting.");
        }
        return new GradingResult(question.correctOptionId().equals(selectedOptionId) ? Status.CORRECT : Status.INCORRECT,
                question.explanation());
    }
}
