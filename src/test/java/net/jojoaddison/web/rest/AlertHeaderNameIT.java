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
 * <h2>Both paths, and the second one arrived a day after the first</h2>
 *
 * <p>This class opened with the success case alone, and said so at length: item 91's precedent on the
 * api pins the name on a {@code BadRequestAlertException}, and <b>that surface did not work here</b>
 * — measured on 2026-09-13 against a real response from one raised through this stack, it carried
 * <b>no {@code X-}-prefixed alert header of any kind</b>, so there was no name on the wire to pin.
 * That was item 91's own defect, live in this repository and fixed only in the api, and it was
 * reported rather than fixed at the time because it was a different item with a different blast
 * radius.
 *
 * <p><b>That item is 97 and it closed on 2026-09-14</b>, so the refused-write case below is now
 * possible and is here. The two belong together: they are the same
 * {@code jhipster.clientApp.name} on the same {@code HeaderUtil}, and the asymmetry between them is
 * the thing an operator would actually have met — <em>confirmations appearing while refusals stay
 * mute</em>, which reads as "errors are broken" rather than as one unfinished item.
 *
 * <p>The dispatch defect item 97 fixed is argued where the fix is,
 * {@code ExceptionTranslator.handleErrorResponseException}; it is not restated here.
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
 * <p>The success case, before {@code jhipster.clientApp.name} changed — a <em>wrong name</em>:
 *
 * <pre>
 * [alert-header] emitted = [X-AdminGatewayApp-alert, X-AdminGatewayApp-params]
 * Expecting actual:
 *   ["X-AdminGatewayApp-alert", "X-AdminGatewayApp-params"]
 * to contain exactly (and in same order):
 *   ["X-hcAdminApp-alert", "X-hcAdminApp-params"]
 * </pre>
 *
 * <p>The refused-write case, before item 97's override existed — <em>no name at all</em>, which is a
 * different failure and reads differently:
 *
 * <pre>
 * [alert-header] refused write, emitted alert headers = []
 * Expecting actual:
 *   []
 * to contain exactly (and in same order):
 *   ["X-hcAdminApp-error", "X-hcAdminApp-params"]
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
    void aRefusedWriteCarriesTheFailureAlertHeadersTheConsoleReads() throws Exception {
        createdAuthority = "ROLE_AH_" + UUID.randomUUID();

        webTestClient
            .post()
            .uri("/api/authorities")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new Authority().name(createdAuthority)))
            .exchange()
            .expectStatus()
            .isCreated();

        // The same name a second time. AuthorityResource.createAuthority raises
        // BadRequestAlertException("authority already exists", "adminAuthority", "idexists").
        HttpHeaders headers = webTestClient
            .post()
            .uri("/api/authorities")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(new Authority().name(createdAuthority)))
            .exchange()
            .expectStatus()
            .isBadRequest()
            .returnResult(Void.class)
            .getResponseHeaders();

        List<String> emitted = headers
            .headerNames()
            .stream()
            .filter(name -> ALERT_HEADER.matcher(name).matches())
            .sorted()
            .toList();

        // Printed for the same reason the success case prints: a red run has to distinguish
        // "misnamed" from "absent", and this case was born of the second. Every header name goes
        // with it, because the answer to "was anything emitted at all" is the whole diagnosis here.
        System.out.println("[alert-header] refused write, emitted alert headers = " + emitted);
        System.out.println("[alert-header] refused write, all headers = " + headers.headerNames());
        // Printed rather than asserted: restoring the alert headers must not change the media type of
        // a refused write, and this is the line that would show it if it ever did.
        System.out.println("[alert-header] refused write, content type = " + headers.getContentType());

        assertThat(emitted)
            .as(
                "A refused write must carry the failure-alert headers the console reads. " +
                    "app/src/main/webapp/app/shared/jhipster/constants.ts declares %s and %s; with neither on the " +
                    "response getMessageFromHeaders finds no errorKey, falls to its error.message branch and the " +
                    "operator sees no alert at all — while a SUCCESSFUL write, since backlog item 95, does show " +
                    "one. If this went red as [] rather than as a wrong name: ExceptionTranslator's override of " +
                    "handleErrorResponseException has been removed or bypassed, which is backlog item 97 " +
                    "returning. If it went red on the name: a JHipster regeneration rewrote " +
                    "jhipster.clientApp.name from .yo-rc.json's baseName (adminGateway), item 95.",
                CONSOLE_ERROR_HEADER,
                CONSOLE_PARAMS_HEADER
            )
            .containsExactly("X-hcAdminApp-error", "X-hcAdminApp-params");

        assertThat(headers.getFirst(CONSOLE_ERROR_HEADER))
            .as("%s carries the translation key the console resolves, not the default message", CONSOLE_ERROR_HEADER)
            .isEqualTo("error.idexists");
        assertThat(headers.getFirst(CONSOLE_PARAMS_HEADER))
            .as("%s carries the entity name the console interpolates into {{ entityName }}", CONSOLE_PARAMS_HEADER)
            .isEqualTo("adminAuthority");
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
