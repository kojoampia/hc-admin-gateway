package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;

/**
 * What can actually be asserted about backlog item 39a in this repository, and what cannot.
 *
 * <p>The defect is a <b>duration</b>, not a wrong answer: the endpoint returns 204 either way, and the
 * only difference between a healthy stack and a broken one is sixty seconds on the first call.
 * Asserting "fast" is flaky by construction and asserting "slow" needs a stack with the broker taken
 * away, which no test here has.
 *
 * <p>So the property under test is the one the fix establishes and which is true or false without
 * reference to a clock: <b>the send does not happen on the calling thread</b> — in a reactive
 * application, on the event loop. An executor that records its tasks and never runs them makes that a
 * plain equality.
 *
 * <p><b>What this cannot catch:</b> the sixty seconds itself; that an event-loop thread is genuinely
 * freed, since nothing here runs on one; and a future publisher reaching the broker through
 * {@code KafkaTemplate} rather than {@code StreamBridge}. {@code OutboundPublishingArchTest} covers
 * the last one that can be covered — no <i>other</i> class holding a {@code StreamBridge}.
 */
class OutboundEventPublisherTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);

    /** Records what was handed over without running it, so "was it inline?" is directly observable. */
    private final List<Runnable> queued = new ArrayList<>();

    private final OutboundEventPublisher publisher = new OutboundEventPublisher(streamBridge, queued::add);

    @Test
    void handsTheSendToTheExecutorRatherThanRunningItOnTheCaller() {
        publisher.publish("binding-out-0", "hello", "The message posted to /publish");

        // The assertion that fails if the publish goes back onto the event loop. Nothing about it is
        // timing-dependent: the executor here has run nothing at all yet.
        verifyNoInteractions(streamBridge);
        assertThat(queued).hasSize(1);
    }

    @Test
    void sendsTheDeclaredBindingAndPayloadOnceTheExecutorRunsIt() {
        when(streamBridge.send(anyString(), anyString())).thenReturn(true);

        publisher.publish("binding-out-0", "hello", "The message posted to /publish");
        queued.forEach(Runnable::run);

        verify(streamBridge).send("binding-out-0", "hello");
    }

    @Test
    void aFailedSendIsSwallowedOnThePublisherThread() {
        when(streamBridge.send(anyString(), anyString())).thenThrow(new IllegalStateException("no broker"));

        publisher.publish("binding-out-0", "hello", "The message posted to /publish");

        assertThatCode(() -> queued.forEach(Runnable::run)).doesNotThrowAnyException();
    }

    @Test
    void aRejectedTaskIsDroppedRatherThanThrownAtTheCaller() {
        Executor full = task -> {
            throw new RejectedExecutionException("queue full");
        };
        OutboundEventPublisher publisherOnAFullQueue = new OutboundEventPublisher(streamBridge, full);

        assertThatCode(
            () -> publisherOnAFullQueue.publish("binding-out-0", "hello", "The message posted to /publish")
        ).doesNotThrowAnyException();
        verifyNoInteractions(streamBridge);
    }
}
