package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Authority;
import net.jojoaddison.repository.AuthorityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * This gateway emits the alert-header names the console reads. Backlog item 95.
 *
 * <h2>What was broken</h2>
 *
 * <p>Three names, none agreeing, all read from their own files on 2026-09-13:
 *
 * <pre>
 *   api      emits  X-hcAdminServiceApp-*   from jhipster.clientApp.name: hcAdminServiceApp
 *   gateway  emits  X-AdminGatewayApp-*     from jhipster.clientApp.name: AdminGatewayApp
 *   app      reads  x-hcadminapp-*          from shared/jhipster/constants.ts
 * </pre>
 *
 * <p><b>The console matched neither service</b>, and one console constant cannot match two
 * differently-named services — which is why the architect's decision was one name across both:
 * {@code hcAdminApp}, the value the console already reads. The api is being set to the same value in
 * its own repository.
 *
 * <p><b>It is not only the error path, and in this repository it is <em>mostly</em> not.</b>
 * {@code notificationInterceptor} reads the same three constants through
 * {@code getMessageFromHeaders}, and {@code MESSAGE_ALERT_HEADER_NAME} is the <em>success</em>
 * header. So no confirmation has ever been shown for a user creation, a user deletion or any other
 * write this gateway owns — an administrator saving a record sees nothing at all.
 *
 * <h2>Why a success response rather than a refused one</h2>
 *
 * <p>Item 91's precedent on the api pins the name on a {@code BadRequestAlertException}. <b>That
 * surface does not work here</b>, measured on 2026-09-13 against a real response from a
 * {@code BadRequestAlertException} raised through this stack: it carried <b>no {@code X-}-prefixed
 * alert header of any kind</b>, so there was no name on the wire to pin. That is item 91's own
 * defect — {@code BadRequestAlertException extends ErrorResponseException}, for which
 * {@code ResponseEntityExceptionHandler} declares a handler more specific than this advice's
 * {@code @ExceptionHandler(Throwable)}, so {@code ExceptionTranslator.buildHeaders} is never entered
 * — present in this repository and fixed only in the api. Item 91's closing note said to check the
 * sibling gateways; this is that check, and it is <b>reported rather than fixed here</b>, being a
 * different item with a different blast radius.
 *
 * <p>So the name is pinned where this gateway really does emit one: {@code HeaderUtil} on the
 * success path. It is the same {@code jhipster.clientApp.name} on both paths, so one value settles
 * both — and this is the path an operator actually sees.
 *
 * <h2>Why it is its own class, and why the expectation is a literal</h2>
 *
 * <p><b>Its own class</b> because every other candidate home is generated. {@code hcAdminApp}
 * diverges from {@code .yo-rc.json}'s {@code baseName: adminGateway}, so a JHipster regeneration
 * rewrites {@code jhipster.clientApp.name} back — which is exactly how this mismatch survived from
 * the generator's first commit to 2026-09-13. A guard living in {@code AuthorityResourceIT} or
 * {@code ExceptionTranslatorIT} would be overwritten by the same regeneration it exists to catch.
 *
 * <p><b>A literal</b> because a test that reads {@code jhipster.clientApp.name} and builds
 * {@code "X-" + name + "-alert"} passes whatever the property says, including whatever a
 * regeneration puts back. It would assert that {@code HeaderUtil} concatenates, which nobody doubts.
 * The literal below is a copy of the console's own constant — the thing that has to agree, and the
 * thing this repository cannot import.
 *
 * <p><b>This pins the config the tests read, not the config that ships.</b>
 * {@code src/test/resources/config/application.yml} shadows the main one on the test classpath, so
 * the value exercised here is the test file's. The shipped files are pinned to the same literal by
 * {@code ConfigurationBindingTest.clientAppNameIsTheNameTheConsoleReads}, which reads
 * {@code src/main/resources} off disk. <b>Neither half is sufficient alone</b>: this one would stay
 * green with production emitting {@code X-AdminGatewayApp-alert}, and that one never sees a
 * response.
 *
 * <h2>Watched red before the value moved</h2>
 *
 * <pre>
 * [alert-header] emitted = [X-AdminGatewayApp-alert, X-AdminGatewayApp-params]
 * Expecting actual:
 *   ["X-AdminGatewayApp-alert", "X-AdminGatewayApp-params"]
 * to contain exactly (and in same order):
 *   ["X-hcAdminApp-alert", "X-hcAdminApp-params"]
 * </pre>
 */
