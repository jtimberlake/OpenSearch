/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.gateway;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.opensearch.ExceptionsHelper;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.cluster.coordination.CoordinationMetadata.VotingConfigExclusion;
import org.opensearch.cluster.coordination.CoordinationState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Manifest;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.BigArrays;
import org.opensearch.common.util.MockBigArrays;
import org.opensearch.common.util.MockPageCacheRecycler;
import org.opensearch.common.util.set.Sets;
import org.opensearch.core.internal.io.IOUtils;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.env.TestEnvironment;
import org.opensearch.indices.breaker.NoneCircuitBreakerService;
import org.opensearch.node.Node;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.opensearch.test.NodeRoles.nonMasterNode;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GatewayMetaStatePersistedStateTests extends OpenSearchTestCase {
    private NodeEnvironment nodeEnvironment;
    private ClusterName clusterName;
    private Settings settings;
    private DiscoveryNode localNode;
    private BigArrays bigArrays;

    @Override
    public void setUp() throws Exception {
        bigArrays = new MockBigArrays(new MockPageCacheRecycler(Settings.EMPTY), new NoneCircuitBreakerService());
        nodeEnvironment = newNodeEnvironment();
        localNode = new DiscoveryNode("node1", buildNewFakeTransportAddress(), Collections.emptyMap(),
            Sets.newHashSet(DiscoveryNodeRole.MASTER_ROLE), Version.CURRENT);
        clusterName = new ClusterName(randomAlphaOfLength(10));
        settings = Settings.builder().put(ClusterName.CLUSTER_NAME_SETTING.getKey(), clusterName.value()).build();
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
        nodeEnvironment.close();
        super.tearDown();
    }

    private CoordinationState.PersistedState newGatewayPersistedState() {
        final MockGatewayMetaState gateway = new MockGatewayMetaState(localNode, bigArrays);
        gateway.start(settings, nodeEnvironment, xContentRegistry());
        final CoordinationState.PersistedState persistedState = gateway.getPersistedState();
        assertThat(persistedState, instanceOf(GatewayMetaState.LucenePersistedState.class));
        return persistedState;
    }

    private CoordinationState.PersistedState maybeNew(CoordinationState.PersistedState persistedState) throws IOException {
        if (randomBoolean()) {
            persistedState.close();
            return newGatewayPersistedState();
        }
        return persistedState;
    }

    public void testInitialState() throws IOException {
        CoordinationState.PersistedState gateway = null;
        try {
            gateway = newGatewayPersistedState();
            ClusterState state = gateway.getLastAcceptedState();
            assertThat(state.getClusterName(), equalTo(clusterName));
            assertTrue(Metadata.isGlobalStateEquals(state.metadata(), Metadata.EMPTY_METADATA));
            assertThat(state.getVersion(), equalTo(Manifest.empty().getClusterStateVersion()));
            assertThat(state.getNodes().getLocalNode(), equalTo(localNode));

            long currentTerm = gateway.getCurrentTerm();
            assertThat(currentTerm, equalTo(Manifest.empty().getCurrentTerm()));
        } finally {
            IOUtils.close(gateway);
        }
    }

    public void testSetCurrentTerm() throws IOException {
        CoordinationState.PersistedState gateway = null;
        try {
            gateway = newGatewayPersistedState();

            for (int i = 0; i < randomIntBetween(1, 5); i++) {
                final long currentTerm = randomNonNegativeLong();
                gateway.setCurrentTerm(currentTerm);
                gateway = maybeNew(gateway);
                assertThat(gateway.getCurrentTerm(), equalTo(currentTerm));
            }
        } finally {
            IOUtils.close(gateway);
        }
    }

    private ClusterState createClusterState(long version, Metadata metadata) {
        return ClusterState.builder(clusterName).
            nodes(DiscoveryNodes.builder().add(localNode).localNodeId(localNode.getId()).build()).
            version(version).
            metadata(metadata).
            build();
    }

    private CoordinationMetadata createCoordinationMetadata(long term) {
        CoordinationMetadata.Builder builder = CoordinationMetadata.builder();
        builder.term(term);
        builder.lastAcceptedConfiguration(
            new CoordinationMetadata.VotingConfiguration(
                Sets.newHashSet(generateRandomStringArray(10, 10, false))));
        builder.lastCommittedConfiguration(
            new CoordinationMetadata.VotingConfiguration(
                Sets.newHashSet(generateRandomStringArray(10, 10, false))));
        for (int i = 0; i < randomIntBetween(0, 5); i++) {
            builder.addVotingConfigExclusion(new VotingConfigExclusion(randomAlphaOfLength(10), randomAlphaOfLength(10)));
        }

        return builder.build();
    }

    private IndexMetadata createIndexMetadata(String indexName, int numberOfShards, long version) {
        return IndexMetadata.builder(indexName).settings(
            Settings.builder()
                .put(IndexMetadata.SETTING_INDEX_UUID, indexName)
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numberOfShards)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .build()
        ).version(version).build();
    }

    private void assertClusterStateEqual(ClusterState expected, ClusterState actual) {
        assertThat(actual.version(), equalTo(expected.version()));
        assertTrue(Metadata.isGlobalStateEquals(actual.metadata(), expected.metadata()));
        for (IndexMetadata indexMetadata : expected.metadata()) {
            assertThat(actual.metadata().index(indexMetadata.getIndex()), equalTo(indexMetadata));
        }
    }

    public void testSetLastAcceptedState() throws IOException {
        CoordinationState.PersistedState gateway = null;
        try {
            gateway = newGatewayPersistedState();
            final long term = randomNonNegativeLong();

            for (int i = 0; i < randomIntBetween(1, 5); i++) {
                final long version = randomNonNegativeLong();
                final String indexName = randomAlphaOfLength(10);
                final IndexMetadata indexMetadata = createIndexMetadata(indexName, randomIntBetween(1, 5), randomNonNegativeLong());
                final Metadata metadata = Metadata.builder().
                    persistentSettings(Settings.builder().put(randomAlphaOfLength(10), randomAlphaOfLength(10)).build()).
                    coordinationMetadata(createCoordinationMetadata(term)).
                    put(indexMetadata, false).
                    build();
                ClusterState state = createClusterState(version, metadata);

                gateway.setLastAcceptedState(state);
                gateway = maybeNew(gateway);

                ClusterState lastAcceptedState = gateway.getLastAcceptedState();
                assertClusterStateEqual(state, lastAcceptedState);
            }
        } finally {
            IOUtils.close(gateway);
        }
    }

    public void testSetLastAcceptedStateTermChanged() throws IOException {
        CoordinationState.PersistedState gateway = null;
        try {
            gateway = newGatewayPersistedState();

            final String indexName = randomAlphaOfLength(10);
            final int numberOfShards = randomIntBetween(1, 5);
            final long version = randomNonNegativeLong();
            final long term = randomValueOtherThan(Long.MAX_VALUE, OpenSearchTestCase::randomNonNegativeLong);
            final IndexMetadata indexMetadata = createIndexMetadata(indexName, numberOfShards, version);
            final ClusterState state = createClusterState(randomNonNegativeLong(),
                Metadata.builder().coordinationMetadata(createCoordinationMetadata(term)).put(indexMetadata, false).build());
            gateway.setLastAcceptedState(state);

            gateway = maybeNew(gateway);
            final long newTerm = randomLongBetween(term + 1, Long.MAX_VALUE);
            final int newNumberOfShards = randomValueOtherThan(numberOfShards, () -> randomIntBetween(1, 5));
            final IndexMetadata newIndexMetadata = createIndexMetadata(indexName, newNumberOfShards, version);
            final ClusterState newClusterState = createClusterState(randomNonNegativeLong(),
                Metadata.builder().coordinationMetadata(createCoordinationMetadata(newTerm)).put(newIndexMetadata, false).build());
            gateway.setLastAcceptedState(newClusterState);

            gateway = maybeNew(gateway);
            assertThat(gateway.getLastAcceptedState().metadata().index(indexName), equalTo(newIndexMetadata));
        } finally {
            IOUtils.close(gateway);
        }
    }

    public void testCurrentTermAndTermAreDifferent() throws IOException {
        CoordinationState.PersistedState gateway = null;
        try {
            gateway = newGatewayPersistedState();

            long currentTerm = randomNonNegativeLong();
            long term = randomValueOtherThan(currentTerm, OpenSearchTestCase::randomNonNegativeLong);

            gateway.setCurrentTerm(currentTerm);
            gateway.setLastAcceptedState(createClusterState(randomNonNegativeLong(),
                Metadata.builder().coordinationMetadata(CoordinationMetadata.builder().term(term).build()).build()));

            gateway = maybeNew(gateway);
            assertThat(gateway.getCurrentTerm(), equalTo(currentTerm));
            assertThat(gateway.getLastAcceptedState().coordinationMetadata().term(), equalTo(term));
        } finally {
            IOUtils.close(gateway);
        }
    }

    public void testMarkAcceptedConfigAsCommitted() throws IOException {
        CoordinationState.PersistedState gateway = null;
        try {
            gateway = newGatewayPersistedState();

            // generate random coordinationMetadata with different lastAcceptedConfiguration and lastCommittedConfiguration
            CoordinationMetadata coordinationMetadata;
            do {
                coordinationMetadata = createCoordinationMetadata(randomNonNegativeLong());
            } while (coordinationMetadata.getLastAcceptedConfiguration().equals(coordinationMetadata.getLastCommittedConfiguration()));

            ClusterState state = createClusterState(randomNonNegativeLong(),
                Metadata.builder().coordinationMetadata(coordinationMetadata)
                    .clusterUUID(randomAlphaOfLength(10)).build());
            gateway.setLastAcceptedState(state);

            gateway = maybeNew(gateway);
            assertThat(gateway.getLastAcceptedState().getLastAcceptedConfiguration(),
                not(equalTo(gateway.getLastAcceptedState().getLastCommittedConfiguration())));
            gateway.markLastAcceptedStateAsCommitted();

            CoordinationMetadata expectedCoordinationMetadata = CoordinationMetadata.builder(coordinationMetadata)
                .lastCommittedConfiguration(coordinationMetadata.getLastAcceptedConfiguration()).build();
            ClusterState expectedClusterState =
                ClusterState.builder(state).metadata(Metadata.builder().coordinationMetadata(expectedCoordinationMetadata)
                    .clusterUUID(state.metadata().clusterUUID()).clusterUUIDCommitted(true).build()).build();

            gateway = maybeNew(gateway);
            assertClusterStateEqual(expectedClusterState, gateway.getLastAcceptedState());
            gateway.markLastAcceptedStateAsCommitted();

            gateway = maybeNew(gateway);
            assertClusterStateEqual(expectedClusterState, gateway.getLastAcceptedState());
        } finally {
            IOUtils.close(gateway);
        }
    }

    public void testStatePersistedOnLoad() throws IOException {
        // open LucenePersistedState to make sure that cluster state is written out to each data path
        final PersistedClusterStateService persistedClusterStateService =
            new PersistedClusterStateService(nodeEnvironment, xContentRegistry(), getBigArrays(),
                new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), () -> 0L);
        final ClusterState state = createClusterState(randomNonNegativeLong(),
            Metadata.builder().clusterUUID(randomAlphaOfLength(10)).build());
        try (GatewayMetaState.LucenePersistedState ignored = new GatewayMetaState.LucenePersistedState(
            persistedClusterStateService, 42L, state)) {

        }

        nodeEnvironment.close();

        // verify that the freshest state was rewritten to each data path
        for (Path path : nodeEnvironment.nodeDataPaths()) {
            Settings settings = Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toAbsolutePath())
                .put(Environment.PATH_DATA_SETTING.getKey(), path.getParent().getParent().toString()).build();
            try (NodeEnvironment nodeEnvironment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings))) {
                final PersistedClusterStateService newPersistedClusterStateService =
                    new PersistedClusterStateService(nodeEnvironment, xContentRegistry(), getBigArrays(),
                        new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), () -> 0L);
                final PersistedClusterStateService.OnDiskState onDiskState = newPersistedClusterStateService.loadBestOnDiskState();
                assertFalse(onDiskState.empty());
                assertThat(onDiskState.currentTerm, equalTo(42L));
                assertClusterStateEqual(state,
                    ClusterState.builder(ClusterName.DEFAULT)
                        .version(onDiskState.lastAcceptedVersion)
                        .metadata(onDiskState.metadata).build());
            }
        }
    }

    public void testDataOnlyNodePersistence() throws Exception {
        final List<Closeable> cleanup = new ArrayList<>(2);

        try {
            DiscoveryNode localNode = new DiscoveryNode("node1", buildNewFakeTransportAddress(), Collections.emptyMap(),
                Sets.newHashSet(DiscoveryNodeRole.DATA_ROLE), Version.CURRENT);
            Settings settings = Settings.builder()
                .put(ClusterName.CLUSTER_NAME_SETTING.getKey(), clusterName.value())
                .put(nonMasterNode())
                .put(Node.NODE_NAME_SETTING.getKey(), "test")
                .build();
            final MockGatewayMetaState gateway = new MockGatewayMetaState(localNode, bigArrays);
            cleanup.add(gateway);
            final TransportService transportService = mock(TransportService.class);
            TestThreadPool threadPool = new TestThreadPool("testMarkAcceptedConfigAsCommittedOnDataOnlyNode");
            cleanup.add(() -> ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS));
            when(transportService.getThreadPool()).thenReturn(threadPool);
            ClusterService clusterService = mock(ClusterService.class);
            when(clusterService.getClusterSettings()).thenReturn(
                new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS));
            final PersistedClusterStateService persistedClusterStateService =
                new PersistedClusterStateService(nodeEnvironment, xContentRegistry(), getBigArrays(),
                    new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), () -> 0L);
            gateway.start(settings, transportService, clusterService,
                new MetaStateService(nodeEnvironment, xContentRegistry()), null, null, persistedClusterStateService);
            final CoordinationState.PersistedState persistedState = gateway.getPersistedState();
            assertThat(persistedState, instanceOf(GatewayMetaState.AsyncLucenePersistedState.class));

            //generate random coordinationMetadata with different lastAcceptedConfiguration and lastCommittedConfiguration
            CoordinationMetadata coordinationMetadata;
            do {
                coordinationMetadata = createCoordinationMetadata(randomNonNegativeLong());
            } while (coordinationMetadata.getLastAcceptedConfiguration().equals(coordinationMetadata.getLastCommittedConfiguration()));

            ClusterState state = createClusterState(randomNonNegativeLong(),
                Metadata.builder().coordinationMetadata(coordinationMetadata)
                    .clusterUUID(randomAlphaOfLength(10)).build());
            persistedState.setCurrentTerm(state.term());
            persistedState.setLastAcceptedState(state);
            assertBusy(() -> assertTrue(gateway.allPendingAsyncStatesWritten()));

            assertThat(persistedState.getLastAcceptedState().getLastAcceptedConfiguration(),
                not(equalTo(persistedState.getLastAcceptedState().getLastCommittedConfiguration())));
            CoordinationMetadata persistedCoordinationMetadata =
                persistedClusterStateService.loadBestOnDiskState().metadata.coordinationMetadata();
            assertThat(persistedCoordinationMetadata.getLastAcceptedConfiguration(),
                equalTo(GatewayMetaState.AsyncLucenePersistedState.staleStateConfiguration));
            assertThat(persistedCoordinationMetadata.getLastCommittedConfiguration(),
                equalTo(GatewayMetaState.AsyncLucenePersistedState.staleStateConfiguration));

            persistedState.markLastAcceptedStateAsCommitted();
            assertBusy(() -> assertTrue(gateway.allPendingAsyncStatesWritten()));

            CoordinationMetadata expectedCoordinationMetadata = CoordinationMetadata.builder(coordinationMetadata)
                .lastCommittedConfiguration(coordinationMetadata.getLastAcceptedConfiguration()).build();
            ClusterState expectedClusterState =
                ClusterState.builder(state).metadata(Metadata.builder().coordinationMetadata(expectedCoordinationMetadata)
                    .clusterUUID(state.metadata().clusterUUID()).clusterUUIDCommitted(true).build()).build();

            assertClusterStateEqual(expectedClusterState, persistedState.getLastAcceptedState());
            persistedCoordinationMetadata = persistedClusterStateService.loadBestOnDiskState().metadata.coordinationMetadata();
            assertThat(persistedCoordinationMetadata.getLastAcceptedConfiguration(),
                equalTo(GatewayMetaState.AsyncLucenePersistedState.staleStateConfiguration));
            assertThat(persistedCoordinationMetadata.getLastCommittedConfiguration(),
                equalTo(GatewayMetaState.AsyncLucenePersistedState.staleStateConfiguration));
            assertTrue(persistedClusterStateService.loadBestOnDiskState().metadata.clusterUUIDCommitted());

            // generate a series of updates and check if batching works
            final String indexName = randomAlphaOfLength(10);
            long currentTerm = state.term();
            final int iterations = randomIntBetween(1, 1000);
            for (int i = 0; i < iterations; i++) {
                if (rarely()) {
                    // bump term
                    currentTerm = currentTerm + (rarely() ? randomIntBetween(1, 5) : 0L);
                    persistedState.setCurrentTerm(currentTerm);
                } else {
                    // update cluster state
                    final int numberOfShards = randomIntBetween(1, 5);
                    final long term = Math.min(state.term() + (rarely() ? randomIntBetween(1, 5) : 0L), currentTerm);
                    final IndexMetadata indexMetadata = createIndexMetadata(indexName, numberOfShards, i);
                    state = createClusterState(state.version() + 1,
                        Metadata.builder().coordinationMetadata(createCoordinationMetadata(term)).put(indexMetadata, false).build());
                    persistedState.setLastAcceptedState(state);
                }
            }
            assertEquals(currentTerm, persistedState.getCurrentTerm());
            assertClusterStateEqual(state, persistedState.getLastAcceptedState());
            assertBusy(() -> assertTrue(gateway.allPendingAsyncStatesWritten()));

            gateway.close();
            assertTrue(cleanup.remove(gateway));

            try (CoordinationState.PersistedState reloadedPersistedState = newGatewayPersistedState()) {
                assertEquals(currentTerm, reloadedPersistedState.getCurrentTerm());
                assertClusterStateEqual(GatewayMetaState.AsyncLucenePersistedState.resetVotingConfiguration(state),
                    reloadedPersistedState.getLastAcceptedState());
                assertNotNull(reloadedPersistedState.getLastAcceptedState().metadata().index(indexName));
            }
        } finally {
            IOUtils.close(cleanup);
        }
    }

    public void testStatePersistenceWithIOIssues() throws IOException {
        final AtomicReference<Double> ioExceptionRate = new AtomicReference<>(0.01d);
        final List<MockDirectoryWrapper> list = new ArrayList<>();
        final PersistedClusterStateService persistedClusterStateService =
            new PersistedClusterStateService(nodeEnvironment, xContentRegistry(), getBigArrays(),
                new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), () -> 0L) {
                @Override
                Directory createDirectory(Path path) {
                    final MockDirectoryWrapper wrapper = newMockFSDirectory(path);
                    wrapper.setAllowRandomFileNotFoundException(randomBoolean());
                    wrapper.setRandomIOExceptionRate(ioExceptionRate.get());
                    wrapper.setRandomIOExceptionRateOnOpen(ioExceptionRate.get());
                    list.add(wrapper);
                    return wrapper;
                }
            };
        ClusterState state = createClusterState(randomNonNegativeLong(),
            Metadata.builder().clusterUUID(randomAlphaOfLength(10)).build());
        long currentTerm = 42L;
        try (GatewayMetaState.LucenePersistedState persistedState = new GatewayMetaState.LucenePersistedState(
            persistedClusterStateService, currentTerm, state)) {

            try {
                if (randomBoolean()) {
                    final ClusterState newState = createClusterState(randomNonNegativeLong(),
                        Metadata.builder().clusterUUID(randomAlphaOfLength(10)).build());
                    persistedState.setLastAcceptedState(newState);
                    state = newState;
                } else {
                    final long newTerm = currentTerm + 1;
                    persistedState.setCurrentTerm(newTerm);
                    currentTerm = newTerm;
                }
            } catch (IOError | Exception e) {
                assertNotNull(ExceptionsHelper.unwrap(e, IOException.class));
            }

            ioExceptionRate.set(0.0d);
            for (MockDirectoryWrapper wrapper : list) {
                wrapper.setRandomIOExceptionRate(ioExceptionRate.get());
                wrapper.setRandomIOExceptionRateOnOpen(ioExceptionRate.get());
            }

            for (int i = 0; i < randomIntBetween(1, 5); i++) {
                if (randomBoolean()) {
                    final long version = randomNonNegativeLong();
                    final String indexName = randomAlphaOfLength(10);
                    final IndexMetadata indexMetadata = createIndexMetadata(indexName, randomIntBetween(1, 5), randomNonNegativeLong());
                    final Metadata metadata = Metadata.builder().
                        persistentSettings(Settings.builder().put(randomAlphaOfLength(10), randomAlphaOfLength(10)).build()).
                        coordinationMetadata(createCoordinationMetadata(1L)).
                        put(indexMetadata, false).
                        build();
                    state = createClusterState(version, metadata);
                    persistedState.setLastAcceptedState(state);
                } else {
                    currentTerm += 1;
                    persistedState.setCurrentTerm(currentTerm);
                }
            }

            assertEquals(state, persistedState.getLastAcceptedState());
            assertEquals(currentTerm, persistedState.getCurrentTerm());

        } catch (IOError | Exception e) {
            if (ioExceptionRate.get() == 0.0d) {
                throw e;
            }
            assertNotNull(ExceptionsHelper.unwrap(e, IOException.class));
            return;
        }

        nodeEnvironment.close();

        // verify that the freshest state was rewritten to each data path
        for (Path path : nodeEnvironment.nodeDataPaths()) {
            Settings settings = Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toAbsolutePath())
                .put(Environment.PATH_DATA_SETTING.getKey(), path.getParent().getParent().toString()).build();
            try (NodeEnvironment nodeEnvironment = new NodeEnvironment(settings, TestEnvironment.newEnvironment(settings))) {
                final PersistedClusterStateService newPersistedClusterStateService =
                    new PersistedClusterStateService(nodeEnvironment, xContentRegistry(), getBigArrays(),
                        new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), () -> 0L);
                final PersistedClusterStateService.OnDiskState onDiskState = newPersistedClusterStateService.loadBestOnDiskState();
                assertFalse(onDiskState.empty());
                assertThat(onDiskState.currentTerm, equalTo(currentTerm));
                assertClusterStateEqual(state,
                    ClusterState.builder(ClusterName.DEFAULT)
                        .version(onDiskState.lastAcceptedVersion)
                        .metadata(onDiskState.metadata).build());
            }
        }
    }

    private static BigArrays getBigArrays() {
        return usually()
                ? BigArrays.NON_RECYCLING_INSTANCE
                : new MockBigArrays(new MockPageCacheRecycler(Settings.EMPTY), new NoneCircuitBreakerService());
    }

}
