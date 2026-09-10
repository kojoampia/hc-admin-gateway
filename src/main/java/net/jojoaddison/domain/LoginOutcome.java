package net.jojoaddison.domain;

/**
 * What happened when somebody tried to sign in.
 *
 * <p>Two values and deliberately not more. The reason a sign-in failed — an unknown login, a wrong
 * password, a deactivated account — is a real distinction and it is <b>not</b> recorded here: it
 * would turn the store into an oracle for which logins exist, readable by anyone who reaches the
 * screen, in exchange for a breakdown backlog item 75 did not ask for. {@code /api/authenticate}
 * itself refuses all three identically for the same reason (see
 * {@code AuthenticateControllerIT.aDeactivatedAccountIsRefused}), and a store that draws the
 * distinction the endpoint refuses to draw would undo that one layer down.
 */
public enum LoginOutcome {
    /** A token was issued. */
    SUCCEEDED,
    /** No token was issued, for any reason. */
    FAILED,
}