@IntegrationTest
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_ENTITY_TIMEOUT)
@WithMockUser(authorities = { "ROLE_ADMIN" })
class AlertHeaderNameIT {

    /**
     * The three names {@code app/src/main/webapp/app/shared/jhipster/constants.ts} declares, copied
     * as literals rather than derived. The console is a different repository; a constant it cannot
     * import is the whole reason the two sides drifted.
     */
    private static final String CONSOLE_ALERT_HEADER = "x-hcadminapp-alert";
    private static final String CONSOLE_ERROR_HEADER = "x-hcadminapp-error";
    private static final String CONSOLE_PARAMS_HEADER = "x-hcadminapp-params";

    /** Any {@code X-<something>-alert|error|params}, whatever the {@code <something>} turns out to be. */
    private static final Pattern ALERT_HEADER = Pattern.compile("(?i)^x-.+-(alert|error|params)$");

    @Autowired
    private ObjectMapper om;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Autowired
    private WebTestClient webTestClient;

    private String createdAuthority;

    /**
     * Removes only what this class created. {@code AuthorityResourceIT} opens with
     * {@code deleteAll()}, which takes the seeded authorities with it; a name guard has no business
     * emptying a collection to assert a header.
     */
    @AfterEach
    void removeWhatThisTestCreated() {
        if (createdAuthority != null) {
            authorityRepository.deleteById(createdAuthority).block();
            createdAuthority = null;
        }
    }

    @Test
    void aSuccessfulWriteCarriesTheAlertHeaderNamesTheConsoleReads() throws Exception {
        // Authority.name is @Size(max = 50) and is the document id, so the prefix is abbreviated
        // rather than spelled out: "ROLE_AH_" plus a UUID is 44 characters.
        createdAuthority = "ROLE_AH_" + UUID.randomUUID();

        HttpHeaders headers = webTestClient
            .post()
            .uri("/api/authorities")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new Authority().name(createdAuthority)))
            .exchange()
            .expectStatus()
            .isCreated()
            .returnResult(Void.class)
            .getResponseHeaders();

        List<String> emitted = headers
            .headerNames()
            .stream()
            .filter(name -> ALERT_HEADER.matcher(name).matches())
            .sorted()
            .toList();

        // Printed so a red run shows what WAS sent beside what was expected. The name is the entire
        // subject of this case, and an assertion showing only the expectation sends the next reader
        // looking for a missing header rather than for a misnamed one.
        System.out.println("[alert-header] emitted = " + emitted);

        assertThat(emitted)
            .as(
                "A successful write must carry the alert-header names the console reads. " +
                    "app/src/main/webapp/app/shared/jhipster/constants.ts declares %s, %s and %s, and reads nothing " +
                    "else — a header under any other name is not read at all, so the toast is silently dropped and " +
                    "the operator sees nothing. If this went red after a JHipster regeneration: it rewrote " +
                    "jhipster.clientApp.name from .yo-rc.json's baseName (adminGateway), and the value it must " +
                    "carry is hcAdminApp (backlog item 95).",
                CONSOLE_ALERT_HEADER,
                CONSOLE_ERROR_HEADER,
                CONSOLE_PARAMS_HEADER
            )
            .containsExactly("X-hcAdminApp-alert", "X-hcAdminApp-params");

        // HttpHeaders is case-insensitive, as is every HTTP client the console could be using, so
        // reading by the console's own lower-case constant is the closest this side can get to
        // reading the response the way the console reads it.
        assertThat(headers.getFirst(CONSOLE_ALERT_HEADER))
            .as("%s carries the message the console shows in the toast", CONSOLE_ALERT_HEADER)
            .isEqualTo("A new adminAuthority is created with identifier " + createdAuthority);
        assertThat(headers.getFirst(CONSOLE_PARAMS_HEADER))
            .as("%s carries the identifier that message names", CONSOLE_PARAMS_HEADER)
            .isEqualTo(createdAuthority);
    }
}
