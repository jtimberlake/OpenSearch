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

package org.opensearch.search.msearch;

import org.opensearch.action.search.MultiSearchRequest;
import org.opensearch.action.search.MultiSearchResponse;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.test.OpenSearchIntegTestCase;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertFirstHit;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertNoFailures;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.hasId;
import static org.hamcrest.Matchers.equalTo;

public class MultiSearchIT extends OpenSearchIntegTestCase {

    public void testSimpleMultiSearch() {
        createIndex("test");
        ensureGreen();
        client().prepareIndex("test", "type", "1").setSource("field", "xxx").get();
        client().prepareIndex("test", "type", "2").setSource("field", "yyy").get();
        refresh();
        MultiSearchResponse response = client().prepareMultiSearch()
                .add(client().prepareSearch("test").setQuery(QueryBuilders.termQuery("field", "xxx")))
                .add(client().prepareSearch("test").setQuery(QueryBuilders.termQuery("field", "yyy")))
                .add(client().prepareSearch("test").setQuery(QueryBuilders.matchAllQuery()))
                .get();

        for (MultiSearchResponse.Item item : response) {
           assertNoFailures(item.getResponse());
        }
        assertThat(response.getResponses().length, equalTo(3));
        assertHitCount(response.getResponses()[0].getResponse(), 1L);
        assertHitCount(response.getResponses()[1].getResponse(), 1L);
        assertHitCount(response.getResponses()[2].getResponse(), 2L);
        assertFirstHit(response.getResponses()[0].getResponse(), hasId("1"));
        assertFirstHit(response.getResponses()[1].getResponse(), hasId("2"));
    }

    public void testSimpleMultiSearchMoreRequests() {
        createIndex("test");
        int numDocs = randomIntBetween(0, 16);
        for (int i = 0; i < numDocs; i++) {
            client().prepareIndex("test", "type", Integer.toString(i)).setSource("{}", XContentType.JSON).get();
        }
        refresh();

        int numSearchRequests = randomIntBetween(1, 64);
        MultiSearchRequest request = new MultiSearchRequest();
        if (randomBoolean()) {
            request.maxConcurrentSearchRequests(randomIntBetween(1, numSearchRequests));
        }
        for (int i = 0; i < numSearchRequests; i++) {
            request.add(client().prepareSearch("test"));
        }

        MultiSearchResponse response = client().multiSearch(request).actionGet();
        assertThat(response.getResponses().length, equalTo(numSearchRequests));
        for (MultiSearchResponse.Item item : response) {
            assertNoFailures(item.getResponse());
            assertHitCount(item.getResponse(), numDocs);
        }
    }

}
