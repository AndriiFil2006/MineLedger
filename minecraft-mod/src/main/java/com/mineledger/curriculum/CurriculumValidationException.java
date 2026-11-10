package com.mineledger.curriculum;

public final class CurriculumValidationException extends IllegalArgumentException {
    public CurriculumValidationException(String message) { super(message); }
    public CurriculumValidationException(String message, Throwable cause) { super(message, cause); }
}
