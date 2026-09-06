package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the three properties of the publisher's executor that a plausible edit would undo, each of
 * which would put backlog item 39a back without failing anything else.
 *
 * <p>These are configuration values rather than behaviour, and that is the point: the behaviour they
 * protect against — an event-loop thread blocking for a minute on an unreachable broker — cannot be
 * asserted anywhere in this suite, because no test here runs without a broker or on a loop.
 */
class OutboundPublishingConfigurationTest {

    private final ExecutorService executor = new OutboundPublishingConfiguration().outboundEventExecutor();

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    /** One thread, so events reach the broker in the order they were produced. */
    @Test
    void keepsExactlyOneThread() {
        assertThat(pool().getCorePoolSize()).isEqualTo(1);
        assertThat(pool().getMaximumPoolSize()).isEqualTo(1);
    }

    /** Bounded, so a broker that never answers cannot grow the queue without anything reporting it. */
    @Test
    void keepsTheQueueBounded() {
        assertThat(pool().getQueue().remainingCapacity()).isEqualTo(OutboundPublishingConfiguration.QUEUE_CAPACITY);
    }

    /**
     * The one that matters most, and the one that looks like an improvement.
     *
     * <p>{@link ThreadPoolExecutor.CallerRunsPolicy} is the conventional choice for a bounded queue
     * and it is exactly wrong here: it hands the blocking send back to a Netty event-loop thread,
     * which is the defect this executor exists to remove — and only under the load that makes it
     * hardest to attribute. Abort and drop instead; the events are best-effort and say so.
     */
    @Test
    void abortsRatherThanRunningTheSendOnTheCaller() {
        assertThat(pool().getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    private ThreadPoolExecutor pool() {
        assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
        return (ThreadPoolExecutor) executor;
    }
}
