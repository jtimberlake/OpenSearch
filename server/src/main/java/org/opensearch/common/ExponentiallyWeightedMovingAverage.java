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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.common;


import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements exponentially weighted moving averages (commonly abbreviated EWMA) for a single value.
 * This class is safe to share between threads.
 */
public class ExponentiallyWeightedMovingAverage {

    private final double alpha;
    private final AtomicLong averageBits;

    /**
     * Create a new EWMA with a given {@code alpha} and {@code initialAvg}. A smaller alpha means
     * that new data points will have less weight, where a high alpha means older data points will
     * have a lower influence.
     */
    public ExponentiallyWeightedMovingAverage(double alpha, double initialAvg) {
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be greater or equal to 0 and less than or equal to 1");
        }
        this.alpha = alpha;
        this.averageBits = new AtomicLong(Double.doubleToLongBits(initialAvg));
    }

    public double getAverage() {
        return Double.longBitsToDouble(this.averageBits.get());
    }

    public void addValue(double newValue) {
        boolean successful = false;
        do {
            final long currentBits = this.averageBits.get();
            final double currentAvg = getAverage();
            final double newAvg = (alpha * newValue) + ((1 - alpha) * currentAvg);
            final long newBits = Double.doubleToLongBits(newAvg);
            successful = averageBits.compareAndSet(currentBits, newBits);
        } while (successful == false);
    }
}
