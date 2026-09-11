package net.jojoaddison.web.rest;

import static net.jojoaddison.security.SecurityUtils.AUTHORITIES_KEY;
import static net.jojoaddison.security.SecurityUtils.JWT_ALGORITHM;
import static net.jojoaddison.security.SecurityUtils.USER_ID_KEY;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import net.jojoaddison.domain.LoginOutcome;
import net.jojoaddison.management.LoginMetersService;
import net.jojoaddison.security.Account;
import net.jojoaddison.service.LoginAttemptRecorder;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Controller to authenticate users.
 */
@RestController
@RequestMapping("/api")
public class AuthenticateController {

    private final Logger log = LoggerFactory.getLogger(AuthenticateController.class);

    private final JwtEncoder jwtEncoder;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds:0}")
    private long tokenValidityInSeconds;

    @Value("${jhipster.security.authentication.jwt.token-validity-in-seconds-for-remember-me:0}")
    private long tokenValidityInSecondsForRememberMe;

    private final ReactiveAuthenticationManager authenticationManager;

    private final LoginAttemptRecorder loginAttemptRecorder;

    private final LoginMetersService loginMetersService;

    public AuthenticateController(
        JwtEncoder jwtEncoder,
        ReactiveAuthenticationManager authenticationManager,
        LoginAttemptRecorder loginAttemptRecorder,
        LoginMetersService loginMetersService
    ) {
        this.jwtEncoder = jwtEncoder;
        this.authenticationManager = authenticationManager;
        this.loginAttemptRecorder = loginAttemptRecorder;
        this.loginMetersService = loginMetersService;
    }

    /**
     * {@code POST /authenticate} : issue a token, and write down that it was asked for.
     *
     * <h2>⚠ Where the recording is attached, and why it is there and not somewhere tidier</h2>
     *
     * <p>Until backlog item 75 <b>a failed login left no trace on this stack at all</b> — not in a
     * counter, not in a collection. {@code SecurityMetersService} counts JWT token-validation
     * failures and a wrong password is none of them; this controller referenced it nowhere.
     *
     * <p>Three properties of the code below are load-bearing:
     *
     * <ul>
     *   <li><b>The entered login is captured inside the first {@code flatMap}.</b> That lambda is the
     *       only scope in this method where {@code LoginVM} exists — a failure propagates from here
     *       as an error signal carrying a {@code BadCredentialsException}, which knows the principal
     *       but is not the thing to read it from, and the outer operators see no request body at all.
     *       So both hooks are attached <em>inside</em> that lambda, where they can close over the
     *       login this request sent, rather than on the outer chain where it is out of scope.</li>
     *   <li><b>The failure hook is {@code doOnError}, not a {@code catch}.</b> Nothing here throws;
     *       an unknown login, a wrong password and a deactivated account all arrive as an error
     *       signal on the way to {@code ExceptionTranslator}. A {@code try}/{@code catch} around this
     *       expression would record nothing and would look correct.</li>
     *   <li><b>Neither hook can change the response.</b> {@code doOnNext} and {@code doOnError} are
     *       side effects on a signal that continues unaltered, and
     *       {@link LoginAttemptRecorder#record} subscribes its own write and returns — so a MongoDB
     *       that is slow or down cannot make a working login hang and cannot make one fail. That is
     *       argued at length on the recorder, including why failing closed was rejected, and
     *       {@code LoginRecordFailureIT} induces it. The rule is the one
     *       {@code broker/OutboundEventPublisher} exists for, one package away: <b>a blocking or
     *       failing side effect here runs on a Netty event loop shared by every request on it.</b></li>
     * </ul>
     *
     * <p>Both outcomes are recorded, not just the failure. A failure count with no denominator
     * answers nothing — twenty failures is a bad afternoon on a busy console and an incident on a
     * quiet one — and the success row is also what says whether whoever was guessing eventually got
     * in.
     *
     * <h2>⚠ Two things are written per attempt, and they carry deliberately different amounts</h2>
     *
     * <p>Backlog item 80 added {@link LoginMetersService} beside the recorder: the collection keeps
     * the exact login for {@code GET /api/auth-activity}, the counter keeps a bounded
     * {@code outcome} label for Grafana, and <b>nothing carrying a subject goes anywhere near the
     * counter</b> — the metric leaves this process on an OTLP push into an estate-wide store that
     * none of {@link net.jojoaddison.domain.LoginAttempt}'s four safeguards reaches.
     *
     * <p>Both are incremented <em>here</em> rather than inside the recorder, and that is not
     * duplication for its own sake. The recorder writes nothing at all for a blank login, on the
     * argument that a row adding to a total and naming nobody is worse than no row; the counter has
     * no subject to be missing and must count that attempt like any other. Folding the increment into
     * the recorder would make the metric inherit a rule written for the collection, silently.
     * {@code LoginMetersIT} pins both increments to this seam.
     */
    @PostMapping("/authenticate")
    public Mono<ResponseEntity<JWTToken>> authorize(@Valid @RequestBody Mono<LoginVM> loginVM) {
        return loginVM
            .flatMap(login -> {
                String enteredLogin = login.getUsername();
                return authenticationManager
                    .authenticate(new UsernamePasswordAuthenticationToken(enteredLogin, login.getPassword()))
                    .flatMap(auth -> Mono.fromCallable(() -> this.createToken(auth, login.isRememberMe())))
                    .doOnNext(issued -> {
                        loginAttemptRecorder.record(enteredLogin, LoginOutcome.SUCCEEDED);
                        loginMetersService.trackLoginSuccess();
                    })
                    .doOnError(refused -> {
                        loginAttemptRecorder.record(enteredLogin, LoginOutcome.FAILED);
                        loginMetersService.trackLoginRefused();
                    });
            })
            .map(jwt -> {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.setBearerAuth(jwt);
                return new ResponseEntity<>(new JWTToken(jwt), httpHeaders, HttpStatus.OK);
            });
    }

    /**
     * {@code GET /authenticate} : check if the user is authenticated, and return its login.
     *
     * @param request the HTTP request.
     * @return the login if the user is authenticated.
     */
    @GetMapping("/authenticate")
    public Mono<String> isAuthenticated(ServerWebExchange request) {
        log.debug("REST request to check if the current user is authenticated");
        return request.getPrincipal().map(Principal::getName);
    }

    public String createToken(Authentication authentication, boolean rememberMe) {
        String authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.joining(" "));

        Instant now = Instant.now();
        Instant validity;
        if (rememberMe) {
            validity = now.plus(this.tokenValidityInSecondsForRememberMe, ChronoUnit.SECONDS);
        } else {
            validity = now.plus(this.tokenValidityInSeconds, ChronoUnit.SECONDS);
        }

        // @formatter:off
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(validity)
            .subject(authentication.getName())
            .claim(AUTHORITIES_KEY, authorities);

        // The account's database id, alongside its login in `sub`.
        //
        // The downstream admin service records who created and last changed each document, and its
        // domain models reference accounts by ID, not by login — the seed data puts
        // a0eebc99-...-a11 in createdBy, and CLAUDE.md names those ids a cross-service contract.
        // That service runs with skipUserManagement: true and cannot look an id up, so without this
        // claim it can only write logins into a field holding ids, mixing two identifier spaces in
        // one column. Absent for any principal that is not an Account, and the api falls back to
        // Constants.SYSTEM in that case.
        if (authentication.getPrincipal() instanceof Account user) {
            claims.claim(USER_ID_KEY, user.getId());
        }

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims.build())).getTokenValue();
    }

    /**
     * Object to return as body in JWT Authentication.
     */
    static class JWTToken {

        private String idToken;

        JWTToken(String idToken) {
            this.idToken = idToken;
        }

        @JsonProperty("id_token")
        String getIdToken() {
            return idToken;
        }

        void setIdToken(String idToken) {
            this.idToken = idToken;
        }
    }
}
