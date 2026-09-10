package net.jojoaddison.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to Admin Gateway.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {

    // jhipster-needle-application-properties-property
    private final AuthActivity authActivity = new AuthActivity();

    // jhipster-needle-application-properties-property-getter
    public AuthActivity getAuthActivity() {
        return authActivity;
    }

    // jhipster-needle-application-properties-property-class

    /**
     * The two numbers behind {@code GET /api/auth-activity}, and the one relationship between them.
     *
     * <p>Both are properties rather than constants because they are the knobs an operator turns after
     * an incident — a longer window to see a campaign, a longer retention to keep the evidence — and
     * turning either means restarting a container, not editing Java.
     */
    public static class AuthActivity {

        /**
         * How long a {@code login_attempt} row is kept, in days, before MongoDB deletes it.
         *
         * <p>This is the fourth of {@link net.jojoaddison.domain.LoginAttempt}'s safeguards and it is
         * enforced by a TTL index rather than by anything that has to be run — see
         * {@code LoginAttemptIndexes}, including what happens to the index when this value changes.
         *
         * <p>Ninety days is the shortest span over which "this is unusual" is a statement about
         * anything: it covers a quarter, so a January baseline is still readable in March. It is
         * deliberately three times {@link #windowDays}, so the screen's window is never quietly
         * truncated by retention.
         */
        private int retentionDays = 90;

        /**
         * How far back {@code GET /api/auth-activity} counts, in days.
         *
         * <p><b>A window rather than a total, and the window travels with the figures.</b> "50 failed
         * logins" answers no operational question without a span attached, and a running total since
         * the beginning of the collection answers a different question every day as the collection
         * ages out from under it. {@code Uptime(percent, windowDays)} on the api's dashboard is the
         * established precedent for carrying the measured span beside the measurement so a caption
         * cannot drift from it.
         */
        private int windowDays = 30;

        /**
         * How many distinct logins the failed-login breakdown names.
         *
         * <p>Bounded because the list is a projection of an attacker-controlled field: without a
         * ceiling, a scan across ten thousand guessed logins is ten thousand rows in one response.
         * Ten is what fits a card and is enough to distinguish one account being hammered from a
         * broad sweep, which is the only question the list is there to answer.
         */
        private int topFailedLogins = 10;

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public int getWindowDays() {
            return windowDays;
        }

        public void setWindowDays(int windowDays) {
            this.windowDays = windowDays;
        }

        public int getTopFailedLogins() {
            return topFailedLogins;
        }

        public void setTopFailedLogins(int topFailedLogins) {
            this.topFailedLogins = topFailedLogins;
        }
    }
}
