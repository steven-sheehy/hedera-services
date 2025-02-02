/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.suites.TargetNetworkType.SHARED_HAPI_TEST_NETWORK;
import static com.swirlds.platform.system.status.PlatformStatus.ACTIVE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.runAsync;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.services.bdd.junit.hedera.AbstractGrpcNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.suites.TargetNetworkType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * A network of Hedera nodes started in subprocesses and accessed via gRPC. Unlike
 * nodes in a remote or embedded network, its nodes support lifecycle operations like
 * stopping and restarting.
 */
public class SubProcessNetwork extends AbstractGrpcNetwork implements HederaNetwork {
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final int FIRST_CANDIDATE_PORT = 30000;
    private static final int LAST_CANDIDATE_PORT = 40000;

    private static final long FIRST_NODE_ACCOUNT_NUM = 3;
    private static final String SUBPROCESS_HOST = "127.0.0.1";
    private static final String SHARED_NETWORK_NAME = "LAUNCHER_SESSION_SCOPE";
    private static final String[] NODE_NAMES = new String[] {"Alice", "Bob", "Carol", "Dave"};
    private static final GrpcPinger GRPC_PINGER = new GrpcPinger();
    private static final PrometheusClient PROMETHEUS_CLIENT = new PrometheusClient();

    // We initialize these randomly to reduce risk of port binding conflicts in CI runners
    private static int nextGrpcPort;
    private static int nextGossipPort;
    private static int nextGossipTlsPort;
    private static int nextPrometheusPort;
    private static boolean nextPortsInitialized = false;

    public static final AtomicReference<HederaNetwork> SHARED_NETWORK = new AtomicReference<>();

    private final String configTxt;

    private AtomicReference<CompletableFuture<Void>> ready = new AtomicReference<>();

    private SubProcessNetwork(@NonNull final String networkName, @NonNull final List<HederaNode> nodes) {
        super(networkName, nodes);
        this.configTxt = configTxtFor(name(), nodes);
    }

    /**
     * Creates a shared network of sub-process nodes with the given size.
     *
     * @param size the number of nodes in the network
     * @return the shared network
     */
    public static synchronized HederaNetwork newSharedSubProcessNetwork(final int size) {
        if (SHARED_NETWORK.get() != null) {
            throw new UnsupportedOperationException("Only one shared network allowed per launcher session");
        }
        final var sharedNetwork = liveNetwork(SHARED_NETWORK_NAME, size);
        SHARED_NETWORK.set(sharedNetwork);
        return sharedNetwork;
    }

    /**
     * Creates a network of sub-process nodes with the given name and size. Unlike the shared
     * network, this network's nodes will have working directories scoped to the given name.
     *
     * @param name the name of the network
     * @param size the number of nodes in the network
     * @return the network
     */
    public static HederaNetwork newSubProcessNetwork(@NonNull final String name, final int size) {
        requireNonNull(name);
        return liveNetwork(name, size);
    }

    /**
     * Returns the network type; for now this is always
     * {@link TargetNetworkType#SHARED_HAPI_TEST_NETWORK}.
     *
     * @return the network type
     */
    public TargetNetworkType type() {
        return SHARED_HAPI_TEST_NETWORK;
    }

    /**
     * Starts all nodes in the network.
     */
    public void start() {
        nodes.forEach(node -> node.initWorkingDir(configTxt).start());
    }

    /**
     * Forcibly stops all nodes in the network.
     */
    public void terminate() {
        nodes.forEach(HederaNode::terminate);
    }

    /**
     * Waits for all nodes in the network to be ready within the given timeout.
     */
    public void awaitReady(@NonNull final Duration timeout) {
        if (ready.get() == null) {
            final var future = runAsync(() -> {
                final var deadline = Instant.now().plus(timeout);
                nodes.forEach(node -> awaitStatus(node, ACTIVE, Duration.between(Instant.now(), deadline)));
                this.clients = HapiClients.clientsFor(this);
            });
            if (!ready.compareAndSet(null, future)) {
                // We only need one thread to wait for readiness
                future.cancel(true);
            }
        }
        ready.get().join();
    }

    /**
     * Creates a network of live (sub-process) nodes with the given name and size. This method is
     * synchronized because we don't want to re-use any ports across different networks.
     *
     * @param name the name of the network
     * @param size the number of nodes in the network
     * @return the network
     */
    private static synchronized HederaNetwork liveNetwork(@NonNull final String name, final int size) {
        if (!nextPortsInitialized) {
            initializeNextPortsForNetwork(size);
        }
        final var network = new SubProcessNetwork(
                name,
                IntStream.range(0, size)
                        .<HederaNode>mapToObj(
                                nodeId -> new SubProcessNode(metadataFor(nodeId, name), GRPC_PINGER, PROMETHEUS_CLIENT))
                        .toList());
        Runtime.getRuntime().addShutdownHook(new Thread(network::terminate));
        return network;
    }

    private static String configTxtFor(@NonNull final String networkName, @NonNull final List<HederaNode> nodes) {
        final var sb = new StringBuilder();
        sb.append("swirld, ")
                .append(networkName)
                .append("\n")
                .append("\n# This next line is, hopefully, ignored.\n")
                .append("app, HederaNode.jar\n\n#The following nodes make up this network\n");
        for (final var node : nodes) {
            sb.append("address, ")
                    .append(node.getNodeId())
                    .append(", ")
                    .append(node.getName().charAt(0))
                    .append(", ")
                    .append(node.getName())
                    .append(", 1, 127.0.0.1, ")
                    .append(nextGossipPort + (node.getNodeId() * 2))
                    .append(", 127.0.0.1, ")
                    .append(nextGossipTlsPort + (node.getNodeId() * 2))
                    .append(", ")
                    .append("0.0.")
                    .append(node.getAccountId().accountNumOrThrow())
                    .append("\n");
        }
        sb.append("\nnextNodeId, ").append(nodes.size()).append("\n");
        return sb.toString();
    }

    private static NodeMetadata metadataFor(final int nodeId, @NonNull final String networkName) {
        return new NodeMetadata(
                nodeId,
                NODE_NAMES[nodeId],
                AccountID.newBuilder()
                        .accountNum(FIRST_NODE_ACCOUNT_NUM + nodeId)
                        .build(),
                SUBPROCESS_HOST,
                nextGrpcPort + nodeId * 2,
                nextGossipPort + nodeId * 2,
                nextGossipTlsPort + nodeId * 2,
                nextPrometheusPort + nodeId,
                workingDirFor(nodeId, SHARED_NETWORK_NAME.equals(networkName) ? null : networkName));
    }

    private static void initializeNextPortsForNetwork(final int size) {
        // We need 5 ports for each node in the network (gRPC, gRPC, gossip, gossip TLS, prometheus)
        nextGrpcPort = randomPortAfter(FIRST_CANDIDATE_PORT, size * 5);
        nextGossipPort = nextGrpcPort + 2 * size;
        nextGossipTlsPort = nextGossipPort + 1;
        nextPrometheusPort = nextGossipPort + 2 * size;
        nextPortsInitialized = true;
    }

    private static int randomPortAfter(final int firstAvailable, final int numRequired) {
        return RANDOM.nextInt(firstAvailable, LAST_CANDIDATE_PORT + 1 - numRequired);
    }
}
