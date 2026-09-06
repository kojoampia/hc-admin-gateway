package net.jojoaddison.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.jojoaddison.broker.OutboundEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * The thread {@link OutboundEventPublisher} publishes on, and why it is its own.
 *
 * <p>Not {@code taskExecutor} from {@link AsyncConfiguration} and not a Reactor scheduler. Creating an
 * output binding against an unreachable broker blocks for up to a minute (see the class javadoc on
 * {@code OutboundEventPublisher}); putting that on the shared {@code @Async} pool would let one absent
 * broker occupy threads that have nothing to do with it, and putting it on the event loop is the
 * defect itself.
 *
 * <p>Three properties are deliberate and each is asserted by
 * {@code OutboundPublishingConfigurationTest}, because each is a plausible-looking edit that would
 * quietly undo the fix:
 *
 * <ul>
 *   <li><b>One thread.</b> Events reach the broker in the order they were produced, and a second
 *       thread would buy nothing — it would only queue on the same {@code StreamBridge} lock.
 *   <li><b>A bounded queue.</b> An unbounded one turns a broker outage into heap growth that nothing
 *       reports.
 *   <li><b>Abort, not caller-runs.</b> {@link ThreadPoolExecutor.CallerRunsPolicy} is the usual choice
 *       for a bounded queue and here it is exactly wrong: it hands a minute of blocking back to a
 *       Netty event-loop thread, and only under the load that makes it hardest to attribute.
 * </ul>
 */
@Configuration
public class OutboundPublishingConfiguration {

    /** See the bullet above; changing it is a decision, not tuning. */
    static final int QUEUE_CAPACITY = 256;

    /**
     * {@code shutdownNow}, not {@code shutdown} or {@code close}. On shutdown the thread may be a
     * minute into an AdminClient call against a broker that is not there, and Java 19+ makes
     * {@code close()} the inferred destroy method for an {@link ExecutorService} — which waits. These
     * events are best-effort by construction, so interrupting them is right and waiting for them would
     * add a minute to every stop during exactly the incident when restarts need to be quick.
     */
    @Bean(name = OutboundEventPublisher.EXECUTOR_BEAN, destroyMethod = "shutdownNow")
    public ExecutorService outboundEventExecutor() {
        return new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new CustomizableThreadFactory("hc-admin-gateway-publish-"),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
