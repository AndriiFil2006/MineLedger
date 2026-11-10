package com.mineledger.curriculum;

/** One validated source of truth; no hardcoded fallback or alternate question bank. */
public final class CurriculumContent {
    private static final class Holder {
        private static final Curriculum DATA = load();
        private static Curriculum load() {
            try { return new JsonCurriculumRepository().load(); }
            catch (java.io.IOException exception) { throw new IllegalStateException("Cannot load required MineLedger curriculum", exception); }
        }
    }
    public static Curriculum get() { return Holder.DATA; }
    private CurriculumContent() { }
}
