package net.jojoaddison.repository;

import net.jojoaddison.domain.LoginAttempt;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB reactive repository for the {@link LoginAttempt} document.
 *
 * <p><b>Insert only.</b> There is no derived read here on purpose: every read of this collection is
 * an aggregation over a time window, which a derived query method cannot express, and it goes
 * through {@code ReactiveMongoTemplate} in {@code AuthActivityService}. A {@code findByLogin} added
 * for convenience would be an unpaginated read of an attacker-controlled collection — the shape the
 * api's {@code PaginationIT} exists to keep out — so the absence is deliberate.
 */
@Repository
public interface LoginAttemptRepository extends ReactiveMongoRepository<LoginAttempt, String> {}
