package net.jojoaddison.web.rest.errors;

import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.jhipster.config.JHipsterConstants;
import tech.jhipster.web.rest.errors.ExceptionTranslation;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause.ProblemDetailWithCauseBuilder;
import tech.jhipster.web.util.HeaderUtil;

/**
 * Controller advice to translate the server side exceptions to client-friendly json structures.
 * The error response follows RFC7807 - Problem Details for HTTP APIs (https://tools.ietf.org/html/rfc7807).
 */
@ControllerAdvice
public class ExceptionTranslator extends ResponseEntityExceptionHandler implements ExceptionTranslation {

    private static final String FIELD_ERRORS_KEY = "fieldErrors";
    private static final String MESSAGE_KEY = "message";
    private static final String PATH_KEY = "path";
    private static final boolean CASUAL_CHAIN_ENABLED = false;

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final Environment env;

    public ExceptionTranslator(Environment env) {
        this.env = env;
    }

    @ExceptionHandler
    @Override
    public Mono<ResponseEntity<Object>> handleAnyException(Throwable ex, ServerWebExchange request) {
        ProblemDetailWithCause pdCause = wrapAndCustomizeProblem(ex, request);
        return handleExceptionInternal((Exception) ex, pdCause, buildHeaders(ex), HttpStatusCode.valueOf(pdCause.getStatus()), request);
    }

