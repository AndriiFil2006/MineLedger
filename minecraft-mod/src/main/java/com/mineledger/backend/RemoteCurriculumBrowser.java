package com.mineledger.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.mineledger.backend.RemoteCurriculum.*;

/**
 * Read-only navigation state for backend mode: courses, a course's units, a unit's assignments, and an
 * assignment's concepts and question preview. It has no access to grading, mastery, XP or progress storage.
 *
 * <p>Threading: every method must be called on the owner thread (Minecraft's client thread). Responses arrive
 * on background threads and are handed to {@code ownerThread} before any state changes. Each load takes a
 * ticket; a response whose ticket is no longer current, or that arrives after {@link #close()}, is discarded
 * without touching state or notifying the listener.
 */
public final class RemoteCurriculumBrowser {
    public enum Connection { CONNECTING, CONNECTED, UNAVAILABLE }
    public enum View { COURSES, COURSE, UNIT, ASSIGNMENT, QUESTION }

    /** One assignment's cross-checked preview content. */
    public record AssignmentPreview(AssignmentDetail assignment, List<ConceptDetail> concepts, List<Question> questions) {
        public AssignmentPreview {
            concepts = List.copyOf(concepts);
            questions = List.copyOf(questions);
        }

        public Optional<ConceptDetail> concept(String id) {
            return concepts.stream().filter(concept -> concept.id().equals(id)).findFirst();
        }

        /**
         * Combines the three assignment endpoints, refusing content that disagrees about IDs, ownership or
         * order rather than displaying a partly wrong assignment.
         */
        public static AssignmentPreview assemble(String assignmentId, AssignmentDetail detail,
                List<ConceptDetail> concepts, AssignmentQuestions questions) {
            require(detail.id().equals(assignmentId), "assignment ID " + detail.id() + " does not match " + assignmentId);
            require(questions.assignmentId().equals(assignmentId), "questions belong to " + questions.assignmentId());
            require(concepts.size() == detail.concepts().size(), "concept count changed");
            List<String> expectedQuestionIds = new ArrayList<>();
            for (int i = 0; i < concepts.size(); i++) {
                ConceptSummary summary = detail.concepts().get(i);
                ConceptDetail concept = concepts.get(i);
                require(concept.id().equals(summary.id()), "concept ID " + concept.id() + " does not match " + summary.id());
                require(concept.assignmentId().equals(assignmentId), "concept " + concept.id() + " belongs to another assignment");
                require(concept.requiredCorrectAnswers() == summary.requiredCorrectAnswers()
                        && concept.questionIds().size() == summary.questionCount()
                        && concept.lesson().map(lesson -> lesson.id()).equals(summary.lessonId()),
                        "concept " + concept.id() + " differs between endpoints");
                expectedQuestionIds.addAll(concept.questionIds());
            }
            List<String> actual = questions.questions().stream().map(Question::id).toList();
            require(actual.equals(expectedQuestionIds), "question IDs do not match the concepts' question lists");
            int index = 0;
            for (ConceptDetail concept : concepts) {
                for (int i = 0; i < concept.questionIds().size(); i++) {
                    Question question = questions.questions().get(index++);
                    require(question.conceptId().equals(concept.id()), "question " + question.id() + " names the wrong concept");
                }
            }
            return new AssignmentPreview(detail, concepts, questions.questions());
        }
    }

    private final CurriculumApi api;
    private final Executor ownerThread;
    private Runnable listener = () -> { };
    private boolean open;
    private long ticket;
    private Connection connection = Connection.CONNECTING;
    private boolean loading;
    private String error;
    private Runnable retry;
    private List<CourseSummary> courses;
    private CourseDetail course;
    private UnitDetail unit;
    private AssignmentPreview assignment;
    private int questionIndex = -1;

    public RemoteCurriculumBrowser(CurriculumApi api, Executor ownerThread) {
        this.api = api;
        this.ownerThread = ownerThread;
    }

    /** Called on the owner thread after a response or failure changed what should be displayed. */
    public void setListener(Runnable listener) { this.listener = listener == null ? () -> { } : listener; }

    public Connection connection() { return connection; }
    public boolean loading() { return loading; }
    public Optional<String> error() { return Optional.ofNullable(error); }
    public List<CourseSummary> courses() { return courses == null ? List.of() : courses; }
    public Optional<CourseDetail> course() { return Optional.ofNullable(course); }
    public Optional<UnitDetail> unit() { return Optional.ofNullable(unit); }
    public Optional<AssignmentPreview> assignment() { return Optional.ofNullable(assignment); }
    public int questionIndex() { return questionIndex; }

    public View view() {
        if (assignment != null) return questionIndex >= 0 ? View.QUESTION : View.ASSIGNMENT;
        if (unit != null) return View.UNIT;
        return course != null ? View.COURSE : View.COURSES;
    }

    /** Starts accepting responses; the first call requests the course list. Does not notify the listener. */
    public void open() {
        if (open) return;
        open = true;
        if (courses == null && error == null) loadCourses(false);
    }

