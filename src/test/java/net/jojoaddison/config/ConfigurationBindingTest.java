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
            binder
                .bind("jhipster.security", String.class)
                .ifBound(value -> {
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
    private Binder binderFor(String resource) throws IOException {
        FileSystemResource file = new FileSystemResource("src/main/resources/" + resource);
        assertThat(file.exists()).as("%s should exist under src/main/resources", resource).isTrue();

        List<PropertySource<?>> sources = loader.load(resource, file);
        assertThat(sources).as("%s should parse", resource).isNotEmpty();

        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addFirst(source);
        }
        return Binder.get(environment);
    }
}
