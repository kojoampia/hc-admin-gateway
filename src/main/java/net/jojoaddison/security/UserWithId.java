package net.jojoaddison.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * Spring Security's {@link User} plus the database id of the account behind it.
 *
 * <p>Spring's own {@code User} carries a username and nothing else identifying, so by the time
 * {@code AuthenticateController.createToken} runs, the id of the authenticated account is gone —
 * the {@code Authentication} holds a login and the {@code User} document that was just read to
 * check the password has been discarded.
 *
 * <p>That mattered because the downstream service's domain documents reference accounts <em>by
 * id</em>: the seed data puts {@code a0eebc99-…-a11} in {@code createdBy}, and CLAUDE.md names those
 * ids a cross-service contract with hc-patient-ms and hc-professional-service. The api runs with
 * {@code skipUserManagement: true} and has no route to this service's user collection, so unless the
 * id travels on the token it cannot record who changed a record without inventing a second
 * identifier space.
 *
 * <p>Carrying it here rather than re-reading the user in {@code createToken} keeps authentication to
 * the one query it already makes.
 */
public class UserWithId extends User {

    private static final long serialVersionUID = 1L;

    private final String id;

    public UserWithId(String id, String username, String password, Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
