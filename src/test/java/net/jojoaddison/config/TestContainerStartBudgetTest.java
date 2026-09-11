package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The retry, and the latch that keeps it affordable across every class that boots a context.
 *
 * <p>Exercised here rather than against Docker because the behaviour worth pinning is the arithmetic,
 * not the container: how many times a failing start is tried, what a later class costs once the budget
 * is gone, and whether the message a reader ends up with names the cause. Backlog item 17. The same
 * file is in hc-admin-service against {@code mongo:7.0.4}; the two fixtures must agree.
 *
 * <p>Zero backoff throughout — the real fixture waits three seconds between attempts and that is the
 * one part of it a test has no reason to sit through.
 *
 * <p><b>The logger is captured rather than left alone, and that is not only tidiness.</b> This class
 * makes a container fail on purpose, so the fixture emits its full diagnostic banner — at ERROR, with
 * a load average and a pointer to the backlog — on a run where nothing is wrong. Left on the console
 * that is a green build carrying the exact text of a real outage, which is how a diagnostic stops being
 * read. Detaching it also gives somewhere to assert it: that the banner reaches the build output at all
 * is half of what makes this legible, and it is otherwise pinned by nothing.
 */
class TestContainerStartBudgetTest {

    private static final String IMAGE = "mongo:7.0.6";

    private Logger budgetLogger;
    private ListAppender<ILoggingEvent> captured;
    private boolean wasAdditive;

    @BeforeEach
    void captureTheFixturesOwnLogging() {
        budgetLogger = (Logger) LoggerFactory.getLogger(TestContainerStartBudget.class);
        captured = new ListAppender<>();
        captured.start();
        wasAdditive = budgetLogger.isAdditive();
        budgetLogger.setAdditive(false);
        budgetLogger.addAppender(captured);
    }

    @AfterEach
    void releaseTheLogger() {
        budgetLogger.detachAppender(captured);
        budgetLogger.setAdditive(wasAdditive);
        captured.stop();
    }

    @Test
    void aStartThatWorksIsNotRetriedAndSpendsNothing() {
        TestContainerStartBudget budget = new TestContainerStartBudget(3, 0L);
        AtomicInteger starts = new AtomicInteger();

        budget.start(IMAGE, starts::incrementAndGet, failIfDiscarded());

        assertThat(starts).hasValue(1);
        assertThat(budget.isSpent()).isFalse();
    }

    @Test
    void aTransientFailureRecoversOnTheNextAttemptAndDoesNotSpendTheBudget() {
        TestContainerStartBudget budget = new TestContainerStartBudget(3, 0L);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();

        budget.start(
            IMAGE,
            () -> {
                if (starts.incrementAndGet() == 1) {
                    throw new IllegalStateException("A single node replica set was not initialized in a set timeout: 60 attempts");
                }
            },
            discards::incrementAndGet
        );

        assertThat(starts).hasValue(2);
        // The half-started container is thrown away rather than started again: its replica set is
        // partly initialised and restarting that loops on ReadConcernMajorityNotAvailableYet.
        assertThat(discards).hasValue(1);
        assertThat(budget.isSpent()).isFalse();
    }

    @Test
    void aSustainedFailureIsTriedTheWholeBudgetAndThenExplainsItself() {
        TestContainerStartBudget budget = new TestContainerStartBudget(3, 0L);
        AtomicInteger starts = new AtomicInteger();
        RuntimeException containerFailure = new IllegalStateException(
            "A single node replica set was not initialized in a set timeout: 60 attempts"
        );

        assertThatThrownBy(() ->
            budget.start(
                IMAGE,
                () -> {
                    starts.incrementAndGet();
                    throw containerFailure;
                },
                () -> {}
            )
        )
            .isInstanceOf(TestContainerUnavailableException.class)
            .hasCause(containerFailure)
            // The whole point of the class. A reader who gets this instead of "ApplicationContext
            // failure threshold (1) exceeded" can act on it, so every clause is asserted.
            .hasMessageContaining(IMAGE)
            .hasMessageContaining("did not start in 3 attempts")
            .hasMessageContaining("almost never a defect in the code under test")
            .hasMessageContaining("load average")
            .hasMessageContaining("testcontainers.reuse.enable=true")
            .hasMessageContaining("docs/backlog.md item 17");

        assertThat(starts).hasValue(3);
        assertThat(budget.isSpent()).isTrue();

        // Thrown *and* logged. The throw reaches the reader as a Caused-by under two kilobytes of
        // merged-configuration dump that Spring truncates before the cause; the log line is the copy
        // they actually see. Measured on 2026-09-09: the summary line for a failed context is
        // "IllegalState ApplicationContext failure threshold (1) exceeded ..." and names no container
        // at all, so if this ERROR ever stops being emitted the diagnosis reaches nobody.
        assertThat(messagesAt(Level.WARN)).as("one warning per retried attempt").hasSize(2);
        assertThat(messagesAt(Level.ERROR)).singleElement().asString().contains("did not start in 3 attempts");
    }

    @Test
    void onceTheBudgetIsSpentALaterClassAsksDockerForNothingAtAll() {
        TestContainerStartBudget budget = new TestContainerStartBudget(3, 0L);
        RuntimeException containerFailure = new IllegalStateException("Timed out waiting for log output");
        spend(budget, containerFailure);

        AtomicInteger startsAfterwards = new AtomicInteger();

        assertThatThrownBy(() -> budget.start(IMAGE, startsAfterwards::incrementAndGet, failIfDiscarded()))
            .isInstanceOf(TestContainerUnavailableException.class)
            // The original failure travels with it, so nothing is hidden by failing fast.
            .hasCause(containerFailure)
            .hasMessageContaining("an earlier test class already spent the 3-attempt budget")
            .hasMessageContaining("docs/backlog.md item 17");

        // This is the measurement that matters: at load ~47 one failing class costs about 167 seconds,
        // so fifteen of them here — and seventy-two in hc-admin-service, on this same fixture —
        // retrying three times each is the difference between a run that is red in minutes and one
        // that is red in hours.
        assertThat(startsAfterwards).hasValue(0);

        // And the refusal says so where it happens. The banner is logged once and can be thousands of
        // lines earlier by the end of a long run; a reader scrolling up from the summary meets this.
        assertThat(messagesAt(Level.WARN)).last().asString().contains("has stopped trying");
    }

    private List<String> messagesAt(Level level) {
        return captured.list
            .stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static void spend(TestContainerStartBudget budget, RuntimeException failure) {
        try {
            budget.start(
                IMAGE,
                () -> {
                    throw failure;
                },
                () -> {}
            );
        } catch (TestContainerUnavailableException expected) {
            // spending the budget is the setup, not the assertion
        }
        assertThat(budget.isSpent()).isTrue();
    }

    private static Runnable failIfDiscarded() {
        return () -> {
            throw new AssertionError("A container that started, or was never started, must not be discarded");
        };
    }
}
