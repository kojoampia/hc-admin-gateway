package net.jojoaddison.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How many times a test container may be started before this run gives up on it, for good.
 *
 * <p>Two things are being traded here and they pull in opposite directions.
 *
 * <p><b>Retrying helps, because the failure is a missed window rather than a broken machine.</b> A
 * loaded box misses the six-second replica-set election or the sixty-second readiness wait and the
 * next attempt, seconds later, succeeds. hc-patient shipped exactly that on 2026-09-06 (their gateway
 * PR #23) and it turned a confusing red into a slow green.
 *
 * <p><b>And retrying per class compounds, which is why theirs is not copied unchanged.</b>
 * {@code TestContainersSpringContextCustomizerFactory} assigns its static bean only after the
 * container has started, so a failed start leaves it null and the <em>next</em> class builds a
 * container of its own. Fifteen classes boot a context here — twelve through {@code @IntegrationTest}
 * and three through {@code @AuthenticationIntegrationTest} — against seventy-two in hc-admin-service,
 * where the same fixture and the same budget are in place. Measured there at load ~47 on 2026-09-09,
 * one failing class costs about 167 seconds; three attempts per class turns a saturated run from
 * minutes of red into hours of it, and nobody waits for that. They kill the build and read the first
 * stack trace, which names an innocent class.
 *
 * <p>So the budget is <b>per JVM, not per class</b>. The first class to fail gets every attempt, and
 * once they are spent nothing in this run asks Docker for that container again: every later class
 * throws {@link TestContainerUnavailableException#budgetAlreadySpent} immediately, carrying the
 * original failure as its cause. A saturated run is red in minutes with a message naming the reason,
 * rather than red in hours with a message naming {@code AuditingIT}.
 *
 * <p>The cost of that choice, stated plainly: a spike that clears <em>after</em> the budget is spent
 * but <em>before</em> the run would have finished now fails the whole run where the old behaviour
 * would have recovered from the second class onwards. The budget is what bounds it — three attempts is
 * about twenty seconds on a quiet machine and about eight minutes on a saturated one, and a spike that
 * outlasts eight minutes of retrying is not a spike. Runs stay red when the machine is saturated, on
 * purpose: a retry that hides contention buys green builds by making the machine's state unreportable,
 * and this fixture is the only thing in the suite that can see it.
 *
 * <p>Not static, and holds no static state, so {@code TestContainerStartBudgetTest} can exercise the
 * latch with a fresh instance and no reset hook. The one long-lived instance lives in
 * {@link MongoDbTestContainer}. This file is deliberately the same in hc-admin-service; keep the two
 * in step, because a fixture that behaves differently in the two repositories is a fixture nobody can
 * reason about from a failure message.
 */
final class TestContainerStartBudget {

    private static final Logger log = LoggerFactory.getLogger(TestContainerStartBudget.class);

    private final int attempts;
    private final long backoffMillis;

    private boolean spent;
    private RuntimeException terminalFailure;

    TestContainerStartBudget(int attempts, long backoffMillis) {
        this.attempts = attempts;
        this.backoffMillis = backoffMillis;
    }

    /**
     * Starts the container, retrying until the budget is gone and then refusing for the rest of the run.
     *
     * @param image the image name, for the diagnosis — this class never builds a container itself
     * @param start starts the container; must throw if it did not start
     * @param discard throws away the container that failed and prepares a fresh one. A half-started
     *     MongoDB container has a partly initialised replica set, and starting <em>that</em> again loops
     *     on {@code ReadConcernMajorityNotAvailableYet} instead of recovering, so the retry cannot reuse
     *     it. hc-patient found this first; it is the one piece of their fix that transfers unchanged.
     */
    void start(String image, Runnable start, Runnable discard) {
        if (spent) {
            TestContainerUnavailableException refusal = TestContainerUnavailableException.budgetAlreadySpent(
                image,
                attempts,
                terminalFailure
            );
            // Once per refusing class, and without the stack trace. The full banner below is logged once
            // and can be eight minutes and several thousand lines earlier by the time the run ends; what
            // a reader scrolling up from the summary needs is the answer within a screen of where their
            // eye lands, not a pointer to somewhere they have to go looking.
            log.warn(refusal.getMessage());
            throw refusal;
        }
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                start.run();
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt < attempts) {
                    log.warn(
                        "The {} test container did not start on attempt {} of {}. On this fixture that is normally a loaded machine missing the container's start window rather than a defect in the code under test — {}. Discarding the container and retrying in {} ms.",
                        image,
                        attempt,
                        attempts,
                        TestContainerUnavailableException.machineLoad(),
                        backoffMillis,
                        e
                    );
                    discard.run();
                    backOff();
                }
            }
        }
        spent = true;
        terminalFailure = lastFailure;
        // The container from the *final* attempt is deliberately not discarded, and the review that
        // spotted it was right that it lingers until Ryuk reaps it at JVM exit. Left alone anyway: this
        // path only runs on a machine already too busy to start a container, the whole point of the
        // latch below is that nothing else in this run will touch Docker, and stopping it means another
        // round trip to a daemon that is the thing failing — on the one path where the goal is to fail
        // fast and say why. Ryuk exists for exactly this and is running (it is the first container the
        // baseline log reports starting).
        TestContainerUnavailableException failure = TestContainerUnavailableException.exhausted(image, attempts, lastFailure);
        // Logged as well as thrown. The throw reaches the reader four Caused-by levels down under a
        // merged-configuration dump; this reaches them in the build output, once, at the moment it happens.
        log.error(failure.getMessage(), lastFailure);
        throw failure;
    }

    boolean isSpent() {
        return spent;
    }

    private void backOff() {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry a test container", e);
        }
    }
}
