/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.builder;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_CONFIG_FILE_NAME;
import static com.swirlds.platform.builder.PlatformBuildConstants.DEFAULT_SETTINGS_FILE_NAME;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.doStaticSetup;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;
import static com.swirlds.platform.config.internal.PlatformConfigUtils.checkConfiguration;
import static com.swirlds.platform.crypto.CryptoStatic.initNodeSecurity;
import static com.swirlds.platform.event.preconsensus.PcesUtilities.getDatabaseDirectory;
import static com.swirlds.platform.state.signed.StartupStateUtils.getInitialState;
import static com.swirlds.platform.util.BootstrapUtils.checkNodesToRun;
import static com.swirlds.platform.util.BootstrapUtils.detectSoftwareUpgrade;

import com.swirlds.base.time.Time;
import com.swirlds.common.concurrent.ExecutorFactory;
import com.swirlds.common.context.DefaultPlatformContext;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyFactory;
import com.swirlds.common.crypto.CryptographyHolder;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.wiring.WiringConfig;
import com.swirlds.common.wiring.model.WiringModel;
import com.swirlds.common.wiring.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.ParameterProvider;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.gossip.DefaultIntakeEventCounter;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.pool.TransactionPoolNexus;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.state.MerkleRoot;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.address.AddressBookInitializer;
import com.swirlds.platform.state.iss.IssScratchpad;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.StaticSoftwareVersion;
import com.swirlds.platform.system.SwirldState;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.util.BootstrapUtils;
import com.swirlds.platform.util.RandomBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builds a {@link SwirldsPlatform} instance.
 */
public final class PlatformBuilder {

    private static final Logger logger = LogManager.getLogger(PlatformBuilder.class);

    private final String appName;
    private final SoftwareVersion softwareVersion;
    private final Supplier<SwirldState> genesisStateBuilder;
    private final NodeId selfId;
    private final String swirldName;

    private Configuration configuration;
    private Cryptography cryptography;
    private Metrics metrics;
    private Time time;
    private FileSystemManager fileSystemManager;
    private RecycleBin recycleBin;
    private ExecutorFactory executorFactory;

    private static final UncaughtExceptionHandler DEFAULT_UNCAUGHT_EXCEPTION_HANDLER =
            (t, e) -> logger.error(EXCEPTION.getMarker(), "Uncaught exception on thread {}: {}", t, e);

    /**
     * An address book that is used to bootstrap the system. Traditionally read from config.txt.
     */
    private AddressBook bootstrapAddressBook;

    /**
     * This node's cryptographic keys.
     */
    private KeysAndCerts keysAndCerts;

    /**
     * The path to the configuration file (i.e. the file with the address book).
     */
    private Path configPath = getAbsolutePath(DEFAULT_CONFIG_FILE_NAME);

    /**
     * The wiring model to use for this platform.
     */
    private WiringModel model;

    /**
     * The source of non-cryptographic randomness for this platform.
     */
    private RandomBuilder randomBuilder;

    private Consumer<GossipEvent> preconsensusEventConsumer;
    private Consumer<ConsensusSnapshot> snapshotOverrideConsumer;
    private Consumer<GossipEvent> staleEventConsumer;

    /**
     * False if this builder has not yet been used to build a platform (or platform component builder), true if it has.
     */
    private boolean used;

    /**
     * Create a new platform builder.
     *
     * @param appName             the name of the application, currently used for deciding where to store states on
     *                            disk
     * @param swirldName          the name of the swirld, currently used for deciding where to store states on disk
     * @param selfId              the ID of this node
     * @param softwareVersion     the software version of the application
     * @param genesisStateBuilder a supplier that will be called to create the genesis state, if necessary
     */
    @NonNull
    public static PlatformBuilder create(
            @NonNull final String appName,
            @NonNull final String swirldName,
            @NonNull final SoftwareVersion softwareVersion,
            @NonNull final Supplier<SwirldState> genesisStateBuilder,
            @NonNull final NodeId selfId) {
        return new PlatformBuilder(appName, swirldName, softwareVersion, genesisStateBuilder, selfId);
    }