    /**
     * Puts the failure-alert headers back on a {@link BadRequestAlertException}.
     *
     * <h2>Why {@link #handleAnyException} never gets the chance</h2>
     *
     * <p>{@link #handleAnyException} calls {@link #buildHeaders} and <b>never runs for this family</b>.
     * {@code BadRequestAlertException} extends {@link ErrorResponseException}, and
     * {@link ResponseEntityExceptionHandler#handleException} declares a handler for that which is
     * <em>more specific</em> than this advice's {@code @ExceptionHandler(Throwable)}, so Spring
     * dispatches there and answers with the exception's own headers — which are a freshly constructed,
     * permanently empty {@code HttpHeaders} ({@code ErrorResponseException:46}). {@code buildHeaders}
     * built a perfectly good pair of headers that nothing ever received. Backlog item 97, measured on
     * a real refused write through this stack before the fix:
     *
     * <pre>
     * [alert-header] refused write, emitted alert headers = []
     * </pre>
     *
     * <h2>Why an override rather than a second {@code @ExceptionHandler}</h2>
     *
     * <p>A {@code @ExceptionHandler(BadRequestAlertException.class)} on this advice would also work — a
     * subclass outranks its parent in {@code ExceptionHandlerMethodResolver}. It is <b>not</b> what
     * this does, because <em>competing for dispatch is what broke this in the first place</em>: the
     * advice claimed {@code Throwable} and quietly lost to a framework handler it did not know about.
     * {@link ResponseEntityExceptionHandler#handleException} is {@code final}, so overriding this
     * protected seam is the framework's own answer: one dispatch path rather than two, and any future
     * {@code ErrorResponseException} that {@code buildHeaders} learns about is covered without a third
     * handler.
     *
     * <h2>The reactive seam is the same shape as the servlet one, which was not a given</h2>
     *
     * <p>This is the fix {@code hc-admin-service} took for the same defect (backlog item 91), and that
     * repository is Spring MVC while this one is WebFlux — a different
     * {@code ResponseEntityExceptionHandler} in a different package with a different surface. It was
     * read before being copied:
     * {@code org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler}
     * (spring-webflux 7.0.8) declares {@code handleException} {@code final}, dispatches
     * {@code ErrorResponseException} to {@code handleErrorResponseException(ex, ex.getHeaders(),
     * ex.getStatusCode(), exchange)}, and that method delegates to {@code handleExceptionInternal}.
     * Same seam, same contract, {@code Mono} and {@code ServerWebExchange} in place of the servlet
     * types.
     *
     * <h2>Two things this deliberately does not do</h2>
     *
     * <p><b>It does not touch the body.</b> {@code BadRequestAlertException} sets {@code message} and
     * {@code params} on its own {@code ProblemDetail} and those are unchanged. Once the headers arrive
     * the console takes its {@code errorKey} branch and builds {@code { entityName }} itself from the
     * {@code -params} header, so the body's {@code params} goes back to being unread there. That is
     * the intended outcome, not a regression.
     *
     * <p><b>It does not go through {@link #handleAnyException}.</b> Routing this family there would
     * re-derive a problem detail the exception already carries, and would put back the two-handler
     * race this override exists to avoid.
     *
     * <h2>One visible side effect</h2>
     *
     * <p>{@code HeaderUtil.createFailureAlert} opens with {@code log.error("Entity processing failed,
     * {}", defaultMessage)}, so every refused write now logs at ERROR where it logged nothing before.
     *
     * <p><b>And it logs more than the string {@code buildHeaders} passes it.</b> That argument is
     * {@code ex.getMessage()}, which on an {@code ErrorResponseException} is the status plus the whole
     * rendered {@code ProblemDetail} — observed on this repository's own suite:
     *
     * <pre>
     * Entity processing failed, 400 BAD_REQUEST, ProblemDetailWithCause[type='…/problem/email-already-used',
     *   title='Email is already in use!', status=400, detail='null', instance='null',
     *   properties='{message=error.emailexists, params=userManagement}']
     * </pre>
     *
     * <p>So the thing to read before adding a {@code BadRequestAlertException} is not only its default
     * message but everything on its problem detail — {@code title}, {@code detail} and every property.
     * All four raise sites in this repository were read before accepting the change
     * ({@code AuthorityResource}, {@code UserResource}, and the {@code EmailAlreadyUsedException} /
     * {@code LoginAlreadyUsedException} subclasses) and every field on all of them is a constant
     * naming no login, address or id. Note that the log statement itself lives in the
     * jhipster-framework jar, where no sweep in this repository can see it: a future detail
     * interpolating a subject would be leaked through a line nothing here reads.
     */
    @Override
    protected Mono<ResponseEntity<Object>> handleErrorResponseException(
        ErrorResponseException ex,
        HttpHeaders headers,
        HttpStatusCode statusCode,
        ServerWebExchange request
    ) {
        HttpHeaders alertHeaders = buildHeaders(ex);
        if (alertHeaders == null) return super.handleErrorResponseException(ex, headers, statusCode, request);

        // A copy rather than an addition to the exception's own headers: the alert headers belong to
        // this response, not to the exception instance, and the parameter is documented @Nullable.
        HttpHeaders merged = new HttpHeaders();
        if (headers != null) merged.putAll(headers);
        merged.putAll(alertHeaders);
        return super.handleErrorResponseException(ex, merged, statusCode, request);
    }

