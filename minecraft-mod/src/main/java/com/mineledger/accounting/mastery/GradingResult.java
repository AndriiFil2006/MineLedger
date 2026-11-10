package com.mineledger.accounting.mastery;

public record GradingResult(Status status, String explanation) {
    public enum Status { CORRECT, INCORRECT, INVALID_OPTION }

    public boolean correct() {
        return status == Status.CORRECT;
    }

    public boolean valid() {
        return status != Status.INVALID_OPTION;
    }
}
