package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One attempt to sign in, and its outcome. The whole of the authentication record this stack keeps.
 *
 * <h2>Why it exists at all</h2>
 *
 * <p>Until this collection, <b>a failed login left no trace anywhere on this stack</b> — backlog
 * item 75's second finding, measured rather than assumed. {@code AuthenticateController} referenced
 * {@link net.jojoaddison.management.SecurityMetersService} nowhere, and that class counts JWT
 * <em>token-validation</em> failures only ({@code invalid-signature}, {@code expired},
 * {@code unsupported}, {@code malformed}); a wrong password reaches none of the four. The gateway
 * persisted exactly {@link User} and {@link Authority}. So the question "is somebody trying
 * passwords against this console" had no answer, in any store, at any level.
 *
 * <p><b>A Micrometer counter was the obvious answer and it was rejected, on a measurement.</b>
 * {@code application-prod.yml} sets {@code management.prometheus.metrics.export.enabled: false}; no
 * quality JVM in this estate carries {@code -javaagent}; {@code jacserver}'s Alloy config carries no
 * application scrape targets, deliberately, because "application telemetry arrives as OTLP at the
 * OpenTelemetry Collector"; and that collector has been {@code exited} since 2026-09-05. A counter
 * here would be correct, invisible and untestable — the estate-wide monitoring <em>claim</em> with
 * nothing behind it, one product along. A collection is readable by the console today, which is what
 * was asked for.
 *
 * <h2>⚠ It carries the login as it was entered, and that is a deliberate exception to item 43</h2>
 *
 * <p>Item 43 took the correlation key out of every log statement in hc-admin-service, because those
 * lines reach Loki unauthenticated and estate-wide with a fourteen-day index, shared with five other
 * products. {@code DirectoryLinkResource}'s javadoc then argued the converse for a screen: an
 * endpoint may serve an identifier a log may not, because the reader is named, the read is
 * authorised, and the value is not copied into a store with a lifetime of its own. <b>This document
 * is the same argument for a value that is harder</b>, so it is made here in full rather than left
 * to be inferred from next door.
 *
 * <p>What is stored is <b>whatever string was typed into the login box</b>. On a failure that is by
 * definition usually not the account holder — it is an attacker's guess, a typo, a colleague's
 * login, or a password pasted into the wrong field. The alternatives were weighed:
 *
 * <ul>
 *   <li><b>Outcome only, no subject.</b> Cheapest and safe, and it answers nothing an operator can
 *       act on. "Forty failures today" does not distinguish one account being hammered from forty
 *       people mistyping once, and those need opposite responses. The whole operational value of a
 *       failed-login record is <em>which</em> account.</li>
 *   <li><b>{@code LogPseudonym.subject()}, a digest.</b> That primitive exists in hc-admin-service
 *       and its own rule is that it is <b>never for display</b> — a digest on a screen is the
 *       unreadable-identifier-as-a-name defect item 45 removed from the patient list, one field
 *       along. An operator cannot lock {@code subj-5c9b0dc282ec}. It is also weaker than it looks
 *       here: the login space is small and enumerable, so a digest of a login is reversible by
 *       anyone holding the user list, which is everyone who can reach this screen.</li>
 * </ul>
 *
 * <p><b>Four safeguards carry that exception and none of them is optional.</b> Each is enforced
 * somewhere a test can see it, and each is named beside the thing that enforces it:
 *
 * <ol>
 *   <li><b>The read surface is {@code ROLE_ADMIN} alone</b> — narrower than the console dashboard,
 *       which is admin-or-operator. {@code SecurityConfiguration} states the rule explicitly above
 *       the blanket {@code /api/**} matcher; {@code SecurityConfigurationOrderTest} pins the
 *       position and {@code AuthActivityResourceIT} pins the decision for each of the three roles.</li>
 *   <li><b>The value never reaches a log, at any level.</b> Item 43's rule is untouched. Nothing in
 *       this repository logs it, including on the failure path — see
 *       {@link net.jojoaddison.service.LoginAttemptRecorder}, which logs the exception's
 *       <em>class</em> and never the exception, because a Mongo write failure's message embeds the
 *       document it failed to write. {@code LoginAttemptNeverLoggedTest} sweeps every source that
 *       mentions this type and fails on any log argument it does not recognise as non-identifying.</li>
 *   <li><b>{@link #login} is capped at {@link #MAX_LOGIN_LENGTH} characters</b>, applied in the
 *       recorder before the document is built. This field stores an arbitrary attacker-supplied
 *       string; uncapped it is a storage-exhaustion vector reachable by anyone who can open a
 *       socket. See {@link net.jojoaddison.service.LoginAttemptRecorder#record} for why the cap is
 *       above what {@code LoginVM} admits rather than equal to it.</li>
 *   <li><b>Retention is bounded by the store itself</b>, not by a sweep somebody has to run: a
 *       MongoDB TTL index on {@link #attemptedAt} deletes a row once it is older than
 *       {@code application.auth-activity.retention-days}. {@code LoginAttemptIndexes} creates it and
 *       says what happens when the value changes.</li>
 * </ol>
 *
 * <p>So: <b>identifying, to a reader entitled to it, on purpose, for a bounded time. Never into a
 * log, at any level.</b> If that stops being true, it is this document's authorities or its
 * retention that change — do not reach for a digest to make the screen safer, because it makes the
 * screen useless without making the collection any less identifying.
 *
 * <h2>What it deliberately does not carry</h2>
 *
 * <p><b>No IP address, no user agent.</b> Both are the obvious next fields on an authentication
 * record and both are a second identifier with their own retention argument, on a document whose
 * first one is already an exception. Neither is needed by the figures item 75 asked for. Adding one
 * means re-running the argument above for it, not appending a column.
 */
@Document(collection = "login_attempt")
public class LoginAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The most characters of an entered login this document will hold.
     *
     * <p>Deliberately <em>above</em> what a real request can carry rather than equal to it —
     * {@code LoginVM.username} is {@code @Size(min = 1, max = 50)}, so validation refuses a longer
     * one with a {@code 400} before {@code AuthenticateController} is entered. Truncating at 50 would
     * therefore never fire on anything reachable today, and would silently merge two long logins into
     * one row-key on the day that constraint is relaxed. 100 leaves the real values untouched and
     * still bounds the document.
     */
    public static final int MAX_LOGIN_LENGTH = 100;

    @Id
    private String id;

    /**
     * The login exactly as it was typed, trimmed and truncated to {@link #MAX_LOGIN_LENGTH}.
     *
     * <p><b>Not lower-cased, unlike {@link User#setLogin}.</b> {@code User} normalises because a
     * login is a key there and two casings must be one account. Here the value is evidence, and
     * "somebody is trying {@code Admin}, {@code ADMIN} and {@code admin} in turn" is a fact about the
     * attempt that normalising would erase. The consequence is stated rather than hidden: the
     * top-failed-logins figure counts casings separately, which is the honest reading of what was
     * sent.
     *
     * <p>Never null. An attempt with no login cannot reach the recorder — {@code LoginVM.username} is
     * {@code @NotNull} — and the recorder refuses one anyway rather than writing a row that counts
     * towards a total and names nobody.
     */
    @Field("login")
    private String login;

    @Field("outcome")
    private LoginOutcome outcome;

    /**
     * When the attempt was made. <b>The TTL index is on this field</b>, so it is also what decides
     * when the row is deleted — see {@code LoginAttemptIndexes}.
     */
    @Field("attempted_at")
    private Instant attemptedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public LoginOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(LoginOutcome outcome) {
        this.outcome = outcome;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Instant attemptedAt) {
        this.attemptedAt = attemptedAt;
    }

    /**
     * The outcome and the time, and <b>never {@link #login}</b>.
     *
     * <p>JHipster's generated {@code toString} prints every field, and that is the shape this had to
     * not have: {@code User.toString} above prints a login and an email, which is exactly how an
     * identifier reaches a log without anybody writing a log statement about it — one
     * {@code LOG.debug("... {}", attempt)} and the exception in this class's javadoc is a Loki index.
     * {@code LoginAttemptNeverLoggedTest} refuses the interpolation as well, so this is the second of
     * two guards rather than the only one.
     */
    @Override
    public String toString() {
        return "LoginAttempt{id='" + id + "', outcome=" + outcome + ", attemptedAt=" + attemptedAt + "}";
    }
}