    @Nullable
    @Override
    protected Mono<ResponseEntity<Object>> handleExceptionInternal(
        Exception ex,
        @Nullable Object body,
        HttpHeaders headers,
        HttpStatusCode statusCode,
        ServerWebExchange request
    ) {
        body = body == null ? wrapAndCustomizeProblem((Throwable) ex, (ServerWebExchange) request) : body;
        if (request.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        return Mono.just(
            new ResponseEntity<>(body, updateContentType(headers), HttpStatusCode.valueOf(((ProblemDetailWithCause) body).getStatus()))
        );
    }

    protected ProblemDetailWithCause wrapAndCustomizeProblem(Throwable ex, ServerWebExchange request) {
        return customizeProblem(getProblemDetailWithCause(ex), ex, request);
    }

    private ProblemDetailWithCause getProblemDetailWithCause(Throwable ex) {
        if (
            ex instanceof net.jojoaddison.service.UsernameAlreadyUsedException
        ) return (ProblemDetailWithCause) new LoginAlreadyUsedException().getBody();
        if (
            ex instanceof net.jojoaddison.service.EmailAlreadyUsedException
        ) return (ProblemDetailWithCause) new EmailAlreadyUsedException().getBody();
        if (
            ex instanceof net.jojoaddison.service.InvalidPasswordException
        ) return (ProblemDetailWithCause) new InvalidPasswordException().getBody();

        if (ex instanceof AuthenticationException) {
            // Ensure no information about existing users is revealed via failed authentication attempts
            return ProblemDetailWithCauseBuilder.instance()
                .withStatus(toStatus(ex).value())
                .withTitle("Unauthorized")
                .withDetail("Invalid credentials")
                .build();
        }
        if (
            ex instanceof ErrorResponseException exp && exp.getBody() instanceof ProblemDetailWithCause problemDetailWithCause
        ) return problemDetailWithCause;
        return ProblemDetailWithCauseBuilder.instance().withStatus(toStatus(ex).value()).build();
    }

    protected ProblemDetailWithCause customizeProblem(ProblemDetailWithCause problem, Throwable err, ServerWebExchange request) {
        if (problem.getStatus() <= 0) problem.setStatus(toStatus(err));

        if (problem.getType() == null || problem.getType().equals(URI.create("about:blank"))) problem.setType(getMappedType(err));

        // higher precedence to Custom/ResponseStatus types
        String title = extractTitle(err, problem.getStatus());
        String problemTitle = problem.getTitle();
        if (problemTitle == null || !problemTitle.equals(title)) {
            problem.setTitle(title);
        }

        if (problem.getDetail() == null) {
            // higher precedence to cause
            problem.setDetail(getCustomizedErrorDetails(err));
        }

        Map<String, Object> problemProperties = problem.getProperties();
        if (problemProperties == null || !problemProperties.containsKey(MESSAGE_KEY)) problem.setProperty(
            MESSAGE_KEY,
            getMappedMessageKey(err) != null ? getMappedMessageKey(err) : "error.http." + problem.getStatus()
        );

        if (problemProperties == null || !problemProperties.containsKey(PATH_KEY)) problem.setProperty(PATH_KEY, getPathValue(request));

        if (
            err instanceof WebExchangeBindException fieldException &&
            (problemProperties == null || !problemProperties.containsKey(FIELD_ERRORS_KEY))
        ) problem.setProperty(FIELD_ERRORS_KEY, getFieldErrors(fieldException));

        problem.setCause(buildCause(err.getCause(), request).orElse(null));

        return problem;
    }

    private String extractTitle(Throwable err, int statusCode) {
        return getCustomizedTitle(err) != null ? getCustomizedTitle(err) : extractTitleForResponseStatus(err, statusCode);
    }

    private List<FieldErrorVM> getFieldErrors(WebExchangeBindException ex) {
        return ex
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .map(f ->
                new FieldErrorVM(
                    f.getObjectName().replaceFirst("DTO$", ""),
                    f.getField(),
                    StringUtils.isNotBlank(f.getDefaultMessage()) ? f.getDefaultMessage() : f.getCode()
                )
            )
            .toList();
    }

    private String extractTitleForResponseStatus(Throwable err, int statusCode) {
        ResponseStatus specialStatus = extractResponseStatus(err);
        return specialStatus == null ? HttpStatus.valueOf(statusCode).getReasonPhrase() : specialStatus.reason();
    }

    private HttpStatus toStatus(final Throwable throwable) {
        // Let the ErrorResponse take this responsibility
        if (throwable instanceof ErrorResponse err) return HttpStatus.valueOf(err.getBody().getStatus());

        return Optional.ofNullable(getMappedStatus(throwable)).orElse(
            Optional.ofNullable(resolveResponseStatus(throwable)).map(ResponseStatus::value).orElse(HttpStatus.INTERNAL_SERVER_ERROR)
        );
    }

    private ResponseStatus extractResponseStatus(final Throwable throwable) {
        return Optional.ofNullable(resolveResponseStatus(throwable)).orElse(null);
    }

    private ResponseStatus resolveResponseStatus(final Throwable type) {
        final ResponseStatus candidate = findMergedAnnotation(type.getClass(), ResponseStatus.class);
        return candidate == null && type.getCause() != null ? resolveResponseStatus(type.getCause()) : candidate;
    }

    private URI getMappedType(Throwable err) {
        if (err instanceof MethodArgumentNotValidException) return ErrorConstants.CONSTRAINT_VIOLATION_TYPE;
        return ErrorConstants.DEFAULT_TYPE;
    }

    private String getMappedMessageKey(Throwable err) {
        if (err instanceof MethodArgumentNotValidException) {
            return ErrorConstants.ERR_VALIDATION;
        } else if (err instanceof ConcurrencyFailureException || err.getCause() instanceof ConcurrencyFailureException) {
            return ErrorConstants.ERR_CONCURRENCY_FAILURE;
        } else if (err instanceof WebExchangeBindException) {
            return ErrorConstants.ERR_VALIDATION;
        }
        return null;
    }

    private String getCustomizedTitle(Throwable err) {
        if (err instanceof MethodArgumentNotValidException) return "Method argument not valid";
        return null;
    }

    private String getCustomizedErrorDetails(Throwable err) {
        Collection<String> activeProfiles = Arrays.asList(env.getActiveProfiles());
        if (activeProfiles.contains(JHipsterConstants.SPRING_PROFILE_PRODUCTION)) {
            if (err instanceof HttpMessageConversionException) return "Unable to convert http message";
            if (err instanceof DataAccessException) return "Failure during data access";
            if (containsPackageName(err.getMessage())) return "Unexpected runtime exception";
        }
        return err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
    }

    private HttpStatus getMappedStatus(Throwable err) {
        // Where we disagree with Spring defaults
        if (err instanceof AccessDeniedException) return HttpStatus.FORBIDDEN;
        if (err instanceof ConcurrencyFailureException) return HttpStatus.CONFLICT;
        if (err instanceof BadCredentialsException) return HttpStatus.UNAUTHORIZED;
        if (err instanceof UsernameNotFoundException) return HttpStatus.UNAUTHORIZED;
        // Every other AuthenticationException, and UserNotActivatedException is the one that
        // matters: it extends AuthenticationException but neither of the two above, so it fell
        // through to null and became a 500. Signing in with a deactivated account answered
        // "500 Internal Server Error" while the body it was given already read
        // "Unauthorized / Invalid credentials" — the ProblemDetail branch recognised it as an
        // authentication failure and only the status disagreed.
        //
        // A 500 there is worse than untidy. It is distinguishable from the 401 a wrong password
        // gets, so it tells an unauthenticated caller that the account exists and is merely
        // switched off — the information the "Invalid credentials" wording exists to withhold.
        if (err instanceof AuthenticationException) return HttpStatus.UNAUTHORIZED;
        return null;
    }

    private URI getPathValue(ServerWebExchange request) {
        if (request == null) return URI.create("about:blank");
        return request.getRequest().getURI();
    }

    private HttpHeaders buildHeaders(Throwable err) {
        return err instanceof BadRequestAlertException badRequestAlertException
            ? HeaderUtil.createFailureAlert(
                  applicationName,
                  true,
                  badRequestAlertException.getEntityName(),
                  badRequestAlertException.getErrorKey(),
                  badRequestAlertException.getMessage()
              )
            : null;
    }

    private HttpHeaders updateContentType(HttpHeaders headers) {
        if (headers == null) {
            headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        return headers;
    }

    public Optional<ProblemDetailWithCause> buildCause(final Throwable throwable, ServerWebExchange request) {
        if (throwable != null && isCasualChainEnabled()) {
            return Optional.of(customizeProblem(getProblemDetailWithCause(throwable), throwable, request));
        }
        return Optional.ofNullable(null);
    }

    private boolean isCasualChainEnabled() {
        // Customize as per the needs
        return CASUAL_CHAIN_ENABLED;
    }

    private boolean containsPackageName(String message) {
        // This list is for sure not complete
        return StringUtils.containsAny(message, "org.", "java.", "net.", "jakarta.", "javax.", "com.", "io.", "de.", "net.jojoaddison");
    }
}
