package net.jojoaddison.service.dto;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * What {@code GET /api/auth-activity} answers: this gateway's own accounts, and what has happened at
 * its sign-in endpoint over a window.
 *
 * <h2>⚠ Two figures, two different populations, and the screen must not blur them</h2>
 *
 * <p>{@link GatewayAccounts} counts <b>console staff accounts on this gateway</b> — the
 * administrators and operators an administrator created through {@code /api/admin/users}. It is a
 * small number and is meant to be: there is no self-registration on this stack, deliberately
 * ({@code SecurityConfiguration:79} — chained with the old blanket {@code /api/**} rule,
 * self-registration granted {@code ROLE_USER} to anyone on the internet and that reached every admin
 * record).
 *
 * <p>It is emphatically <b>not</b> "registrations" in the sense a BridgeCare operator means. Those
 * happen on hc-patient and hc-professional, arrive here as domain events, and are counted by
 * hc-admin-service from {@code directory_link} — {@code GET /api/directory-links/registrations},
 * which the console renders as the headline beside this. Backlog item 75 asked for both and this
 * serves one of them; a reader who sees only this number and reads it as the estate's registrations
 * will conclude the platform has six users. The console labels the scope of each card in words for
 * that reason, and {@code auth-activity.spec.ts} asserts it does.
 *
 * <h2>Every figure is over the window, and the window is in the response</h2>
 *
 * <p>Except {@link GatewayAccounts}, which is a standing total of a collection nothing expires —
 * "how many accounts exist" has no window. Everything derived from {@code login_attempt} does, and
 * {@link #windowDays} says which. It travels with the numbers rather than being assumed by the
 * caption, which is the convention hc-admin-service's {@code Uptime(percent, windowDays)} set after a
 * card claiming a 30-day figure was computed over 15 days of retention.
 *
 * <p><b>There is deliberately no "total attempts ever".</b> The store is a lower bound rather than a
 * ledger: {@code LoginAttemptRecorder} never fails a login to save a row, so a MongoDB that is slow
 * or down loses attempts silently as far as the figures go (loudly as far as the log goes). A
 * lifetime total invites being read as an audit trail, which this is not; a window shorter than
 * retention is exactly as true as the data behind it.
 */
public record AuthActivityDTO(GatewayAccounts accounts, LoginTotals logins, int windowDays, int retentionDays) implements Serializable {
    /**
     * Console staff accounts on this gateway, by {@code User.activated}.
     *
     * <p>Two buckets and only two, which is correct <em>here</em> and would be a defect on the
     * estate-wide figure: {@code User.activated} is a primitive {@code boolean} with a {@code false}
     * default, so every account has an answer. hc-admin-service's {@code DirectoryLink.activated} is
     * a boxed {@code Boolean} precisely because a clinician known only from an {@code onboarding.state}
     * frame has no answer at all — see {@code RegistrationTotalsDTO} there, which has three.
     */
    public record GatewayAccounts(long activated, long notActivated) implements Serializable {}

    /**
     * Sign-in attempts over the window.
     *
     * @param succeeded a token was issued.
     * @param failed no token was issued, for any reason — see {@code LoginOutcome} for why the reason
     *     is not broken down.
     * @param daily one point per calendar day the window touches, oldest first, <b>including days on
     *     which nothing happened</b>. A series that omits empty days draws a quiet week as a straight
     *     line between two spikes, which is the opposite of what it means.
     *     <p><b>⚠ There are {@code windowDays + 1} points, not {@code windowDays}, and both end days
     *     are partial.</b> The window is {@code now - windowDays} to {@code now} — a span in hours,
     *     not a run of calendar days — so with a 30-day window it starts mid-morning 30 days ago and
     *     ends mid-morning today, touching 31 calendar dates. The first point counts only the part of
     *     that date inside the window and the last counts only the part of today so far, so <b>both
     *     will look low beside the days between them</b>, and the first is not a day on which sign-ins
     *     collapsed.
     *     <p>The alternative — drop the partial first point and ship exactly 30 — was rejected, and
     *     the reason is an invariant worth keeping: <b>the series sums to {@code succeeded} and
     *     {@code failed}</b>, because both are counted over the same {@code >= now - windowDays}
     *     filter. Dropping the point would leave a chart whose bars do not add up to the totals
     *     printed above them, which is the defect this estate's dashboard has already had twice, in
     *     exchange for a rounder number. A caption saying "last 30 days" over 31 bars is the smaller
     *     inaccuracy and it is stated here rather than left for somebody to count.
     * @param topFailedLogins the logins most often failed against, largest first, bounded by
     *     {@code application.auth-activity.top-failed-logins}. <b>This is the part of the response
     *     that carries {@code LoginAttempt}'s deliberate exception to item 43</b>, and it is the
     *     reason the whole endpoint is {@code ROLE_ADMIN} alone rather than admin-or-operator like
     *     every other read on the console. Empty when nothing has failed.
     */
    public record LoginTotals(
        long succeeded,
        long failed,
        List<DayCount> daily,
        List<FailedLogin> topFailedLogins
    ) implements Serializable {}

    /**
     * One day of the window.
     *
     * @param day the day in UTC. <b>UTC rather than the server's zone</b>, because the aggregation
     *     buckets on an {@code Instant} and a chart whose buckets and whose labels disagree about
     *     where a day starts is off by one for half the world without saying so.
     */
    public record DayCount(LocalDate day, long succeeded, long failed) implements Serializable {}

    /**
     * A login as it was entered, and how many times it failed within the window.
     *
     * <p>Not lower-cased, not normalised, not de-duplicated across casings — see
     * {@link net.jojoaddison.domain.LoginAttempt#getLogin()}. What is shown is what was sent.
     */
    public record FailedLogin(String login, long failures) implements Serializable {}
}
