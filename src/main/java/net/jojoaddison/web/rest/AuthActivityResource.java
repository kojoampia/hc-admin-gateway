package net.jojoaddison.web.rest;

import net.jojoaddison.service.AuthActivityService;
import net.jojoaddison.service.dto.AuthActivityDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code GET /api/auth-activity} — who has an account here, and what has happened at the sign-in
 * endpoint.
 *
 * <h2>⚠ {@code ROLE_ADMIN} alone, and that is narrower than every other read on this console</h2>
 *
 * <p>The rule is stated in {@code SecurityConfiguration} above the blanket {@code /api/**} matcher —
 * <b>not here</b>, because a {@code @PreAuthorize} beside a chain rule is two places for one decision
 * to drift and the chain is the one that actually holds. {@code SecurityConfigurationOrderTest} pins
 * the position (below it, this path is {@code authenticated()} like anything else under
 * {@code /api/**}, which is every token in the estate) and {@code AuthActivityResourceIT} pins the
 * decision for an admin, an operator, a plain user and an anonymous caller separately.
 *
 * <p><b>An operator is refused, and that is deliberate rather than an oversight.</b> An operator
 * reads the whole entity surface of hc-admin-service, including the patient directory with its
 * contact addresses ({@code DirectoryLinkResource}'s javadoc argues that one). What they may not
 * read is this, because {@code topFailedLogins} names logins <em>as they were entered</em> — see
 * {@link net.jojoaddison.domain.LoginAttempt}, whose first safeguard this rule is. The pattern is
 * the one item 53 set for the patient CSV export: an operator may work the console without being
 * handed the copy of it that is most useful to somebody who should not have it.
 *
 * <p>Read-only, so there is no {@code POST}/{@code PUT} shape to keep. The path is singular for the
 * same reason {@code /api/dashboard/metrics} is on the api: this is one computed summary, not a CRUD
 * surface over a collection, and {@code login_attempt} deliberately has no CRUD surface at all.
 */
@RestController
@RequestMapping("/api/auth-activity")
public class AuthActivityResource {

    private static final Logger LOG = LoggerFactory.getLogger(AuthActivityResource.class);

    private final AuthActivityService authActivityService;

    public AuthActivityResource(AuthActivityService authActivityService) {
        this.authActivityService = authActivityService;
    }

    /**
     * {@code GET /api/auth-activity} : the whole panel in one response.
     *
     * <p>One call rather than four, for the reason {@code DashboardMetricsResource} gives next door:
     * the screen renders as a unit, and a screen that fires four requests on load is four chances to
     * half-render.
     *
     * @return {@code 200 OK} with the figures.
     */
    @GetMapping
    public Mono<ResponseEntity<AuthActivityDTO>> getAuthActivity() {
        LOG.debug("REST request to get authentication activity");
        return authActivityService.activity().map(ResponseEntity::ok);
    }
}
