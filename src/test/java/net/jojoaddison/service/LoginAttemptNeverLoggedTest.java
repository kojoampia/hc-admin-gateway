package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.jojoaddison.JavaSourceText;
import org.junit.jupiter.api.Test;

/**
 * {@link net.jojoaddison.domain.LoginAttempt}'s <b>second safeguard</b>: the entered login never
 * reaches a log, at any level.
 *
 * <h2>Why this exists here rather than being covered by hc-admin-service's sweep</h2>
 *
 * <p>That repository has {@code LogPseudonymTest}, which sweeps every source touching the sibling-link
 * correlation key and refuses any log statement that passes it unwrapped. It reads
 * {@code src/main/java} — <b>its own</b> — so it cannot reach this repository, and no test can sweep
 * across two independently-cloned git repositories. Item 43's rule is estate-wide; its enforcement is
 * necessarily per-repo, and this is this repo's copy for this repo's value.
 *
 * <p>Its subject is different too, and that matters for how strict this can be. Item 43's key is an
 * address a service handles constantly and legitimately, so its guard permits the value wrapped in
 * {@code LogPseudonym.subject()}. Here there is <b>no permitted form at all</b>: this gateway has no
 * pseudonymiser, deliberately (see {@link LoginAttemptRecorder}), and an entered login has no reason
 * to appear in a log line in any shape.
 *
 * <h2>How the population is chosen, and why it fails closed</h2>
 *
 * <p>{@link #IN_SCOPE} is a discriminator over source text, not a list of file names — a new class
 * handling this data joins the sweep by mentioning the types, without anybody remembering to add it.
 * That is hc-admin-service's own rule, learned from a {@code PaginationIT} whose hand-written list of
 * 23 paths let eight unpaginated endpoints through.
 *
 * <p><b>It is deliberately not "every source that touches a login".</b> That discriminator —
 * {@code getLogin\(|setLogin\(} — matches {@link net.jojoaddison.domain.User} and the nine classes
 * around it, several of which have logged logins since the generator's first commit. Widening to
 * them is a different decision about a different value on a surface item 75 does not touch, and
 * making this test the place that decision gets taken by accident would either redden a clean build
 * or, far more likely, get the rule weakened until it passed.
 *
 * <p>The per-argument rule is an <b>allow-list</b> ({@link #NON_IDENTIFYING}) rather than a deny-list,
 * and the direction is the point. A deny-list has to anticipate how the value might be spelled; an
 * allow-list fails on anything it has not been told about, so the way to add an argument is to argue
 * that it carries no identity — which is the thought this guard exists to force.
 *
 * <h2>⚠ What this grades, and what is outside its reach</h2>
 *
 * <p><b>SLF4J log statements only.</b> It finds {@code LOG.trace|debug|info|warn|error(…)} and grades
 * the arguments. It says nothing about any other route from a value to a log file, and three are
 * worth naming because none of them would redden it: {@code MDC.put("login", …)}, which ends up on
 * every line in the request's scope; {@code System.out}/{@code System.err}, which the container
 * collects; and any exception thrown with the value in its message, which some frame above will log.
 * Nothing in this feature does any of the three — checked, not assumed — and this paragraph exists
 * because an unstated boundary is how the next person walks past it.
 *
 * <p>The population is fail-closed against a file that does not exist yet
 * ({@link #IN_SCOPE}) and against a logger this reader cannot see
 * ({@link #everyLoggerInScopeIsNamedSomethingTheReaderCanSee()}). It is <b>not</b> fail-closed
 * against a new sink, and cannot be: a rule about SLF4J cannot know what else somebody imports.
 *
 * <p><b>And "a logger this reader cannot see" is exactly a {@code Logger}-typed field
 * declaration</b> — {@link #LOGGER_FIELD} matches {@code Logger <name> =} and nothing else. A local
 * {@code var log = LoggerFactory.getLogger(...)}, or an inline
 * {@code LoggerFactory.getLogger(X.class).warn(...)}, is invisible to that check <em>and</em> to
 * {@link #LOG_CALL_HEAD}, so it would be swept in the sense of being opened and ungraded in the
 * sense that matters. Neither shape exists in this repository and neither is idiomatic here, so this
 * is a stated limit rather than a known gap — but the sentence above claims fail-closed and is
 * strictly true only of fields, which is the kind of gap between a claim and its code that this file
 * exists to catch one domain along.
 */
class LoginAttemptNeverLoggedTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /**
     * Every source that handles the authentication record, whatever package it is in.
     *
     * <p>The three type names this feature introduced. A file mentioning any of them is sweeping
     * distance of the entered login, including through a {@code toString} it did not write.
     */
    private static final Pattern IN_SCOPE = Pattern.compile("LoginAttempt|LoginOutcome|AuthActivity");

    /**
     * The subset of the above that <b>writes</b> a {@code LoginAttempt}, and therefore the only place
     * a Mongo exception carrying an entered login can be caught.
     *
     * <p>A compiler-visible discriminator rather than a file name, for the reason
     * {@code LogPseudonymTest}'s {@code CROSS_STACK_CLIENT} javadoc gives: a rule stated on the
     * dependency lands on the class that has it, where a rule stated as a list lands on the classes
     * somebody remembered.
     *
     * <p><b>All three write verbs, not just the one today's code happens to use.</b> It was
     * {@code \.save\s*\(} alone, which is fail-open in a specific and cheap way: a new writer calling
     * {@code insert()} or {@code upsert()} in a class with no {@code .save(} in it escapes the ban
     * entirely — while {@link #NON_IDENTIFYING} <em>allows</em> a bare {@code e}, which is exactly the
     * permission this narrower rule exists to take back. Widening costs nothing and closes it.
     *
     * <p>Renaming the write away from all three still fails closed, and that is deliberate rather than
     * lucky: {@link #theClassThatWritesTheRowDoesNotLogTheExceptionFromAFailedWrite()} asserts the
     * matched set is non-empty before it grades anything, so a discriminator that has lost its subject
     * reddens instead of sweeping nothing.
     */
    private static final Pattern WRITES_AN_ATTEMPT = Pattern.compile("\\.(save|insert|upsert)\\s*\\(");

    /**
     * A {@code Logger} field declaration, so the population can be checked for loggers this reader
     * cannot see — see {@link #everyLoggerInScopeIsNamedSomethingTheReaderCanSee()}.
     */
    private static final Pattern LOGGER_FIELD = Pattern.compile("\\bLogger\\s+(\\w+)\\s*=");

    /**
     * A log call, under either of the two logger names this repository uses.
     *
     * <p>{@code LOG} is the convention in every class this feature added, and hc-admin-service's
     * equivalent guard matches only that. <b>It would be a fail-open hole here</b>:
     * {@code AuthenticateController} — the one class that has the entered login on a request thread —
     * declares {@code private final Logger log}, lower case, from the generator. Matching one spelling
     * would have swept that file and found nothing in it, silently.
     * {@link #theSweepReadsTheControllerThatHoldsTheEnteredLogin()} is the case that pins the other
     * spelling rather than trusting this comment.
     */
    private static final Pattern LOG_CALL_HEAD = Pattern.compile("\\b(?:LOG|log)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");

    /**
     * An argument form known to carry no identity.
     *
     * <p>Deliberately short. Every entry is a value this feature's own log lines need and that cannot
     * be or contain a login: the outcome enum in its three spellings, the two configuration integers,
     * the index name, a computed duration, and an exception's <em>class</em> — never the exception,
     * which is the next rule down.
     */
    private static final Pattern NON_IDENTIFYING = Pattern.compile(
        "^(" +
            "outcome" +
            "|(attempt|saved)\\.getOutcome\\(\\)" +
            "|windowDays" +
            "|retentionDays" +
            "|RETENTION_INDEX" +
            "|Duration\\.ofDays\\(retentionDays\\)\\.toSeconds\\(\\)" +
            "|\\w+\\.getClass\\(\\)\\.getName\\(\\)" +
            // A raw Throwable is allowed HERE and refused by the narrower rule below, and that
            // distinction is worth stating rather than blurring. An exception is unsafe when it came
            // from something holding the document: a failed write embeds the document in its message,
            // which is item 50's finding arriving through a different door. An exception from
            // createIndex holds an index specification and no document at all, and stripping its stack
            // trace would make a retention failure materially harder to diagnose for no gain. So the
            // ban sits on the class that saves a LoginAttempt, which is where the risk actually is.
            "|e|ex|error|refused|failure|throwable|t" +
            ")$"
    );

    /**
     * A raw {@link Throwable} argument, in the two shapes this codebase writes it.
     *
     * <p>Matched separately from {@link #NON_IDENTIFYING} so the failure can say <em>why</em>: an
     * exception is not merely an unrecognised argument, it is the specific mistake this rule was
     * written for.
     */
    private static final Pattern RAW_THROWABLE = Pattern.compile("^(e|ex|error|refused|failure|throwable|t)$");

    /**
     * An argument made <b>entirely</b> of string literals, which an author wrote and no value can
     * reach — the only thing that may be dropped before grading.
     *
     * <h2>⚠ This replaced {@code startsWith("\"")}, which failed open on the commonest log statement
     * in Java</h2>
     *
     * <p>The old filter dropped any argument beginning with a quote, under a comment claiming those
     * were "the format string, and any other literal". <b>That premise is false for a concatenation
     * that begins with a literal.</b> After whitespace collapse,
     * {@code LOG.warn("could not record " + enteredLogin)} is {@code "couldnotrecord"+enteredLogin} —
     * it starts with a quote, so it was dropped and never graded, and the entered login went to Loki
     * with this guard green. Message-plus-value concatenation is the single most idiomatic way to
     * write a log line, so the hole was not an edge case; it was the main road.
     *
     * <p>It was exactly and only <em>literal-first</em>. The mirror form
     * {@code LOG.warn(enteredLogin + " tried")} was caught, as were {@code String.format} and a bare
     * call — which is why nothing in the shipped code tripped it and why reading the filter, rather
     * than running the suite, is what found it. <b>Both rules were bypassed at once</b>, because
     * {@link #NON_IDENTIFYING} and {@link #RAW_THROWABLE} share {@link #argumentsOf}.
     *
     * <p>This is the third instance of the items 57/59 class in this estate, and the shape is worth
     * carrying: {@link #argumentsOf}'s whitespace-collapse comment <em>cites item 57 by name</em>
     * while the line below it introduced a new hole. Citing the lesson is not applying it.
     *
     * <p>The pattern requires the whole argument to be one or more string literals, optionally joined
     * by {@code +}. {@code "a" + "b"} drops; {@code "a" + login} does not. The literal body is
     * {@code (?:\\.|[^"\\])*} rather than {@code [^"]*} so that an escaped quote inside a message —
     * {@code "he said \"no\""} — does not end it early and leave a trailing fragment ungraded.
     */
    private static final Pattern LITERAL_ONLY = Pattern.compile("^(\"(?:\\\\.|[^\"\\\\])*\"\\+?)+$");

    // --- the sweep -----------------------------------------------------------------------------

    /**
     * The premise. A discriminator that matched nothing would leave every assertion below vacuously
     * green — {@code LogPseudonymTest}'s own {@code IN_SCOPE} javadoc names that as this shape of
     * test's characteristic failure, and it is the one nothing else could catch.
     */
    @Test
    void theSweepIsActuallySweepingSomething() {
        List<Path> sources = inScopeSources();

        assertThat(sources)
            .as("no source mentions LoginAttempt, LoginOutcome or AuthActivity — the feature was renamed and this guard is reading nothing")
            .isNotEmpty();
        assertThat(sources.stream().map(path -> path.getFileName().toString()))
            .as("the four classes that hold or project the entered login must all be in the sweep")
            .contains("LoginAttempt.java", "LoginAttemptRecorder.java", "AuthenticateController.java", "AuthActivityService.java");
    }

    /**
     * The other half of the premise, and the one that caught a real hole in this file.
     *
     * <p>{@code AuthenticateController} is the only class in the sweep that has the entered login on a
     * request thread, and it declares its logger {@code log} rather than {@code LOG}. The first
     * version of {@link #LOG_CALL_HEAD} matched only the upper-case spelling, so that file was in
     * scope, was read, produced <b>zero</b> statements, and passed — a guard sweeping the one file it
     * most needed to sweep and finding nothing there by construction.
     */
    @Test
    void theSweepReadsTheControllerThatHoldsTheEnteredLogin() {
        Path controller = inScopeSources()
            .stream()
            .filter(source -> source.getFileName().toString().equals("AuthenticateController.java"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AuthenticateController is not in the sweep at all"));

        assertThat(logStatements(read(controller)))
            .as("no log statement found in AuthenticateController — LOG_CALL_HEAD does not match the logger it declares")
            .isNotEmpty();
    }

    /**
     * <b>The same hole, closed as a property of the population instead of pinned to one file.</b>
     *
     * <p>{@link #theSweepReadsTheControllerThatHoldsTheEnteredLogin()} above catches the logger name
     * this repository happens to have got wrong once. It does nothing about the next one: a new
     * in-scope class declaring {@code private static final Logger logger} — or {@code LOGGER}, or
     * {@code auditLog} — is read, contributes <b>zero</b> statements to both rules, and passes. The
     * file is swept in the sense that it is opened, and unswept in the sense that matters, with
     * nothing red anywhere.
     *
     * <p>So the check is on the <em>population</em> rather than on a file, which is what makes it
     * cover classes that do not exist yet — this file's own stated rule about discovered sets, applied
     * to the reader instead of to the sources. It is deliberately not "widen
     * {@link #LOG_CALL_HEAD} to any identifier containing 'log'": that quietly starts grading
     * {@code catalog.get(...)} and, more to the point, still says nothing about a logger named
     * something else entirely. Requiring the name to be one this reader can see is the fail-closed
     * direction; widening the reader is guessing.
     *
     * <p>The requirement is derived from {@link #LOG_CALL_HEAD} rather than restated as
     * {@code LOG|log}, so the two cannot drift: a probe call is built from each declared name and the
     * real pattern is asked whether it would find it.
     */
    @Test
    void everyLoggerInScopeIsNamedSomethingTheReaderCanSee() {
        List<String> unreadable = new ArrayList<>();
        int declared = 0;

        for (Path source : inScopeSources()) {
            Matcher field = LOGGER_FIELD.matcher(JavaSourceText.withoutComments(read(source)));
            while (field.find()) {
                declared++;
                String name = field.group(1);
                if (!LOG_CALL_HEAD.matcher(name + ".warn(").find()) {
                    unreadable.add(source.getFileName() + " · Logger " + name);
                }
            }
        }

        assertThat(declared).as("no Logger field found in any in-scope source — this premise is reading nothing").isPositive();
        assertThat(unreadable)
            .as(
                "an in-scope class declares a Logger under a name LOG_CALL_HEAD does not match, so every log " +
                    "statement in that file is invisible to BOTH rules here and the file passes while being " +
                    "ungraded. Rename the field to LOG (or log), or teach LOG_CALL_HEAD the new name — but do not " +
                    "leave it, because the failure mode is a silent zero rather than an error."
            )
            .isEmpty();
    }

    /**
     * <b>The rule.</b> No log statement in scope may pass an argument that has not been argued to
     * carry no identity.
     *
     * <p>Add {@code LOG.warn("…{}", attempt.getLogin())} anywhere in scope and this is the case that
     * goes red, naming the file and the argument.
     */
    @Test
    void noLogStatementInScopePassesAnythingIdentifying() {
        List<String> offences = new ArrayList<>();

        for (Path source : inScopeSources()) {
            for (LogStatement statement : logStatements(read(source))) {
                for (String argument : argumentsOf(statement)) {
                    if (!NON_IDENTIFYING.matcher(argument).matches()) {
                        offences.add(source.getFileName() + " · LOG." + statement.level() + "(…, " + argument + ", …)");
                    }
                }
            }
        }

        assertThat(offences)
            .as(
                "a log statement handling the authentication record passes an argument this guard does not " +
                    "recognise as non-identifying. LoginAttempt stores the login AS ENTERED, which is a deliberate " +
                    "exception to item 43 carried by four safeguards, and 'never into a log, at any level' is the " +
                    "second of them — the exception does not extend to Loki, which is unauthenticated and " +
                    "estate-wide. If the argument genuinely carries no identity, add its form to NON_IDENTIFYING " +
                    "and say why."
            )
            .isEmpty();
    }

    /**
     * <b>The rule that is not obvious.</b> The class that writes the row may not log the exception
     * from a failed write — because a Mongo write failure's message <em>embeds the document it could
     * not write</em>, so the login reaches Loki with no log statement mentioning it.
     *
     * <p>That is item 50's finding — "an exception message is not safe to log" — arriving in a second
     * repository through a different door: there it was a {@code RestClient} error handler appending
     * the response body, here it is a driver appending the document. Same shape, and it is invisible
     * to a reader of the log statement in both.
     */
    @Test
    void theClassThatWritesTheRowDoesNotLogTheExceptionFromAFailedWrite() {
        List<Path> writers = mainSourcesMatching(IN_SCOPE)
            .stream()
            .filter(source -> WRITES_AN_ATTEMPT.matcher(read(source)).find())
            .toList();

        assertThat(writers).as("nothing in scope saves a LoginAttempt any more — this rule has lost its subject").isNotEmpty();

        List<String> offences = new ArrayList<>();
        for (Path source : writers) {
            for (LogStatement statement : logStatements(read(source))) {
                for (String argument : argumentsOf(statement)) {
                    if (RAW_THROWABLE.matcher(argument).matches()) {
                        offences.add(source.getFileName() + " · LOG." + statement.level() + "(…, " + argument + ")");
                    }
                }
            }
        }

        assertThat(offences)
            .as(
                "the class that writes a LoginAttempt passes an exception to a log call. A failed Mongo write " +
                    "throws with the document in its message, so this publishes the entered login to Loki without " +
                    "any log statement naming it. Log the exception's class instead — error.getClass().getName()."
            )
            .isEmpty();
    }

    // --- the reader's own two properties, on constructed sources --------------------------------

    /**
     * <b>The rule really does fire.</b> A guard that has never been seen to fail is item 68's defect
     * one domain along, and both cases above assert an <em>empty</em> list — which is what a broken
     * reader also produces.
     *
     * <p>Run through {@link #logStatements} and {@link #argumentsOf}, the rule's own reading, rather
     * than through a second copy written beside it.
     */
    @Test
    void theRuleRejectsALogStatementThatNamesTheLogin() {
        String offending = "class X { void f() { LOG.warn(\"tried {}\", attempt.getLogin()); } }";

        List<String> arguments = argumentsOf(logStatements(offending).getFirst());

        assertThat(arguments).containsExactly("attempt.getLogin()");
        assertThat(NON_IDENTIFYING.matcher(arguments.getFirst()).matches()).as("the guard must not accept this").isFalse();
    }

    /**
     * <b>And it fires on the commonest form of all: message-plus-value concatenation.</b>
     *
     * <p>This is the case that goes red without {@link #LITERAL_ONLY}. Under the old
     * {@code startsWith("\"")} filter the argument was dropped before grading — it begins with a
     * quote — so the sibling case above stayed green while
     * {@code LOG.warn("could not record " + enteredLogin)} shipped the entered login to Loki.
     * Measured, not reasoned about: the reviewer planted exactly this in the recorder and the suite
     * stayed 7/7.
     *
     * <p>Graded through {@link #argumentsOf}, the rule's own reading, rather than through a second
     * copy written beside it — which is this file's convention and is what would otherwise let the
     * case pass while the sweep still dropped the argument.
     */
    @Test
    void theRuleRejectsALoginConcatenatedIntoTheMessage() {
        String offending = "class X { void f() { LOG.warn(\"could not record \" + enteredLogin); } }";

        List<String> arguments = argumentsOf(logStatements(offending).getFirst());

        assertThat(arguments)
            .as("a literal-first concatenation must survive the literal filter and reach the rule")
            .containsExactly("\"couldnotrecord\"+enteredLogin");
        assertThat(NON_IDENTIFYING.matcher(arguments.getFirst()).matches()).as("the guard must not accept this").isFalse();
    }

    /**
     * The other half of {@link #LITERAL_ONLY}: a format string, however it is spelled, is still
     * dropped.
     *
     * <p>Without this the obvious over-correction — grading every argument — passes the case above
     * and reddens on every real message in the repository, which is how a guard gets weakened until
     * it passes. A literal joined to another literal, and a literal containing an escaped quote, are
     * both author-written text and neither can carry a value.
     */
    @Test
    void theRuleStillDropsSomethingMadeOnlyOfLiterals() {
        assertThat(LITERAL_ONLY.matcher("\"couldnotrecord\"").matches()).as("a plain format string").isTrue();
        assertThat(LITERAL_ONLY.matcher("\"could\"+\"notrecord\"").matches()).as("literal joined to literal").isTrue();
        assertThat(LITERAL_ONLY.matcher("\"a\\\"b\"").matches()).as("a literal containing an escaped quote").isTrue();

        assertThat(LITERAL_ONLY.matcher("\"couldnotrecord\"+enteredLogin").matches()).as("THE LEAK — must be graded").isFalse();
        assertThat(LITERAL_ONLY.matcher("\"a\\\"b\"+login").matches()).as("escaped quote then concat").isFalse();
        assertThat(LITERAL_ONLY.matcher("enteredLogin+\"tried\"").matches()).as("value-first").isFalse();
        assertThat(LITERAL_ONLY.matcher("attempt.getLogin()").matches()).as("a bare call").isFalse();
    }

    /**
     * <b>And it does not fire on a comment</b> — which is not a nicety, it is what
     * {@code JavaSourceText.withoutComments} was added for after both rules failed on this feature's own javadoc
     * quoting the mistakes it exists to prevent.
     *
     * <p>The second half is the more important one: the head inside the comment must not consume the
     * real statement after it, which is a fail-<em>open</em> hole rather than a noisy one.
     */
    @Test
    void theRuleGradesCodeRatherThanTheProseAboutIt() {
        String documented = """
        class X {
          /** Never write LOG.warn("tried {}", attempt.getLogin()) — that is the whole point. */
          void f() { LOG.warn("dropped {}", outcome); }
        }
        """;

        List<LogStatement> statements = logStatements(documented);

        assertThat(statements).as("the commented head must not be read as a statement").hasSize(1);
        assertThat(argumentsOf(statements.getFirst()))
            .as("and it must not have swallowed the real one after it")
            .containsExactly("outcome");
    }

    /** A path literal must not be mistaken for a comment opener — {@code "/api/**"} contains one. */
    @Test
    void aPathLiteralIsNotMistakenForACommentOpener() {
        String source = "class X { String p = \"/api/**\"; void f() { LOG.debug(\"at {}\", outcome); } }";

        assertThat(argumentsOf(logStatements(source).getFirst())).containsExactly("outcome");
    }

    // --- the reader ----------------------------------------------------------------------------

    private record LogStatement(String level, String arguments) {}

    /**
     * The arguments of one log call, split at top-level commas, with the author-written text dropped.
     *
     * <p>What is dropped is an argument made <b>entirely</b> of string literals — see
     * {@link #LITERAL_ONLY}, which carries the reason at length. It used to be "anything starting with
     * a quote", and that dropped {@code "could not record " + enteredLogin} unread. Including the
     * message text instead of dropping it is not an option: every real log line would fail
     * {@link #NON_IDENTIFYING}, and a guard that reddens on correct code gets weakened until it stops.
     *
     * <p>The split is depth-aware and steps over string literals, character literals and text blocks,
     * so {@code LOG.warn("a, b {}", e.getClass().getName())} is two arguments rather than three.
     */
    private static List<String> argumentsOf(LogStatement statement) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        String text = statement.arguments();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Before the single-quote branch, for the reason JavaSourceText gives: both guards are
            // true at the start of a text block, and reading one as an empty string leaves the split
            // running inside it.
            if (text.startsWith("\"\"\"", i)) {
                int end = JavaSourceText.endOfTextBlock(text, i);
                if (end < 0) {
                    break;
                }
                current.append(text, i, end);
                i = end - 1;
            } else if (c == '"' || c == '\'') {
                int end = JavaSourceText.endOfLiteral(text, i, c);
                if (end < 0) {
                    break;
                }
                current.append(text, i, end);
                i = end - 1;
            } else if (c == '(' || c == '[') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                arguments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        arguments.add(current.toString());

        return arguments
            .stream()
            // Prettier wraps a long call across lines and Spotless indents it, so an argument arrives
            // with newlines inside it. Collapsing whitespace is what lets NON_IDENTIFYING stay a plain
            // pattern — item 57's finding, where a rule was defeated by a formatter's line break.
            .map(JavaSourceText::collapseWhitespace)
            .filter(argument -> !argument.isEmpty())
            // Author-written text only. NOT startsWith("\"") — see LITERAL_ONLY for what that cost.
            .filter(argument -> !LITERAL_ONLY.matcher(argument).matches())
            .toList();
    }

    /**
     * Every {@code LOG.x(...)} call in a source file, with the whole of its argument list.
     *
     * <p>A head found by the pattern, then a forward scan for the {@code )} that closes it, counting
     * brackets and stepping over the four things a bracket can hide inside. This is
     * {@code LogPseudonymTest}'s reader, ported deliberately rather than reinvented: its own
     * non-greedy version truncated at a {@code );} inside a format string and dropped the arguments —
     * the part where the identifiers are — and that hole was reached by 1 statement in 394, because
     * writing {@code ({})} in a message is idiomatic.
     */
    private static List<LogStatement> logStatements(String wholeFile) {
        String source = JavaSourceText.withoutComments(wholeFile);
        List<LogStatement> statements = new ArrayList<>();
        Matcher head = LOG_CALL_HEAD.matcher(source);
        while (head.find()) {
            int arguments = head.end();
            int closing = endOfArgumentList(source, arguments);
            if (closing >= 0) {
                statements.add(new LogStatement(head.group(1), source.substring(arguments, closing)));
            }
        }
        return statements;
    }

    /*
     * withoutComments, endOfLiteral and endOfTextBlock used to live here and are now
     * net.jojoaddison.JavaSourceText, shared with SecurityConfigurationOrderTest.
     *
     * They moved because that guard needed the same stripping for the opposite reason. This sweep
     * scanned raw text and failed CLOSED — this feature's own javadoc quotes the mistakes it exists
     * to prevent, so both rules failed on the documentation arguing for the rule, and underneath that
     * a head inside a comment has no argument list, so the forward scan ran on and graded the next
     * real statement. `ruleFor` over there did no stripping and failed OPEN: a comment quoting a
     * matcher above a widened real rule would be graded AS the rule. One lexer, two guards, and the
     * reasoning is on JavaSourceText rather than duplicated here.
     */

    /**
     * The index of the {@code )} closing an argument list whose contents begin at {@code from}, or
     * {@code -1} if the file ends first.
     *
     * <p>A comment is consumed whole from its opening {@code /}, so an apostrophe inside one is never
     * dispatched on as a character literal. {@code """} is tested before {@code "}: both guards are
     * true at the start of a text block, and reading one as an empty string closes the scan on the
     * first bracket <em>inside</em> the block.
     */
    private static int endOfArgumentList(String source, int from) {
        int depth = 1;
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
                if (i < 0) {
                    return -1;
                }
            } else if (c == '/' && source.startsWith("/*", i)) {
                i = source.indexOf("*/", i + 2);
                if (i < 0) {
                    return -1;
                }
                i += 1;
            } else if (source.startsWith("\"\"\"", i)) {
                i = JavaSourceText.endOfTextBlock(source, i);
                if (i < 0) {
                    return -1;
                }
                continue;
            } else if (c == '"' || c == '\'') {
                i = JavaSourceText.endOfLiteral(source, i, c);
                if (i < 0) {
                    return -1;
                }
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }



    private static List<Path> inScopeSources() {
        return mainSourcesMatching(IN_SCOPE);
    }

    private static List<Path> mainSourcesMatching(Pattern discriminator) {
        try (Stream<Path> tree = Files.walk(MAIN_SOURCES)) {
            return tree
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> discriminator.matcher(read(path)).find())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk src/main/java — is this running from the module root?", e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