    /**
     * Constructor.
     *
     * @param appName             the name of the application, currently used for deciding where to store states on
     *                            disk
     * @param swirldName          the name of the swirld, currently used for deciding where to store states on disk
     * @param selfId              the ID of this node
     * @param softwareVersion     the software version of the application
     * @param genesisStateBuilder a supplier that will be called to create the genesis state, if necessary
     */
    private PlatformBuilder(
            @NonNull final String appName,
            @NonNull final String swirldName,
            @NonNull final SoftwareVersion softwareVersion,
            @NonNull final Supplier<SwirldState> genesisStateBuilder,
            @NonNull final NodeId selfId) {

        this.appName = Objects.requireNonNull(appName);
        this.swirldName = Objects.requireNonNull(swirldName);
        this.softwareVersion = Objects.requireNonNull(softwareVersion);
        this.genesisStateBuilder = Objects.requireNonNull(genesisStateBuilder);
        this.selfId = Objects.requireNonNull(selfId);

        StaticSoftwareVersion.setSoftwareVersion(softwareVersion);
    }

    /**
     * Provide a configuration to use for the platform. If not provided then default configuration is used.
     * <p>
     * Note that any configuration provided here must have the platform configuration properly registered.
     *
     * @param configuration the configuration to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withConfiguration(@NonNull final Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration);
        checkConfiguration(configuration);
        return this;
    }

    /**
     * Provide the cryptography to use for this platform. If not provided then the default cryptography is used.
     *
     * @param cryptography the cryptography to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withCryptography(@NonNull final Cryptography cryptography) {
        this.cryptography = Objects.requireNonNull(cryptography);
        return this;
    }

    /**
     * Provide the metrics to use for this platform. If not provided then default metrics are created.
     *
     * @param metrics the metrics to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withMetrics(@NonNull final Metrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        return this;
    }

    /**
     * Provide the time to use for this platform. If not provided then the default wall clock time is used.
     *
     * @param time the time to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withTime(@NonNull final Time time) {
        this.time = Objects.requireNonNull(time);
        return this;
    }

    /**
     * Provide the file system manager to use for this platform. If not provided then the default file system manager is
     * used.
     *
     * @param fileSystemManager the file system manager to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withFileSystemManager(@NonNull final FileSystemManager fileSystemManager) {
        this.fileSystemManager = Objects.requireNonNull(fileSystemManager);
        return this;
    }

    /**
     * Provide the recycle bin to use for this platform. If not provided then the default recycle bin is used.
     *
     * @param recycleBin the recycle bin to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withRecycleBin(@NonNull final RecycleBin recycleBin) {
        this.recycleBin = Objects.requireNonNull(recycleBin);
        return this;
    }

    /**
     * Provide the executor factory to use for this platform. If not provided then the default executor factory is
     * used.
     *
     * @param executorFactory the executor factory to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withExecutorFactory(@NonNull final ExecutorFactory executorFactory) {
        this.executorFactory = Objects.requireNonNull(executorFactory);
        return this;
    }

    /**
     * Provide the platform with the class ID of the previous software version. Needed at migration boundaries if the
     * class ID of the software version has changed.
     *
     * @param previousSoftwareVersionClassId the class ID of the previous software version
     * @return this
     */
    @NonNull
    public PlatformBuilder withPreviousSoftwareVersionClassId(final long previousSoftwareVersionClassId) {
        throwIfAlreadyUsed();
        final Set<Long> softwareVersions = new HashSet<>();
        softwareVersions.add(softwareVersion.getClassId());
        softwareVersions.add(previousSoftwareVersionClassId);
        StaticSoftwareVersion.setSoftwareVersion(softwareVersions);
        return this;
    }

    /**
     * Registers a callback that is called for each valid non-ancient preconsensus event in topological order (i.e.
     * after each event exits the orphan buffer). Useful for scenarios where access to this internal stream of events is
     * useful (e.g. UI hashgraph visualizers).
     *
     * <p>
     * Among all callbacks in the following list, it is guaranteed that callbacks will not be called concurrently, and
     * that there will be a happens-before relationship between each of the callbacks.
     *
     * <ul>
     *     <li>{@link #withPreconsensusEventCallback(Consumer)} (i.e. this callback)</li>
     *     <li>{@link #withConsensusSnapshotOverrideCallback(Consumer)}</li>
     * </ul>
     *
     * @param preconsensusEventConsumer the callback to register
     * @return this
     */
    @NonNull
    public PlatformBuilder withPreconsensusEventCallback(
            @NonNull final Consumer<GossipEvent> preconsensusEventConsumer) {
        throwIfAlreadyUsed();
        this.preconsensusEventConsumer = Objects.requireNonNull(preconsensusEventConsumer);
        return this;
    }

