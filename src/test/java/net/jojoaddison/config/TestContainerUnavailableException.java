package net.jojoaddison.config;

import java.io.Serial;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Locale;

/**
 * Thrown when a test container could not be started, carrying the diagnosis in its own message.
 *
 * <p>It exists because the failure a reader actually sees names nothing they can act on. A container
 * that does not start fails the context, and every test in every class that shares that context then
 * reports {@code IllegalState ApplicationContext failure threshold (1) exceeded: skipping repeated
 * attempt to load context for [WebMergedContextConfiguration@...]} followed by two kilobytes of
 * merged-configuration dump — no container, no image, no cause, and a list of context customizers that
 * reads like a stack trace but is not one. The real cause sits four {@code Caused by} levels down as
 * {@code ReplicaSetInitializationException: A single node replica set was not initialized in a set
 * timeout: 60 attempts}, which is itself only meaningful to somebody who already knows the answer.
 *
 * <p>Measured in hc-admin-service at load ~47 across 16 CPUs on 2026-09-09: three integration test
 * classes, 43 errors, and not one of the 43 messages named Mongo. That is the shape backlog item 17 is
 * about — in this repository it has sent readers to {@code TokenAuthenticationIT} and
 * {@code TokenAuthenticationSecurityMetersIT} for changes that touch neither, because whichever class
 * boots the first context is the one that pays the container start and absorbs its failure.
 *
 * <p>So the message is the fix. It names the image, the attempts, the load average at the moment of
 * the failure, the window that was missed and why it cannot be widened, what to do next, and where to
 * read. The container's own exception is kept as the cause, so nothing is hidden.
 */
public class TestContainerUnavailableException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    private TestContainerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The container was tried {@code attempts} times in this run and never came up.
     */
    static TestContainerUnavailableException exhausted(String image, int attempts, Throwable cause) {
        return new TestContainerUnavailableException(
            "The " +
                image +
                " test container did not start in " +
                attempts +
                " attempts, and no integration test in this run can boot a context without it. This is " +
                "almost never a defect in the code under test: " +
                machineLoad() +
                " at the moment of the failure. Testcontainers waits sixty seconds for the image to log " +
                "that it is ready and then gives a single-node replica set six seconds to elect a primary " +
                "(AWAIT_INIT_REPLICA_SET_ATTEMPTS = 60 attempts 100 ms apart, a private constant with no " +
                "property behind it), and a saturated machine misses one or both. Every remaining test " +
                "class in this run will now fail immediately with this same diagnosis rather than spend " +
                "the same minutes rediscovering it. Re-run on a quiet box; setting " +
                "testcontainers.reuse.enable=true in ~/.testcontainers.properties keeps a started " +
                "container between runs and largely stops this happening. Read docs/backlog.md item 17 " +
                "before looking for a regression. The container's own failure is attached as the cause.",
            cause
        );
    }

    /**
     * An earlier test class in this run already spent the budget, so nothing was asked of Docker here.
     */
    static TestContainerUnavailableException budgetAlreadySpent(String image, int attempts, Throwable cause) {
        return new TestContainerUnavailableException(
            "The " +
                image +
                " test container is unavailable on this machine and this run has stopped trying: an " +
                "earlier test class already spent the " +
                attempts +
                "-attempt budget on it, so nothing was asked of Docker for this class and it failed at " +
                "once. " +
                capitalise(machineLoad()) +
                " now. This is not a defect in the code under test — read the failure attached as the " +
                "cause, and docs/backlog.md item 17, before looking for a regression.",
            cause
        );
    }

    /**
     * The load average and CPU count, which is the single most useful fact about a failure of this kind
     * and the one nothing else in the output reports. {@code getSystemLoadAverage()} answers -1 on
     * platforms that do not have one, and saying so is better than printing a negative load.
     */
    static String machineLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        int cpus = os.getAvailableProcessors();
        return load < 0
            ? "the load average is not readable on this platform, which has " + cpus + " CPUs"
            : String.format(Locale.ROOT, "the load average was %.2f across %d CPUs", load, cpus);
    }

    private static String capitalise(String sentence) {
        return sentence.substring(0, 1).toUpperCase(Locale.ROOT) + sentence.substring(1);
    }
}
