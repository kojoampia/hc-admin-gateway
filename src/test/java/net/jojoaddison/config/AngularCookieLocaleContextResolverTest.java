package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import net.jojoaddison.config.LocaleConfiguration.AngularCookieLocaleContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * The cookie that decides which language the console is served in.
 *
 * <p>The largest block of untested logic in this application that is not security: 196 instructions
 * and 30 of 34 branches uncovered, across both the unit and integration phases.
 *
 * <p>All of that untested logic exists for one reason, and it is the reason this is worth a test at
 * all rather than dismissing as generated code. AngularJS's {@code $cookies} writes a string value
 * <b>wrapped in literal double quotes</b>, percent-encoded as {@code %22}. So the cookie is not
 * {@code en} but {@code %22en%22}, and every method here has to strip and re-add those quotes. Get
 * it wrong in one direction and the locale is parsed as the language tag {@code "en"} — with the
 * quotes — which is not a locale and silently falls back. Get it wrong in the other and the value
 * written back is one Angular cannot read.
 */
class AngularCookieLocaleContextResolverTest {

    private static final String COOKIE_NAME = "NG_TRANSLATE_LANG_KEY";

    private final AngularCookieLocaleContextResolver resolver = new AngularCookieLocaleContextResolver();

    private static MockServerWebExchange exchangeWithCookie(String value) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/").cookie(new HttpCookie(COOKIE_NAME, value)));
    }

    /** The quoted form is the one Angular actually writes. */
    @Test
    void readsALocaleFromTheQuotedCookieAngularWrites() {
        var context = resolver.resolveLocaleContext(exchangeWithCookie("%22fr%22"));

        assertThat(context.getLocale()).isEqualTo(Locale.FRENCH);
    }

    /** And an unquoted value still resolves, since not every writer of this cookie is Angular. */
    @Test
    void readsALocaleFromAnUnquotedCookie() {
        var context = resolver.resolveLocaleContext(exchangeWithCookie("fr"));

        assertThat(context.getLocale()).isEqualTo(Locale.FRENCH);
    }

    @Test
    void readsALanguageAndRegion() {
        var context = resolver.resolveLocaleContext(exchangeWithCookie("%22en-GB%22"));

        assertThat(context.getLocale()).isEqualTo(Locale.of("en", "GB"));
    }

    /**
     * No cookie is the first visit, and the resolver reports <b>no locale</b> — it does not consult
     * {@code Accept-Language}, even when the request carries one.
     *
     * <p>Written down because the opposite is the natural assumption, and it was mine until this
     * test failed: {@code parseLocaleCookieIfNecessary} sets the request attribute only when a
     * cookie yields a locale, so {@code getLocale()} returns {@code null} and the fallback happens
     * further out, in Spring's own default. Pinning it means a change that starts inventing a locale
     * here — or one that starts throwing on the null — is visible rather than silent.
     */
    @Test
    void reportsNoLocaleWhenTheCookieIsAbsent() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").acceptLanguageAsLocales(Locale.ITALIAN));

        assertThat(resolver.resolveLocaleContext(exchange).getLocale()).isNull();
    }

    /**
     * The cookie can carry a time zone after the locale, space-separated — a shape nothing in this
     * console writes today, but the parser supports it and the branch was uncovered.
     */
    @Test
    void readsATimeZoneCarriedAlongsideTheLocale() {
        var context = (org.springframework.context.i18n.TimeZoneAwareLocaleContext) resolver.resolveLocaleContext(
            exchangeWithCookie("%22fr Europe/Paris%22")
        );

        assertThat(context.getLocale()).isEqualTo(Locale.FRENCH);
        assertThat(context.getTimeZone()).isEqualTo(java.util.TimeZone.getTimeZone("Europe/Paris"));
    }

    /**
     * <b>The round trip is the point.</b> Writing a locale and reading it back has to yield the same
     * locale — that is the whole contract, and it is the one thing a quoting bug on either side
     * breaks. Asserting only the written string would pass with both sides wrong in the same way.
     */
    @Test
    void whatIsWrittenCanBeReadBack() {
        var writeExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        resolver.setLocaleContext(writeExchange, new SimpleLocaleContext(Locale.GERMAN));

        var written = writeExchange.getResponse().getCookies().getFirst(COOKIE_NAME);
        assertThat(written).as("a locale cookie should have been set").isNotNull();

        var readBack = resolver.resolveLocaleContext(exchangeWithCookie(written.getValue()));
        assertThat(readBack.getLocale()).isEqualTo(Locale.GERMAN);
    }

    /** Written in the quoted form, because that is what Angular's $cookies expects to read. */
    @Test
    void writesTheCookieInTheFormAngularExpects() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        resolver.setLocaleContext(exchange, new SimpleLocaleContext(Locale.GERMAN));

        var cookie = exchange.getResponse().getCookies().getFirst(COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).startsWith("%22").endsWith("%22").contains("de");
    }

    /** Clearing the locale must not leave a cookie that reads as the language tag "null". */
    @Test
    void clearingTheLocaleDoesNotWriteALiteralNull() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        resolver.setLocaleContext(exchange, null);

        var cookie = exchange.getResponse().getCookies().getFirst(COOKIE_NAME);
        if (cookie != null) {
            assertThat(cookie.getValue()).doesNotContain("null");
        }
    }
}