    /**
     * Registers a callback that is called when the consensus snapshot is specified by an out of band operation (i.e.
     * restart or reconnect). Useful for scenarios where access to this internal stream of data is useful (e.g. UI
     * hashgraph visualizers).
     *
     * <p>
     * Among all callbacks in the following list, it is guaranteed that callbacks will not be called concurrently, and
     * that there will be a happens-before relationship between each of the callbacks.
     *
     * <ul>
     *     <li>{@link #withPreconsensusEventCallback(Consumer)}</li>
     *     <li>{@link #withConsensusSnapshotOverrideCallback(Consumer)} (i.e. this callback)</li>
     * </ul>
     *
     * @return
     */
    @NonNull
    public PlatformBuilder withConsensusSnapshotOverrideCallback(
            @NonNull final Consumer<ConsensusSnapshot> snapshotOverrideConsumer) {
        throwIfAlreadyUsed();
        this.snapshotOverrideConsumer = Objects.requireNonNull(snapshotOverrideConsumer);
        return this;
    }

    /**
     * Register a callback that is called when a stale self event is detected (i.e. an event that will never reach
     * consensus). Depending on the use case, it may be a good idea to resubmit the transactions in the stale event.
     * <p>
     * Stale event detection is guaranteed to catch all stale self events as long as the node remains online. However,
     * if the node restarts or reconnects, any event that went stale "in the gap" may not be detected.
     *
     * @param staleEventConsumer the callback to register
     * @return this
     */
    @NonNull
    public PlatformBuilder withStaleEventCallback(@NonNull final Consumer<GossipEvent> staleEventConsumer) {
        throwIfAlreadyUsed();
        this.staleEventConsumer = Objects.requireNonNull(staleEventConsumer);
        return this;
    }

    /**
     * Provide the address book to use for bootstrapping the system. If not provided then the address book is read from
     * the config.txt file.
     *
     * @param bootstrapAddressBook the address book to use for bootstrapping
     * @return this
     */
    @NonNull
    public PlatformBuilder withBootstrapAddressBook(@NonNull final AddressBook bootstrapAddressBook) {
        throwIfAlreadyUsed();
        this.bootstrapAddressBook = Objects.requireNonNull(bootstrapAddressBook);
        return this;
    }

    /**
     * Provide the cryptographic keys to use for this node.
     *
     * @param keysAndCerts the cryptographic keys to use
     * @return this
     */
    @NonNull
    public PlatformBuilder withKeysAndCerts(@NonNull final KeysAndCerts keysAndCerts) {
        throwIfAlreadyUsed();
        this.keysAndCerts = Objects.requireNonNull(keysAndCerts);
        return this;
    }

    /**
     * Provide the wiring model to use for this platform.
     *
     * @param model the wiring model to use
     * @return this
     */
    public PlatformBuilder withModel(@NonNull final WiringModel model) {
        throwIfAlreadyUsed();
        this.model = Objects.requireNonNull(model);
        return this;
    }

    /**
     * Provide the source of non-cryptographic randomness for this platform.
     *
     * @param randomBuilder the source of non-cryptographic randomness
     * @return this
     */
    @NonNull
    public PlatformBuilder withRandomBuilder(@NonNull final RandomBuilder randomBuilder) {
        throwIfAlreadyUsed();
        this.randomBuilder = Objects.requireNonNull(randomBuilder);
        return this;
    }

    /**
     * Parse the address book from the config.txt file.
     *
     * @return the address book
     */
    @NonNull
    private AddressBook loadConfigAddressBook() {
        final LegacyConfigProperties legacyConfig = LegacyConfigPropertiesLoader.loadConfigFile(configPath);
        legacyConfig.appConfig().ifPresent(c -> ParameterProvider.getInstance().setParameters(c.params()));
        return legacyConfig.getAddressBook();
    }

