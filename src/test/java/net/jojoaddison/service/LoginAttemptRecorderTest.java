package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.jojoaddison.domain.LoginAttempt;
import net.jojoaddison.domain.LoginOutcome;
import net.jojoaddison.repository.LoginAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link LoginAttemptRecorder}'s two contracts: what it stores, and what it does when it cannot.
 *
 * <p>Each of {@link LoginAttempt}'s safeguards is mutated <b>separately</b>, because a test that
 * asserts a whole document cannot distinguish "every rule holds" from "one rule holds and the others
 * were never exercised" — and three of the four here are one-line rules that a refactor removes
 * without touching the shape of the result.
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptRecorderTest {

    @Mock
    private LoginAttemptRepository loginAttemptRepository;

    private LoginAttemptRecorder recorder() {
        return new LoginAttemptRecorder(loginAttemptRepository);
    }

    private LoginAttempt recordAndCapture(String enteredLogin, LoginOutcome outcome) {
        when(loginAttemptRepository.save(any(LoginAttempt.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));

        recorder().record(enteredLogin, outcome);

        ArgumentCaptor<LoginAttempt> saved = ArgumentCaptor.forClass(LoginAttempt.class);
        // atLeastOnce and the LAST value, not verify(...) and getValue(): Mockito counts invocations
        // across the whole test method, so a case that records twice — which is exactly how the two
        // outcomes are told apart — fails on the second call with TooManyActualInvocations, naming
        // nothing about the rule under test.
        verify(loginAttemptRepository, atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }

    // --- what is written down ------------------------------------------------------------------

    /**
     * A failed sign-in produces a row, which it did not before backlog item 75 — <b>anywhere, in any
     * store, at any level</b>. This is the case that goes red if the failure hook is ever detached
     * from {@code AuthenticateController}'s error signal.
     */
    @Test
    void aFailedAttemptIsWrittenDownWithTheLoginThatWasTried() {
        LoginAttempt attempt = recordAndCapture("not-a-real-account", LoginOutcome.FAILED);

        assertThat(attempt.getOutcome()).isEqualTo(LoginOutcome.FAILED);
        assertThat(attempt.getLogin()).isEqualTo("not-a-real-account");
        assertThat(attempt.getAttemptedAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
    }

    /**
     * And a successful one, distinguishably.
     *
     * <p>The two outcomes have to be told apart by a reader, which is a stronger claim than "both
     * write a row": a recorder that stamped one constant would satisfy every count in
     * {@code AuthActivityResourceIT} as long as the numbers happened to line up, and would report
     * every failure as a success.
     */
    @Test
    void aSuccessfulAttemptIsWrittenDownAsSuchAndIsNotConfusedWithAFailure() {
        assertThat(recordAndCapture("admin", LoginOutcome.SUCCEEDED).getOutcome()).isEqualTo(LoginOutcome.SUCCEEDED);
        assertThat(recordAndCapture("admin", LoginOutcome.FAILED).getOutcome()).isEqualTo(LoginOutcome.FAILED);
    }

    /**
     * <b>The login is not normalised</b>, unlike {@code User.setLogin}, which lower-cases. Here the
     * value is evidence rather than a key — "somebody is trying Admin, ADMIN and admin in turn" is a
     * fact about the attempt, and lower-casing erases it.
     */
    @Test
    void theLoginIsStoredWithItsCasingIntact() {
        assertThat(recordAndCapture("ADMIN", LoginOutcome.FAILED).getLogin()).isEqualTo("ADMIN");
    }

    // --- safeguard 3: the length cap -----------------------------------------------------------

    /**
     * <b>Safeguard three, mutated on its own.</b> This field holds an arbitrary attacker-supplied
     * string; uncapped it is a storage-exhaustion vector reachable by anyone who can open a socket.
     * Delete the {@code substring} in {@code capped} and only this case goes red.
     */
    @Test
    void aLoginLongerThanTheCapIsTruncatedRatherThanStoredWhole() {
        String enormous = "a".repeat(5_000);

        LoginAttempt attempt = recordAndCapture(enormous, LoginOutcome.FAILED);

        assertThat(attempt.getLogin()).hasSize(LoginAttempt.MAX_LOGIN_LENGTH).isEqualTo("a".repeat(LoginAttempt.MAX_LOGIN_LENGTH));
    }

    /**
     * The cap is deliberately <em>above</em> what {@code LoginVM}'s {@code @Size(max = 50)} admits, so
     * it never fires on anything a real request can carry. Truncating a legitimate login would merge
     * two accounts into one row-key on the day that constraint is relaxed — the same defect the cap
     * exists to prevent, wearing the other face.
     */
    @Test
    void aLoginAsLongAsTheEndpointAcceptsIsStoredWhole() {
        String longestReachable = "b".repeat(50);

        assertThat(recordAndCapture(longestReachable, LoginOutcome.FAILED).getLogin()).isEqualTo(longestReachable);
    }

    @Test
    void nothingIsWrittenForALoginThatNamesNobody() {
        recorder().record(null, LoginOutcome.FAILED);
        recorder().record("   ", LoginOutcome.FAILED);

        verify(loginAttemptRepository, never()).save(any());
    }

    // --- the failure semantics -----------------------------------------------------------------

    /**
     * <b>A MongoDB failure does not fail the login.</b> Induced rather than reasoned about: the
     * repository errors, and {@link LoginAttemptRecorder#record} still returns normally, having
     * swallowed it.
     *
     * <p>This is the half a caller can observe. {@code AuthenticateControllerIT} covers the other
     * half — that a sign-in still returns its token — and neither alone is the property: a recorder
     * that threw from {@code record} would take the event loop's signal with it, and one that merely
     * returned a failing {@code Mono} nobody subscribes would leak it to Reactor's dropped-error hook,
     * which logs a stack trace carrying the exception message this whole class avoids.
     */
    @Test
    void aFailedWriteDoesNotEscapeTheRecorder() {
        when(loginAttemptRepository.save(any(LoginAttempt.class))).thenReturn(
            Mono.error(new IllegalStateException("the write failed and this message would embed the document"))
        );

        assertThatCode(() -> recorder().record("somebody", LoginOutcome.FAILED)).doesNotThrowAnyException();
    }

    /**
     * The same, asserted on the publisher rather than on the call, so that "swallowed" is a property
     * of the write and not an accident of when the subscription happened to run.
     *
     * <p>{@code onErrorResume} rather than {@code doOnError} is what makes this complete instead of
     * erroring, and the distinction is not cosmetic — see {@link #aFailedWriteDoesNotEscapeTheRecorder()}.
     */
    @Test
    void theWriteCompletesEmptyRatherThanErroringWhenMongoRefusesIt() {
        when(loginAttemptRepository.save(any(LoginAttempt.class))).thenReturn(Mono.error(new IllegalStateException("no")));

        LoginAttempt attempt = new LoginAttempt();
        attempt.setLogin("somebody");
        attempt.setOutcome(LoginOutcome.FAILED);
        attempt.setAttemptedAt(Instant.now());

        StepVerifier.create(recorder().write(attempt)).verifyComplete();
    }

    /**
     * {@code toString} prints the outcome and never the login.
     *
     * <p>JHipster's generated {@code toString} prints every field — {@code User}'s prints a login and
     * an email — and that is how an identifier reaches a log with nobody writing a log statement about
     * it. {@code LoginAttemptNeverLoggedTest} refuses the interpolation; this refuses the payload, so
     * neither guard is the only one.
     */
    @Test
    void theDocumentDoesNotPrintItsOwnLogin() {
        LoginAttempt attempt = recordAndCapture("a-secret-looking-login", LoginOutcome.FAILED);

        assertThat(attempt.toString()).doesNotContain("a-secret-looking-login").contains("FAILED");
    }
}
