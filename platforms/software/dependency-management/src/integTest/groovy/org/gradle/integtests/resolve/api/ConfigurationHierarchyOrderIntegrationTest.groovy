/*
 * Copyright 2026 the original author or authors.
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

package org.gradle.integtests.resolve.api

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

/**
 * Tests the order in which dependencies inherited through {@code extendsFrom} end up on a classpath.
 */
class ConfigurationHierarchyOrderIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        settingsFile << """
            rootProject.name = "root"
            include("a", "b")
        """
        ["a", "b"].each { name ->
            file("${name}/build.gradle") << "plugins { id('java-library') }"
            file("${name}/src/main/java/com/example/Greeting.java") << """
                package com.example;
                public class Greeting {}
            """
        }
        buildFile << """
            plugins { id('java-library') }

            configurations {
                dependencyScope("zebra")
                dependencyScope("apple")
                resolvable("ordered") {
                    extendsFrom(configurations.zebra, configurations.apple)
                }
            }

            dependencies {
                zebra(project(":a"))
                apple(project(":b"))
            }

            def orderedFiles = configurations.ordered.incoming.files
            tasks.register("printOrder") {
                doLast {
                    println("order: " + orderedFiles*.name)
                }
            }
        """
    }

    private void enableEnhancedGraphOrdering() {
        settingsFile << """
            enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
        """
    }

    def "dependencies are ordered by configuration name by default"() {
        when:
        succeeds("printOrder")

        then:
        outputContains("order: [b.jar, a.jar]")
    }

    def "dependencies follow the configuration hierarchy with enhanced graph ordering"() {
        given:
        enableEnhancedGraphOrdering()

        when:
        succeeds("printOrder")

        then:
        outputContains("order: [a.jar, b.jar]")
    }

    def "test-scoped dependencies come before main dependencies on the test classpaths with enhanced graph ordering"() {
        given:
        settingsFile << """
            include("clock", "config", "test-config")
        """
        file("clock/build.gradle") << """
            plugins {
                id('java-library')
                id('java-test-fixtures')
            }
        """
        file("clock/src/main/java/com/example/Clock.java") << """
            package com.example;
            public class Clock {}
        """
        file("clock/src/testFixtures/java/com/example/Clock.java") << """
            package com.example;
            public class Clock {
                public static void setFixed(long millis) {}
            }
        """
        file("config/build.gradle") << "plugins { id('java-library') }"
        file("config/src/main/resources/app.properties") << "environment=production"
        file("test-config/build.gradle") << "plugins { id('java-library') }"
        file("test-config/src/main/resources/app.properties") << "environment=test"

        buildFile << """
            dependencies {
                implementation(project(":clock"))
                testImplementation(testFixtures(project(":clock")))
                implementation(project(":config"))
                testImplementation(project(":test-config"))
            }

            def testRuntimeFiles = configurations.testRuntimeClasspath.incoming.files
            tasks.register("printTestRuntimeClasspath") {
                doLast {
                    println("test runtime classpath: " + testRuntimeFiles*.name)
                }
            }
        """
        file("src/test/java/com/example/app/ClockTest.java") << """
            package com.example.app;
            class ClockTest {
                void usesTheTestDouble() {
                    com.example.Clock.setFixed(0L);
                }
            }
        """

        when:
        fails("compileTestJava")

        then:
        failure.assertHasErrorOutput("cannot find symbol")

        when:
        enableEnhancedGraphOrdering()
        succeeds("compileTestJava", "printTestRuntimeClasspath")

        then:
        outputContains("test runtime classpath: [clock-test-fixtures.jar, test-config.jar, clock.jar, config.jar]")
    }

    def "runtime-only dependencies come before implementation dependencies with enhanced graph ordering"() {
        given:
        enableEnhancedGraphOrdering()
        buildFile << """
            dependencies {
                implementation(project(":a"))
                runtimeOnly(project(":b"))
            }

            def runtimeFiles = configurations.runtimeClasspath.incoming.files
            tasks.register("printRuntimeClasspath") {
                doLast {
                    println("runtime classpath: " + runtimeFiles*.name)
                }
            }
        """

        when:
        succeeds("printRuntimeClasspath")

        then:
        outputContains("runtime classpath: [b.jar, a.jar]")
    }
}