    /**
     * Throw an exception if this builder has been used to build a platform or a platform factory.
     */
    private void throwIfAlreadyUsed() {
        if (used) {
            throw new IllegalStateException("PlatformBuilder has already been used");
        }
    }

    /**
     * Construct a platform component builder. This can be used for advanced use cases where custom component
     * implementations are required. If custom components are not required then {@link #build()} can be used and this
     * method can be ignored.
     *
     * @return a new platform component builder
     */
    @NonNull
    public PlatformComponentBuilder buildComponentBuilder() {
        throwIfAlreadyUsed();
        used = true;

        if (configuration == null) {
            final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();
            rethrowIO(() -> BootstrapUtils.setupConfigBuilder(
                    configurationBuilder, getAbsolutePath(DEFAULT_SETTINGS_FILE_NAME)));
            configuration = configurationBuilder.build();
            checkConfiguration(configuration);
        }

        if (time == null) {
            time = Time.getCurrent();
        }

        if (metrics == null) {
            setupGlobalMetrics(configuration);
            metrics = getMetricsProvider().createPlatformMetrics(selfId);
        }

        if (cryptography == null) {
            cryptography = CryptographyFactory.create();
        }
        final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration, cryptography);
        CryptographyHolder.set(cryptography);
        MerkleCryptoFactory.set(merkleCryptography);

        if (fileSystemManager == null) {
            fileSystemManager = FileSystemManager.create(configuration);
        }

        if (recycleBin == null) {
            recycleBin = RecycleBin.create(
                    metrics, configuration, getStaticThreadManager(), time, fileSystemManager, selfId);
        }

        if (executorFactory == null) {
            executorFactory = ExecutorFactory.create("platform", null, DEFAULT_UNCAUGHT_EXCEPTION_HANDLER);
        }

        final PlatformContext platformContext = new DefaultPlatformContext(
                configuration, metrics, cryptography, time, executorFactory, fileSystemManager, recycleBin);

        final boolean firstPlatform = doStaticSetup(configuration, configPath);

        final AddressBook boostrapAddressBook =
                this.bootstrapAddressBook == null ? loadConfigAddressBook() : this.bootstrapAddressBook;

        checkNodesToRun(List.of(selfId));

        final KeysAndCerts keysAndCerts = this.keysAndCerts == null
                ? initNodeSecurity(boostrapAddressBook, configuration).get(selfId)
                : this.keysAndCerts;

        // the AddressBook is not changed after this point, so we calculate the hash now
        platformContext.getCryptography().digestSync(boostrapAddressBook);

        final ReservedSignedState initialState = getInitialState(
                platformContext,
                softwareVersion,
                genesisStateBuilder,
                appName,
                swirldName,
                selfId,
                boostrapAddressBook);

        final boolean softwareUpgrade = detectSoftwareUpgrade(softwareVersion, initialState.get());

        // Initialize the address book from the configuration and platform saved state.
        final AddressBookInitializer addressBookInitializer = new AddressBookInitializer(
                selfId,
                softwareVersion,
                softwareUpgrade,
                initialState.get(),
                boostrapAddressBook.copy(),
                platformContext);

        if (addressBookInitializer.hasAddressBookChanged()) {
            final MerkleRoot state = initialState.get().getState();
            // Update the address book with the current address book read from config.txt.
            // Eventually we will not do this, and only transactions will be capable of
            // modifying the address book.
            state.getPlatformState()
                    .setAddressBook(
                            addressBookInitializer.getCurrentAddressBook().copy());

            state.getPlatformState()
                    .setPreviousAddressBook(
                            addressBookInitializer.getPreviousAddressBook() == null
                                    ? null
                                    : addressBookInitializer
                                            .getPreviousAddressBook()
                                            .copy());
        }

