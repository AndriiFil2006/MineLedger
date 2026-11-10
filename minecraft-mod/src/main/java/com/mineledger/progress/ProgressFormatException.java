package com.mineledger.progress;

import java.io.IOException;

/** Invalid or unsupported files must be preserved and made read-only for this session. */
public final class ProgressFormatException extends IOException {
    public ProgressFormatException(String message) { super(message); }
}