    /** The owning screen went away: every response still in flight is now stale. */
    public void close() {
        open = false;
        ticket++;
        loading = false;
    }

    public void reloadCourses() {
        course = null;
        unit = null;
        assignment = null;
        questionIndex = -1;
        loadCourses(true);
    }

    private void loadCourses(boolean notify) {
        request(notify, () -> api.health().thenCompose(health -> {
            if (!health.ok()) throw BackendException.malformed("the backend reports status " + health.status());
            return api.courses();
        }), loaded -> courses = loaded);
    }

    public void openCourse(String courseId) {
        if (loading || course != null || courses().stream().noneMatch(summary -> summary.id().equals(courseId))) return;
        CourseSummary summary = courses.stream().filter(item -> item.id().equals(courseId)).findFirst().orElseThrow();
        request(true, () -> api.course(courseId), loaded -> {
            require(loaded.id().equals(courseId), "course ID " + loaded.id() + " does not match " + courseId);
            require(loaded.units().stream().map(UnitSummary::id).toList().equals(summary.unitIds()),
                    "the course's units differ from the course list");
            course = loaded;
        });
    }

    public void openUnit(String unitId) {
        if (loading || course == null || unit != null) return;
        Optional<UnitSummary> summary = course.units().stream().filter(item -> item.id().equals(unitId)).findFirst();
        if (summary.isEmpty()) return;
        String courseId = course.id();
        request(true, () -> api.unit(unitId), loaded -> {
            require(loaded.id().equals(unitId), "unit ID " + loaded.id() + " does not match " + unitId);
            require(loaded.courseId().equals(courseId), "unit " + unitId + " belongs to another course");
            require(loaded.assignments().stream().map(AssignmentSummary::id).toList().equals(summary.get().assignmentIds()),
                    "the unit's assignments differ from the course's unit list");
            unit = loaded;
        });
    }

    /** Coming Soon assignments have nothing to preview and are not requested. */
    public void openAssignment(String assignmentId) {
        if (loading || unit == null || assignment != null) return;
        Optional<AssignmentSummary> summary = unit.assignments().stream()
                .filter(item -> item.id().equals(assignmentId) && item.available()).findFirst();
        if (summary.isEmpty()) return;
        String unitId = unit.id();
        request(true, () -> api.assignment(assignmentId).thenCompose(detail -> {
            List<CompletableFuture<ConceptDetail>> concepts = detail.concepts().stream()
                    .map(concept -> api.concept(concept.id())).toList();
            CompletableFuture<AssignmentQuestions> questions = api.assignmentQuestions(assignmentId);
            List<CompletableFuture<?>> all = new ArrayList<>(concepts);
            all.add(questions);
            return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(done -> AssignmentPreview.assemble(
                    assignmentId, detail, concepts.stream().map(CompletableFuture::join).toList(), questions.join()));
        }), loaded -> {
            require(loaded.assignment().unitId().equals(unitId), "assignment " + assignmentId + " belongs to another unit");
            require(loaded.concepts().stream().map(ConceptDetail::id).toList().equals(summary.get().conceptIds()),
                    "the assignment's concepts differ from the unit's assignment list");
            assignment = loaded;
            questionIndex = -1;
        });
    }

    /** Shows one question of the open assignment; an out-of-range index is ignored. */
    public void showQuestion(int index) {
        if (loading || assignment == null || index < 0 || index >= assignment.questions().size()) return;
        questionIndex = index;
    }

    public boolean canRetry() { return error != null && retry != null; }

    public void retry() {
        if (canRetry()) retry.run();
    }

    public boolean canGoBack() {
        return (loading || error != null) ? courses != null : view() != View.COURSES;
    }

    /** Leaves a pending load or error first (abandoning its response), then one level per call. */
    public boolean back() {
        if (!canGoBack()) return false;
        if (loading || error != null) {
            ticket++;
            loading = false;
            error = null;
            retry = null;
            return true;
        }
        if (questionIndex >= 0) questionIndex = -1;
        else if (assignment != null) assignment = null;
        else if (unit != null) unit = null;
        else course = null;
        return true;
    }

    private <T> void request(boolean notify, Supplier<CompletableFuture<T>> call, Consumer<T> apply) {
        long mine = ++ticket;
        loading = true;
        error = null;
        retry = () -> request(true, call, apply);
        if (connection != Connection.CONNECTED) connection = Connection.CONNECTING;
        CompletableFuture<T> future;
        try {
            future = call.get();
        } catch (RuntimeException exception) {
            future = CompletableFuture.failedFuture(exception);
        }
        future.whenCompleteAsync((value, failure) -> {
            if (!open || mine != ticket) return;
            loading = false;
            try {
                if (failure != null) throw BackendException.from(failure);
                apply.accept(value);
                connection = Connection.CONNECTED;
                retry = null;
            } catch (BackendException exception) {
                error = exception.getMessage();
                connection = exception.backendReachable() ? Connection.CONNECTED : Connection.UNAVAILABLE;
            }
            listener.run();
        }, ownerThread);
        if (notify) listener.run();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw BackendException.malformed("inconsistent content (" + message + ")");
    }
}
