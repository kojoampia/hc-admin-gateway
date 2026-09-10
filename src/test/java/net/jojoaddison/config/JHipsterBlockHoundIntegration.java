package net.jojoaddison.config;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

public class JHipsterBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("org.springframework.validation.beanvalidation.SpringValidatorAdapter", "validate");
        builder.allowBlockingCallsInside("net.jojoaddison.service.MailService", "sendEmailFromTemplate");
        builder.allowBlockingCallsInside("net.jojoaddison.security.DomainUserDetailsService", "createSpringSecurityUser");
        builder.allowBlockingCallsInside("org.springframework.web.reactive.result.method.InvocableHandlerMethod", "invoke");
        builder.allowBlockingCallsInside("org.springdoc.core.service.OpenAPIService", "build");
        builder.allowBlockingCallsInside("org.springdoc.core.service.AbstractRequestService", "build");
        builder.allowBlockingCallsInside("com.mongodb.internal.Locks", "checkedWithLock");
        // The MongoDB driver's cluster-clock lock, and the sibling of the generated allowance above it.
        //
        // Found by backlog item 75, which is the first code in this repository to issue CONCURRENT
        // reactive Mongo operations — AuthActivityService composes four independent reads with
        // Mono.zip. Every driver response calls ClusterClock.advance under a ReentrantLock; an
        // uncontended lock never parks, so nothing had ever tripped BlockHound before. Two responses
        // landing on one event loop in the same instant do, and it surfaced as a 500 on the endpoint
        // with a ClassCastException in ExceptionTranslator on top of it, naming neither the lock nor
        // the concurrency.
        //
        // Allowed rather than designed around, deliberately. What it guards is a BsonDocument
        // comparison and an assignment — no I/O, no user code, bounded by the driver — which is the
        // same category as checkedWithLock and not the category BlockHound exists to catch. The
        // alternative was to run the four reads one after another, which trades a nanosecond lock for
        // four round trips of latency on a screen an administrator refreshes, and would leave the next
        // piece of concurrent reactive Mongo in this repo to rediscover all of this.
        builder.allowBlockingCallsInside("com.mongodb.internal.Locks", "lockInterruptibly");
        // jhipster-needle-blockhound-integration - JHipster will add additional gradle plugins here
    }
}
