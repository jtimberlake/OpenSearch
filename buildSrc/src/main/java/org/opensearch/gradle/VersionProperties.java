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

package org.opensearch.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Accessor for shared dependency versions used by opensearch, namely the opensearch and lucene versions.
 */
public class VersionProperties {

    public static String getOpenSearch() {
        return opensearch;
    }

    public static Version getOpenSearchVersion() {
        return Version.fromString(opensearch);
    }

    public static String getLucene() {
        return lucene;
    }

    public static String getBundledJdk(final String platform) {
        switch (platform) {
            case "darwin": // fall trough
            case "mac":
                return bundledJdkDarwin;
            case "linux":
                return bundledJdkLinux;
            case "windows":
                return bundledJdkWindows;
            default:
                throw new IllegalArgumentException("unknown platform [" + platform + "]");
        }
    }

    public static String getBundledJdkVendor() {
        return bundledJdkVendor;
    }

    public static Map<String, String> getVersions() {
        return versions;
    }

    private static final String opensearch;
    private static final String lucene;
    private static final String bundledJdkDarwin;
    private static final String bundledJdkLinux;
    private static final String bundledJdkWindows;
    private static final String bundledJdkVendor;
    private static final Map<String, String> versions = new HashMap<String, String>();

    static {
        Properties props = getVersionProperties();
        opensearch = props.getProperty("opensearch");
        lucene = props.getProperty("lucene");
        bundledJdkVendor = props.getProperty("bundled_jdk_vendor");
        final String bundledJdk = props.getProperty("bundled_jdk");
        bundledJdkDarwin = props.getProperty("bundled_jdk_darwin", bundledJdk);
        bundledJdkLinux = props.getProperty("bundled_jdk_linux", bundledJdk);
        bundledJdkWindows = props.getProperty("bundled_jdk_windows", bundledJdk);

        for (String property : props.stringPropertyNames()) {
            versions.put(property, props.getProperty(property));
        }
    }

    private static Properties getVersionProperties() {
        Properties props = new Properties();
        try (InputStream propsStream = VersionProperties.class.getResourceAsStream("/version.properties")) {
            if (propsStream == null) {
                throw new IllegalStateException("/version.properties resource missing");
            }
            props.load(propsStream);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load version properties", e);
        }
        return props;
    }

    public static boolean isOpenSearchSnapshot() {
        return opensearch.endsWith("-SNAPSHOT");
    }
}
