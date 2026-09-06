package net.jojoaddison.web.rest;

import net.jojoaddison.broker.KafkaConsumer;
import net.jojoaddison.broker.OutboundEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin-gateway-kafka")
public class AdminGatewayKafkaResource {

    private static final String PRODUCER_BINDING_NAME = "binding-out-0";

    private final Logger log = LoggerFactory.getLogger(AdminGatewayKafkaResource.class);
    private final KafkaConsumer kafkaConsumer;
    private final OutboundEventPublisher eventPublisher;

    public AdminGatewayKafkaResource(OutboundEventPublisher eventPublisher, KafkaConsumer kafkaConsumer) {
        this.eventPublisher = eventPublisher;
        this.kafkaConsumer = kafkaConsumer;
    }

    /**
     * Hands the send to {@link OutboundEventPublisher} and answers immediately.
     *
     * <p>This handler had the shape backlog item 39a describes — {@code streamBridge.send} inline —
     * and in a reactive application it is on a Netty event-loop thread, shared by every request that
     * loop is multiplexing. Creating the output binding against an unreachable broker blocks for up to
     * a minute there; the reasoning is on the publisher.
     *
     * <p>Nothing observable is given up. The 204 was already unconditional: {@code send}'s result was
     * discarded, and it only ever reported that the message reached the producer's accumulator rather
     * than that the broker received it. What the bridge is really doing is answered by {@code /consume}.
     */
    @PostMapping("/publish")
    public Mono<ResponseEntity<Void>> publish(@RequestParam("message") String message) {
        log.debug("REST request the message : {} to send to Kafka topic", message);
        eventPublisher.publish(PRODUCER_BINDING_NAME, message, "The message posted to /publish");
        return Mono.just(ResponseEntity.noContent().build());
    }

    @GetMapping("/consume")
    public Flux<String> consume() {
        log.debug("REST request to consume records from Kafka topics");
        return this.kafkaConsumer.getFlux();
    }
}
