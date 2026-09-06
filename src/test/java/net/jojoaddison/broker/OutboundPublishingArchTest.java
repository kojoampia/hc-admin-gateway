package net.jojoaddison.broker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import net.jojoaddison.AdminGatewayApp;

/**
 * {@link OutboundEventPublisher} is the only class that may hold a {@code StreamBridge}.
 *
 * <p>This gateway has one publisher today, so the rule reads as ceremony. It is not: the shape it
 * forbids reproduces because it reads perfectly well — inject the bridge, call {@code send}, return —
 * and hc-admin-service grew three copies of it before anyone measured what it cost (backlog item 39a).
 * In a reactive application the copy is worse, because the thread it blocks is an event loop shared by
 * every request in flight on it.
 *
 * <p>The guard is on the dependency rather than on any one method, so it needs no maintenance as
 * handlers are added. A list of known publishers would have to be extended by hand, and a test whose
 * coverage is extended by hand silently stops covering things.
 */
@AnalyzeClasses(packagesOf = AdminGatewayApp.class, importOptions = DoNotIncludeTests.class)
class OutboundPublishingArchTest {

    // prettier-ignore
    @ArchTest
    static final ArchRule onlyTheOutboundEventPublisherTouchesTheBroker = noClasses()
        .that()
        .doNotHaveFullyQualifiedName(OutboundEventPublisher.class.getName())
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.cloud.stream.function.StreamBridge")
        .because(
            "publishing on the request thread costs the caller up to sixty seconds against an unreachable broker, "
            + "and on an event loop it costs every request sharing it (backlog item 39a); route it through "
            + "OutboundEventPublisher, which hands the send to a single-threaded executor"
        );
}