        // At this point the initial state must have the current address book set.  If not, something is wrong.
        final AddressBook addressBook =
                initialState.get().getState().getPlatformState().getAddressBook();
        if (addressBook == null) {
            throw new IllegalStateException("The current address book of the initial state is null.");
        }

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final IntakeEventCounter intakeEventCounter;
        if (syncConfig.waitForEventsInIntake()) {
            intakeEventCounter = new DefaultIntakeEventCounter(addressBook);
        } else {
            intakeEventCounter = new NoOpIntakeEventCounter();
        }

        final PcesConfig preconsensusEventStreamConfig =
                platformContext.getConfiguration().getConfigData(PcesConfig.class);

        final PcesFileTracker initialPcesFiles;
        try {
            final Path databaseDirectory = getDatabaseDirectory(platformContext, selfId);

            // When we perform the migration to using birth round bounding, we will need to read
            // the old type and start writing the new type.
            initialPcesFiles = PcesFileReader.readFilesFromDisk(
                    platformContext,
                    databaseDirectory,
                    initialState.get().getRound(),
                    preconsensusEventStreamConfig.permitGaps(),
                    platformContext
                            .getConfiguration()
                            .getConfigData(EventConfig.class)
                            .getAncientMode());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        final Scratchpad<IssScratchpad> issScratchpad =
                Scratchpad.create(platformContext, selfId, IssScratchpad.class, "platform.iss");
        issScratchpad.logContents();

        final ApplicationCallbacks callbacks =
                new ApplicationCallbacks(preconsensusEventConsumer, snapshotOverrideConsumer, staleEventConsumer);

        final AtomicReference<StatusActionSubmitter> statusActionSubmitterAtomicReference = new AtomicReference<>();
        final SwirldStateManager swirldStateManager = new SwirldStateManager(
                platformContext,
                initialState.get().getAddressBook(),
                selfId,
                x -> statusActionSubmitterAtomicReference.get().submitStatusAction(x),
                softwareVersion);

        if (model == null) {
            final WiringConfig wiringConfig = platformContext.getConfiguration().getConfigData(WiringConfig.class);

            final int coreCount = Runtime.getRuntime().availableProcessors();
            final int parallelism = (int)
                    Math.max(1, wiringConfig.defaultPoolMultiplier() * coreCount + wiringConfig.defaultPoolConstant());
            final ForkJoinPool defaultPool =
                    platformContext.getExecutorFactory().createForkJoinPool(parallelism);
            logger.info(STARTUP.getMarker(), "Default platform pool parallelism: {}", parallelism);

            model = WiringModelBuilder.create(platformContext)
                    .withJvmAnchorEnabled(true)
                    .withDefaultPool(defaultPool)
                    .withHealthMonitorEnabled(wiringConfig.healthMonitorEnabled())
                    .withHardBackpressureEnabled(wiringConfig.hardBackpressureEnabled())
                    .withHealthMonitorCapacity(wiringConfig.healthMonitorSchedulerCapacity())
                    .withHealthMonitorPeriod(wiringConfig.healthMonitorHeartbeatPeriod())
                    .withHealthLogThreshold(wiringConfig.healthLogThreshold())
                    .withHealthLogPeriod(wiringConfig.healthLogPeriod())
                    .build();
        }

        if (randomBuilder == null) {
            randomBuilder = new RandomBuilder();
        }

        final PlatformBuildingBlocks buildingBlocks = new PlatformBuildingBlocks(
                platformContext,
                model,
                keysAndCerts,
                selfId,
                appName,
                swirldName,
                softwareVersion,
                initialState,
                callbacks,
                preconsensusEventConsumer,
                snapshotOverrideConsumer,
                intakeEventCounter,
                randomBuilder,
                new TransactionPoolNexus(platformContext),
                new AtomicReference<>(),
                new AtomicReference<>(),
                new AtomicReference<>(),
                initialPcesFiles,
                issScratchpad,
                NotificationEngine.buildEngine(getStaticThreadManager()),
                statusActionSubmitterAtomicReference,
                swirldStateManager,
                new AtomicReference<>(),
                new AtomicReference<>(),
                new AtomicReference<>(),
                firstPlatform);

        return new PlatformComponentBuilder(buildingBlocks);
    }

    /**
     * Build a platform. Platform is not started.
     *
     * @return a new platform instance
     */
    @NonNull
    public Platform build() {
        return buildComponentBuilder().build();
    }
}
