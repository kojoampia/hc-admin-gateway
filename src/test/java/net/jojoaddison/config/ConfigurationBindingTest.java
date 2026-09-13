package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import tech.jhipster.config.JHipsterProperties;

/**
 * Binds the shipped configuration files to the classes that read them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A bare {@code security:} key sat under {@code jhipster:} in {@code config/application.yml}
 * from the original 2024 generation, with nothing beneath it. Spring Boot 4.0.6 ignored it. Boot
 * 4.1 binds it as the empty String and fails, because {@code JHipsterProperties.security} is an
 * object:
 *
 * <pre>
 *   Failed to bind properties under 'jhipster' to tech.jhipster.config.JHipsterProperties:
 *     Property: jhipster.security
 *     Value: ""
 *     Reason: No setter found for property: security
 * </pre>
 *
 * <p>It took the gateway down on the first production deploy after that upgrade, and <em>nothing in
 * this repository could have caught it</em>. The entire suite runs under the {@code test} profile
 * against {@code src/test/resources/config/application.yml}; the file that ships inside the jar is
 * only ever read by a running application. `./mvnw verify` was green throughout.
 *
 * <p>It is a plain unit test, not an IT: it starts no context and needs no containers, so it runs
 * in surefire alongside the fast suite rather than behind the integration phase.
 *
 * <p>So this test reads the real files off the classpath and binds them, without starting a context
 * — no Mongo, no Consul, no Kafka. It is the cheapest thing that would have failed.
 */
class ConfigurationBindingTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    /**
     * Every profile's file, including the ones no test ever activates. {@code prod} is the whole
     * point: it is the one that runs in production and the one no test profile touches.
     */
    @ParameterizedTest
    @ValueSource(strings = { "config/application.yml", "config/application-dev.yml", "config/application-prod.yml" })
    void bindsToJHipsterProperties(String resource) throws IOException {
        Binder binder = binderFor(resource);

        assertThatCode(() -> binder.bind("jhipster", JHipsterProperties.class))
            .as("%s must bind to JHipsterProperties — an empty key whose target is an object fails here", resource)
            .doesNotThrowAnyException();
    }

    /**
     * The specific shape that caused the outage: a key present but empty, whose target is not a
     * String. Asserted directly so a regression names the cause rather than a binder stack trace.
     */
    @Test
    void jhipsterSecurityIsNeverAnEmptyValue() throws IOException {
        for (String resource : List.of("config/application.yml", "config/application-dev.yml", "config/application-prod.yml")) {
            Binder binder = binderFor(resource);
            binder.bind("jhipster.security", String.class).ifBound(value -> {
                throw new AssertionError(
                    resource +
                        " binds jhipster.security to the String \"" +
                        value +
                        "\". It is an object: an empty `security:` key here fails the whole application at startup."
                );
            });
        }
    }

    /**
     * <b>A stream binding without a {@code destination} publishes into a topic nothing reads.</b>
     *
     * <p>This is backlog item 40a, and it is stated as a rule rather than as the current state so it
     * stays useful. Spring Cloud Stream does not require {@code destination}: given a binding without
     * one it publishes to a topic named after the <em>binding</em>, so {@code binding-out-0} wrote to
     * a topic literally called {@code binding-out-0} while {@code kafkaConsumer-in-0} read
     * {@code sse-topic}. Producer and consumer sat four lines apart in one file, pointed at different
     * topics. The send succeeded, a 204 came back, the topic was created, and nothing ever read it —
     * there is no failure anywhere in that sequence, which is why it survived in both this repository
     * and hc-admin-service until somebody went looking.
     *
     * <p>This gateway declares no bindings at all today: item 40a deleted them with the REST surface
     * that drove them. So this currently asserts over an empty set, and that is deliberate — an
     * assertion that the set <em>is</em> empty would have to be deleted by the next person who adds a
     * publisher legitimately, and a guard that the next change deletes is not a guard. This one
     * greets them instead.
     */
    @ParameterizedTest
    @ValueSource(strings = { "config/application.yml", "config/application-dev.yml", "config/application-prod.yml" })
    void everyDeclaredStreamBindingCarriesADestination(String resource) throws IOException {
        String prefix = "spring.cloud.stream.bindings.";

        for (PropertySource<?> source : sourcesFor(resource)) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String binding = name.substring(prefix.length()).split("\\.", 2)[0];
                Object destination = enumerable.getProperty(prefix + binding + ".destination");

                assertThat(destination)
                    .as(
                        "%s declares the stream binding `%s` with no `destination`. Spring publishes that to a " +
                            "topic named after the binding, the send succeeds, and nothing reads it (backlog item 40a).",
                        resource,
                        binding
                    )
                    .isNotNull();
                assertThat(String.valueOf(destination).trim())
                    .as("%s declares `%s.destination` as blank, which is the same defect one step along", resource, binding)
                    .isNotEmpty();
            }
        }
    }

    /**
     * <b>The shipped {@code jhipster.clientApp.name} is the name the console reads.</b> Backlog item
     * 95.
     *
     * <p>{@code HeaderUtil} builds every alert header as {@code X-<clientApp.name>-alert} /
     * {@code -error} / {@code -params}, and {@code app/src/main/webapp/app/shared/jhipster/constants.ts}
     * reads {@code x-hcadminapp-*} and nothing else. Until 2026-09-13 this file said
     * {@code AdminGatewayApp} — derived from {@code .yo-rc.json}'s {@code baseName: adminGateway} —
     * so every alert this gateway sent, success and failure alike, arrived under a name the console
     * does not look for and was silently dropped.
     *
     * <p><b>This sweeps all three shipped files rather than asserting the one that sets it</b>,
     * because the defect this estate repeats most is a value corrected in the base file and
     * overridden in the profile that actually runs. Neither {@code application-dev.yml} nor
     * {@code application-prod.yml} carries the key today; if either gains it, it has to carry the
     * same value or this goes red.
     *
     * <p><b>Why here rather than only in an IT.</b> The whole suite runs against
     * {@code src/test/resources/config/application.yml}, which shadows the main file on the test
     * classpath — so {@code AlertHeaderNameIT}, which asserts the same literal on a real response,
     * would stay green with production emitting {@code X-AdminGatewayApp-alert}. This test reads the
     * file that ships. <b>Neither half is sufficient alone</b>: this one never sees a response, and
     * that one never sees the shipped file.
     *
     * <p>The expectation is a <b>literal</b>, deliberately. {@code hcAdminApp} diverges from
     * {@code .yo-rc.json}'s {@code baseName}, so a JHipster regeneration rewrites this property back
     * — which is how the mismatch survived from the generator's first commit. A test deriving the
     * expectation from the property would pass against whatever the regeneration put there.
     *
     * <p><b>Watched red before the value moved:</b>
     * {@code expected: "hcAdminApp" but was: "AdminGatewayApp"}.
     *
     * <p>The name is asked for as {@code jhipster.client-app.name}, not as the camel-case
     * {@code jhipster.clientApp.name} the yaml writes. {@code Binder} canonicalises before it
     * matches and rejects an upper-case letter outright —
     * {@code InvalidConfigurationPropertyNameException: 'jhipster.clientApp.name' is not valid},
     * which is an <em>error</em> rather than a failure and so reads as a broken test rather than as
     * a wrong value. Relaxed binding is what resolves the two to each other, here and at runtime.
     */
    @ParameterizedTest
    @ValueSource(strings = { "config/application.yml", "config/application-dev.yml", "config/application-prod.yml" })
    void clientAppNameIsTheNameTheConsoleReads(String resource) throws IOException {
        binderFor(resource)
            .bind("jhipster.client-app.name", String.class)
            .ifBound(name ->
                assertThat(name)
                    .as(
                        "%s sets jhipster.clientApp.name, which decides every alert header name this gateway emits " +
                            "(X-<name>-alert / -error / -params). app/src/main/webapp/app/shared/jhipster/constants.ts " +
                            "reads x-hcadminapp-alert, x-hcadminapp-error and x-hcadminapp-params and nothing else, so " +
                            "any other value means the console drops the alert without a trace (backlog item 95). If " +
                            "this went red after a JHipster regeneration, it rewrote the value from .yo-rc.json's " +
                            "baseName, adminGateway.",
                        resource
                    )
                    .isEqualTo("hcAdminApp")
            );
    }

    /**
     * The key has to be set <em>somewhere</em>, or the sweep above passes over an empty set and
     * three {@code @Value("${jhipster.clientApp.name}")} injections fail at startup instead.
     */
    @Test
    void clientAppNameIsSetAtAll() throws IOException {
        assertThat(binderFor("config/application.yml").bind("jhipster.client-app.name", String.class).isBound())
            .as(
                "config/application.yml must set jhipster.clientApp.name — AuthorityResource, UserResource and " +
                    "ExceptionTranslator all inject it with no default, so an unset key is a startup failure"
            )
            .isTrue();
    }

    /**
     * Read from {@code src/main/resources} on disk, NOT from the classpath.
     *
     * <p>This is the difference between a guard and a decoration. Under surefire,
     * {@code src/test/resources} precedes {@code src/main/resources}, so
     * {@code new ClassPathResource("config/application.yml")} resolves to the <em>test</em> file —
     * and an earlier version of this test did exactly that. It bound the test config, passed
     * happily, and would never have seen the shipped file that took production down. The test
     * config is already exercised by every other test in the suite; this one exists solely for the
     * file that is not.
     */
    private List<PropertySource<?>> sourcesFor(String resource) throws IOException {
        FileSystemResource file = new FileSystemResource("src/main/resources/" + resource);
        assertThat(file.exists()).as("%s should exist under src/main/resources", resource).isTrue();

        List<PropertySource<?>> sources = loader.load(resource, file);
        assertThat(sources).as("%s should parse", resource).isNotEmpty();
        return sources;
    }

    private Binder binderFor(String resource) throws IOException {
        List<PropertySource<?>> sources = sourcesFor(resource);

        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addFirst(source);
        }
        return Binder.get(environment);
    }
}
