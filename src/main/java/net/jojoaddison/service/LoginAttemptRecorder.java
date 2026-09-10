package net.jojoaddison.service;

import java.time.Duration;
import java.time.Instant;
import net.jojoaddison.domain.LoginAttempt;
import net.jojoaddison.domain.LoginOutcome;
import net.jojoaddison.repository.LoginAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The one place a login outcome is written down, and the only class that holds the entered login.
 *
 * <p>It is a seam for the same reason {@code broker/OutboundEventPublisher} is one: the rule that
 * matters — <b>this write may never make a working login slow, and may never make one fail</b> — is
 * a property of how the write is attached, and a property like that survives only while there is
 * exactly one place it is attached. {@code AuthenticateController} calls {@link #record} and knows
 * nothing else about the collection.
 *
 * <h2>⚠ The failure semantics, which are the whole design</h2>
 *
 * <p><b>Fire and forget, off the caller's own signal.</b> {@link #record} subscribes the write
 * itself and returns immediately. It is called from {@code doOnNext} / {@code doOnError} on the
 * authentication {@code Mono}, so the response is composed from the original signal and never from
 * anything this class produces — <em>there is no operator on the login's path that this write can
 * delay, error or empty</em>. That is stronger than "we handle the error", and it is deliberately
 * not {@code flatMap}: a {@code flatMap} that swallows errors still makes the response wait for the
 * write.
 *
 * <p><b>Why the login must not be gated on it, argued rather than assumed.</b> The obvious
 * alternative is to fail closed — refuse to authenticate anyone we cannot audit. It is the wrong
 * answer here, twice over. This gateway's {@code jhi_user} lives in the same MongoDB as
 * {@code login_attempt}, so a database that is genuinely down already refuses every login on its
 * own; failing closed buys nothing in that case. What it does buy is the case that is not symmetric:
 * a database healthy enough to read {@code jhi_user} and <em>slow</em> to write would make every
 * sign-in on the estate's admin console wait for an audit row, which is a self-inflicted outage in
 * exchange for a statistic.
 *
 * <p><b>What it costs, stated rather than left to be discovered.</b> A write that fails is a row
 * that is lost, and this store is therefore a lower bound rather than a ledger. The console must not
 * present it as one — {@code AuthActivityDTO} carries no "total attempts ever" figure for exactly
 * this reason, only counts over a window that is already shorter than retention. The loss is not
 * silent: every one produces the {@code WARN} below.
 *
 * <p><b>{@link #WRITE_TIMEOUT} bounds how long a write may live, which is weaker than bounding how
 * many are alive — and the difference is worth stating rather than glossing.</b> Without it, a MongoDB
 * that accepts connections and never answers accumulates subscriptions that never complete, without
 * limit. With it, each one is gone within five seconds, so the in-flight set is bounded by the
 * <em>arrival rate</em> rather than by a constant: at N sign-in attempts per second it settles around
 * 5N, not at some fixed ceiling. That is a real bound and it is not a queue depth.
 *
 * <p>Two things keep the residue small, and neither is this timeout. An attacker driving those
 * attempts pays a BCrypt verification on the same request, which is far more expensive than the
 * subscription they are trying to accumulate; and the reactive driver's own connection pool bounds
 * how many of these writes are commands in flight rather than objects waiting. So the marginal
 * surface is minor — but "bounds the in-flight set", which this said until it was read carefully, is
 * a stronger claim than the code makes.
 *
 * <h2>⚠ Nothing here logs the login, and nothing here logs the exception either</h2>
 *
 * <p>The first is {@link LoginAttempt}'s second safeguard and needs no restating. The second is
 * less obvious and is the mistake this class was written to avoid: a failed Mongo write throws an
 * exception <b>whose message embeds the document it could not write</b>, so
 * {@code LOG.warn("…", e)} would put the entered login into Loki without any log statement
 * mentioning it. That is item 50's finding — "an exception message is not safe to log" — reaching a
 * second repository through a different door. The {@code WARN} names the exception's class and the
 * outcome, and {@code LoginAttemptNeverLoggedTest} refuses the exception object as an argument in
 * this file.
 *
 * <p>There is no {@code LogPseudonym} in this repository and one is deliberately not being ported:
 * it is a security primitive whose value is that there is one of it, a second copy in a second repo
 * is a second thing to drift, and the only line that could use it here — a warning that the store
 * dropped a row — is about the store rather than about the subject and gains nothing from naming
 * one.
 */
@Service
public class LoginAttemptRecorder {

    /**
     * How long one write may stay in flight before it is abandoned and warned about.
     *
     * <p>Generous against a healthy MongoDB — a single-document insert on the same connection pool
     * that just served the authentication read. It bounds the work, not the caller: nothing is
     * waiting on this.
     *
     * <p><b>What it does not do is bound how many writes are in flight</b>, and this javadoc said it
     * did until the claim was read carefully. The class javadoc above has the correction in full —
     * it bounds a write's <em>lifetime</em>, so the in-flight set settles at roughly the arrival rate
     * times five seconds rather than at a ceiling — and it is deliberately not restated here. Two
     * copies of a bound are two things to drift, which is how this sentence came to contradict the
     * paragraph that withdrew it, in the same commit.
     */
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(LoginAttemptRecorder.class);

    private final LoginAttemptRepository loginAttemptRepository;

    public LoginAttemptRecorder(LoginAttemptRepository loginAttemptRepository) {
        this.loginAttemptRepository = loginAttemptRepository;
    }

    /**
     * Writes one attempt down, and returns before it has been written.
     *
     * @param enteredLogin the login exactly as it arrived. Trimmed and truncated to
     *     {@link LoginAttempt#MAX_LOGIN_LENGTH}; a null or blank one writes nothing at all, because a
     *     row that adds to a total and names nobody is worse than no row.
     * @param outcome whether a token was issued.
     */
    public void record(String enteredLogin, LoginOutcome outcome) {
        String login = capped(enteredLogin);
        if (login == null) {
            return;
        }

        LoginAttempt attempt = new LoginAttempt();
        attempt.setLogin(login);
        attempt.setOutcome(outcome);
        attempt.setAttemptedAt(Instant.now());

        // subscribe() rather than returning the Mono: see the class javadoc. The caller's own signal
        // must not be able to reach this write, so the write is not on it.
        write(attempt).subscribe();
    }

    /**
     * The write, with its error handling attached — package-private so the recorder's contract can be
     * asserted without a scheduler race in the test.
     *
     * <p>{@code onErrorResume} rather than {@code doOnError}: this Mono is subscribed with no error
     * consumer, and an unhandled error there is dropped through Reactor's global hook, which logs a
     * stack trace containing the very exception message this method exists not to log.
     */
    Mono<Void> write(LoginAttempt attempt) {
        return loginAttemptRepository
            .save(attempt)
            .timeout(WRITE_TIMEOUT)
            .doOnNext(saved -> LOG.trace("Recorded a {} sign-in attempt", saved.getOutcome()))
            .onErrorResume(error -> {
                // The exception's CLASS, never the exception: its message embeds the document.
                LOG.warn(
                    "A {} sign-in attempt was not recorded ({}). The authentication itself was unaffected; " +
                        "the auth-activity figures are a lower bound until this stops.",
                    attempt.getOutcome(),
                    error.getClass().getName()
                );
                return Mono.empty();
            })
            .then();
    }

    /**
     * Trimmed and truncated, or null when there is nothing worth writing.
     *
     * <p>The cap is applied here rather than trusted to {@code LoginVM}'s {@code @Size(max = 50)},
     * and the distinction is the point: validation is a property of one request shape on one
     * endpoint, and this is a property of the collection. Relaxing the view model — or adding a
     * second caller — must not turn {@code login} into an unbounded attacker-controlled field.
     */
    private static String capped(String enteredLogin) {
        if (enteredLogin == null) {
            return null;
        }
        String trimmed = enteredLogin.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= LoginAttempt.MAX_LOGIN_LENGTH ? trimmed : trimmed.substring(0, LoginAttempt.MAX_LOGIN_LENGTH);
    }
}
