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

package org.opensearch.secure_sm;

import junit.framework.TestCase;

import java.security.Permission;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/** Simple tests for SecureSM */
public class SecureSMTests extends TestCase {
    static {
        // install a mock security policy:
        // AllPermission to source code
        // ThreadPermission not granted anywhere else
        final ProtectionDomain sourceCode = SecureSM.class.getProtectionDomain();
        Policy.setPolicy(new Policy() {
            @Override
            public boolean implies(ProtectionDomain domain, Permission permission) {
                if (domain == sourceCode) {
                    return true;
                } else if (permission instanceof ThreadPermission) {
                    return false;
                }
                return true;
            }
        });
        System.setSecurityManager(SecureSM.createTestSecureSM(Collections.emptySet()));
    }

    @SuppressForbidden(reason = "testing that System#exit is blocked")
    public void testTryToExit() {
        try {
            System.exit(1);
            fail("did not hit expected exception");
        } catch (SecurityException expected) {}
    }

    public void testClassCanExit() {
        assertTrue(SecureSM.classCanExit("org.apache.maven.surefire.booter.CommandReader", SecureSM.TEST_RUNNER_PACKAGES));
        assertTrue(SecureSM.classCanExit("com.carrotsearch.ant.tasks.junit4.slave.JvmExit", SecureSM.TEST_RUNNER_PACKAGES));
        assertTrue(SecureSM.classCanExit("org.eclipse.jdt.internal.junit.runner.RemoteTestRunner", SecureSM.TEST_RUNNER_PACKAGES));
        assertTrue(SecureSM.classCanExit("com.intellij.rt.execution.junit.JUnitStarter", SecureSM.TEST_RUNNER_PACKAGES));
        assertTrue(SecureSM.classCanExit("org.opensearch.Foo", new String[]{"org.opensearch.Foo"}));
        assertFalse(SecureSM.classCanExit("org.opensearch.Foo", new String[]{"org.opensearch.Bar"}));
    }

    public void testCreateThread() throws Exception {
        Thread t = new Thread();
        t.start();
        t.join();
        // no exception
    }

    public void testCreateThreadGroup() throws Exception {
        Thread t = new Thread(new ThreadGroup("childgroup"), "child");
        t.start();
        t.join();
        // no exception
    }

    public void testModifyChild() throws Exception {
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread t = new Thread(new ThreadGroup("childgroup"), "child") {
            @Override
            public void run() {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                }
            }
        };
        t.start();
        t.interrupt();
        t.join();
        // no exception
        assertTrue(interrupted.get());
    }

    public void testNoModifySibling() throws Exception {
        final AtomicBoolean interrupted1 = new AtomicBoolean(false);
        final AtomicBoolean interrupted2 = new AtomicBoolean(false);

        final Thread t1 = new Thread(new ThreadGroup("childgroup"), "child") {
            @Override
            public void run() {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException expected) {
                    interrupted1.set(true);
                }
            }
        };
        t1.start();

        Thread t2 = new Thread(new ThreadGroup("anothergroup"), "another child") {
            @Override
            public void run() {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException expected) {
                    interrupted2.set(true);
                    try {
                        t1.interrupt(); // try to bogusly interrupt our sibling
                        fail("did not hit expected exception");
                    } catch (SecurityException expected2) {}
                }
            }
        };
        t2.start();
        t2.interrupt();
        t2.join();
        // sibling attempted to but was not able to muck with its other sibling
        assertTrue(interrupted2.get());
        assertFalse(interrupted1.get());
        // but we are the parent and can terminate
        t1.interrupt();
        t1.join();
        assertTrue(interrupted1.get());
    }
}
