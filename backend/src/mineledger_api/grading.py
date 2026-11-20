"""Deterministic multiple-choice grading: a direct port of Java `MultipleChoiceGrader`.

Option IDs are compared exactly (case- and whitespace-sensitive), never by position or display text.
Stateless: grading never records attempts, credits or XP. No LLM is involved.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from enum import Enum

INVALID_OPTION_FEEDBACK = "Select one of this question's four answers before submitting."


class GradingStatus(str, Enum):
    CORRECT = "correct"
    INCORRECT = "incorrect"
    INVALID_OPTION = "invalid_option"


@dataclass(frozen=True)
class GradingResult:
    status: GradingStatus
    feedback: str

    @property
    def correct(self) -> bool:
        return self.status is GradingStatus.CORRECT

    @property
    def valid(self) -> bool:
        return self.status is not GradingStatus.INVALID_OPTION


def grade_multiple_choice(
    option_ids: Iterable[str], correct_option_id: str, explanation: str, selected_option_id: str | None
) -> GradingResult:
    if selected_option_id is None or selected_option_id not in set(option_ids):
        return GradingResult(GradingStatus.INVALID_OPTION, INVALID_OPTION_FEEDBACK)
    status = GradingStatus.CORRECT if selected_option_id == correct_option_id else GradingStatus.INCORRECT
    return GradingResult(status, explanation)
