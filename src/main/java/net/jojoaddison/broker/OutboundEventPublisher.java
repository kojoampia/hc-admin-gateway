package net.jojoaddison.broker;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

/**
 * The one place this gateway publishes to the broker, and the only class allowed to hold a
 * {@link StreamBridge}. {@code OutboundPublishingArchTest} enforces that second half.
 *
 * <p><strong>Why it exists.</strong> Backlog item 39a was measured in hc-admin-service, where
 * {@code MessageService.send} called {@code streamBridge.send(...)} on the request thread and the
 * first {@code POST /api/messages/send} against a stack with no broker took <b>60.6s</b> — with the
 * row written, a 201 returned and nothing failing. {@code AdminGatewayKafkaResource} was checked
 * rather than assumed and has the same shape, on the same binding name.
 *
 * <p>The sixty seconds is <b>not</b> the publish. Once an output binding exists the Kafka producer
 * buffers and returns; it is the <em>creation</em> of the binding that is slow, and
 * {@code StreamBridge} creates one lazily inside the first {@code send()} for that destination:
 * {@code resolveDestination} calls {@code BindingService.bindProducer}, the Kafka provisioner opens an
 * AdminClient, and that future is bounded by {@code default.api.timeout.ms}, whose default is 60000.
 * {@code StreamBridge} holds one {@code ReentrantLock} across both that and the conversion in
 * {@code send}, so anything else publishing meanwhile queues behind it.
 *
 * <p><strong>Here it is worse than in the service, and that is why this repo is in scope.</strong>
 * hc-admin-gateway is reactive. A blocking call in a handler runs on a Netty event-loop thread, and
 * there is one per core for the whole application — so a minute spent creating a binding does not
 * stall one request, it stalls every request that loop is multiplexing, none of which has anything to
 * do with Kafka. This class hands the send to a dedicated executor and the loop is never held.
 *
 * <p><strong>What it costs.</strong> The sixty-second response was the only externally visible symptom
 * of an unreachable broker and this removes it; nothing replaces it, because
 * {@code MANAGEMENT_HEALTH_BINDERS_ENABLED=false} in every compose file that runs this gateway and
 * {@code management.prometheus.metrics.export.enabled} is {@code false} in {@code application-prod.yml}.
 * The {@code WARN} below is the whole of the visibility, exactly as it was before — backlog item 40.
 */
@Component
public class OutboundEventPublisher {

    /**
     * The executor bean this class runs on. Named here rather than in the configuration because the
     * dependency is the other way round: {@code ..config..} may reach anything, nothing may reach it.
     */
    public static final String EXECUTOR_BEAN = "outboundEventExecutor";

    private static final Logger LOG = LoggerFactory.getLogger(OutboundEventPublisher.class);

    private final StreamBridge streamBridge;

    private final Executor executor;

    public OutboundEventPublisher(StreamBridge streamBridge, @Qualifier(EXECUTOR_BEAN) Executor executor) {
        this.streamBridge = streamBridge;
        this.executor = executor;
    }

    /**
     * Queues one already-serialised event and returns immediately.
     *
     * <p>A plain {@link Executor} rather than {@code Schedulers.boundedElastic()}, which is the usual
     * reactive answer to a blocking call. Three reasons, and they are the same three the service's
     * copy gives: one thread keeps events in the order they were produced; a bounded queue puts a
     * ceiling on what a broker that never answers can accumulate; and a thread named
     * {@code hc-admin-gateway-publish-} says what it is in a thread dump, where a shared elastic pool
     * would only say that something somewhere is blocked.
     *
     * @param bindingName the binding as {@code application.yml} declares it, e.g. {@code binding-out-0}
     * @param payload the wire form, already serialised
     * @param subject what to name in the log if this never reaches the broker
     */
    public void publish(String bindingName, String payload, String subject) {
        try {
            executor.execute(() -> send(bindingName, payload, subject));
        } catch (RejectedExecutionException e) {
            // The queue is full, which takes a broker that has been unreachable long enough for the
            // one thread to still be stuck creating the binding. Dropping is the honest answer: the
            // alternative policies either run this on the event loop — the defect this class exists
            // to remove — or grow without bound.
            LOG.warn("Dropped the event for {} on {}: the outbound publish queue is full", subject, bindingName);
        }
    }

    /**
     * Runs on the publisher thread. Never rethrows: there is no caller left to tell.
     *
     * <p><b>A missing broker is silent</b> — the app starts, serves and reports healthy while
     * everything produced goes nowhere. This log line is the only thing that says so.
     */
    private void send(String bindingName, String payload, String subject) {
        try {
            streamBridge.send(bindingName, payload);
        } catch (RuntimeException e) {
            LOG.warn("{} could not be published to {}", subject, bindingName, e);
        }
    }
}
