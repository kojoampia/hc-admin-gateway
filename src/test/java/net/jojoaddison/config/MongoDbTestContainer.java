package net.jojoaddison.config;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

public class MongoDbTestContainer implements InitializingBean, DisposableBean {

    /* private final long memoryInBytes = Math.round(1024 * 1024 * 1024 * 0.6);
    private final long memorySwapInBytes = Math.round(1024 * 1024 * 1024 * 0.8);
    private final long nanoCpu = Math.round(1_000_000_000L * 0.1); */
    private static final Logger log = LoggerFactory.getLogger(MongoDbTestContainer.class);

    private static final String IMAGE = "mongo:7.0.6";

    /**
     * Three attempts, three seconds apart, shared by every test class in this JVM.
     *
     * <p>Static because the budget is a fact about the machine rather than about a class, and because
     * the factory that creates this bean assigns its static field only after a successful start — so a
     * failure leaves the next class free to build a container of its own, and without a shared budget
     * every context-booting class pays the full retry. {@link TestContainerStartBudget} argues both
     * halves, and the same fixture is in hc-admin-service.
     */
    private static final TestContainerStartBudget START_BUDGET = new TestContainerStartBudget(3, 3_000L);

    private MongoDBContainer mongodbContainer;

    @Override
    public void destroy() {
        if (null != mongodbContainer && mongodbContainer.isRunning()) {
            mongodbContainer.stop();
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (null == mongodbContainer) {
            mongodbContainer = buildContainer();
        }
        START_BUDGET.start(IMAGE, this::startIfNotRunning, this::discardContainer);
    }

    public MongoDBContainer getMongoDBContainer() {
        return mongodbContainer;
    }

    /**
     * Builds a container but does not start it. Kept separate from {@link #afterPropertiesSet()} on
     * purpose: the retry rebuilds through here rather than by calling {@code afterPropertiesSet()}
     * again, which would nest one budget inside another.
     */
    private MongoDBContainer buildContainer() {
        return (
            new MongoDBContainer(IMAGE)
                .withTmpFs(Map.of("/testtmpfs", "rw"))
                /* .withCommand(
                    "--nojournal --wiredTigerCacheSizeGB 0.25 --wiredTigerCollectionBlockCompressor none --slowOpSampleRate 0 --setParameter ttlMonitorEnabled=false --setParameter diagnosticDataCollectionEnabled=false --setParameter logicalSessionRefreshMillis=6000000 --setParameter enableFlowControl=false --setParameter oplogFetcherUsesExhaust=false --setParameter disableResumableRangeDeleter=true --setParameter enableShardedIndexConsistencyCheck=false --setParameter enableFinerGrainedCatalogCacheRefresh=false --setParameter readHedgingMode=off --setParameter loadRoutingTableOnStartup=false --setParameter rangeDeleterBatchDelayMS=2000000 --setParameter skipShardingConfigurationChecks=true --setParameter syncdelay=3600"
                )
                .withCreateContainerCmdModifier(cmd ->
                    cmd.getHostConfig().withMemory(memoryInBytes).withMemorySwap(memorySwapInBytes).withNanoCPUs(nanoCpu)
                ) */
                .withLogConsumer(new Slf4jLogConsumer(log))
                // Silently ignored unless the machine opts in — the container log says "Reuse was
                // requested but the environment does not support the reuse of containers" until
                // testcontainers.reuse.enable=true is in ~/.testcontainers.properties or
                // TESTCONTAINERS_REUSE_ENABLE=true is in the environment. It is a per-developer setting
                // and there is nothing a commit can do about it, which is why it is documented in
                // AGENTS.md rather than relied on here. Leave it off in CI: a reused container carries
                // state between runs and is not reaped.
                .withReuse(true)
        );
    }

    private void startIfNotRunning() {
        if (!mongodbContainer.isRunning()) {
            mongodbContainer.start();
        }
    }

    private void discardContainer() {
        try {
            mongodbContainer.stop();
            // RuntimeException and not Throwable, deliberately. An Error here — OutOfMemoryError is the
            // realistic one on the saturated machine this path exists for — means the JVM is in no state
            // to be building more containers, and swallowing it to carry on would turn the clearest
            // possible failure into another container start. It propagates.
        } catch (RuntimeException e) {
            log.warn("Could not stop the MongoDB test container that failed to start; building a fresh one anyway.", e);
        }
        mongodbContainer = buildContainer();
    }
}
