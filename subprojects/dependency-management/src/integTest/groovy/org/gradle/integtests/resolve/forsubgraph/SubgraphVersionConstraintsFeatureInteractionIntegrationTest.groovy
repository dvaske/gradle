/*
 * Copyright 2019 the original author or authors.
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
package org.gradle.integtests.resolve.forsubgraph

import org.gradle.integtests.resolve.AbstractModuleDependencyResolveTest

class SubgraphVersionConstraintsFeatureInteractionIntegrationTest extends AbstractModuleDependencyResolveTest {

    def setup() {
        resolve.withStrictReasonsCheck()
    }

    def "can turn constraint into subgraph constraint by using a component metadata rule"() {
        given:
        repository {
            'org:foo:1.0'() {
                dependsOn 'org:baz:2.0'
            }
            'org:bar:1.0' {
                dependsOn 'org:baz:1.0'
                dependsOn 'org:foo:1.0'
            }
            'org:baz:1.0'()
            'org:baz:2.0'()
        }

        buildFile << """
            dependencies {
                components {
                    withModule('org:bar') { ComponentMetadataDetails details ->
                        details.allVariants {
                            withDependencies {
                                it.each { 
                                    it.version { forSubgraph() } 
                                }
                            }
                        }
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        repositoryInteractions {
            'org:foo:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:baz:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module('org:bar:1.0') {
                    edge('org:baz:{require 1.0; subgraph}', 'org:baz:1.0').byRequest()
                    edge('org:foo:{require 1.0; subgraph}', 'org:foo:1.0') {
                        edge('org:baz:2.0', 'org:baz:1.0').byAncestor()
                    }
                }
            }
        }
    }

    def "can turn subgraph constraint into normal constraint by using a component metadata rule"() {
        given:
        repository {
            'org:foo:1.0'() {
                dependsOn 'org:baz:2.0'
            }
            'org:bar:1.0' {
                dependsOn(group: 'org', artifact: 'baz', version: '1.0', forSubgraph: true)
                dependsOn(group: 'org', artifact: 'foo', version: '1.0', forSubgraph: true)
            }
            'org:baz:1.0'()
            'org:baz:2.0'()
        }

        buildFile << """
            dependencies {
                components {
                    withModule('org:bar') { ComponentMetadataDetails details ->
                        details.allVariants {
                            withDependencies {
                                it.each { 
                                    it.version { notForSubgraph() } 
                                }
                            }
                        }
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        repositoryInteractions {
            'org:foo:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:baz:1.0' {
                expectGetMetadata()
            }
            'org:baz:2.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                module('org:bar:1.0') {
                    edge('org:baz:1.0', 'org:baz:2.0').byConflictResolution('between versions 2.0 and 1.0')
                    module('org:foo:1.0') {
                        module('org:baz:2.0').byRequest()
                    }
                }
            }
        }
    }

    def "an ancestor provided versions is not a version conflict"() {
        given:
        repository {
            'org:foo:1.0'()
            'org:foo:2.0'()
            'org:bar:1.0' {
                dependsOn 'org:foo:2.0'
            }
        }

        buildFile << """
            configurations.conf.resolutionStrategy.failOnVersionConflict()
            dependencies {
                constraints {
                    conf('org:foo:1.0') {
                       version { forSubgraph() }
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        repositoryInteractions {
            'org:foo:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                constraint('org:foo:{require 1.0; subgraph}', 'org:foo:1.0').byConstraint()
                module('org:bar:1.0') {
                    edge('org:foo:2.0', 'org:foo:1.0').byAncestor()
                }
            }
        }
    }

    def "can force a version over an ancestor provided version"() {
        given:
        repository {
            'org:bar:1.0'()
            'org:bar:2.0'()
        }

        settingsFile << "\ninclude 'foo'"
        buildFile << """
            project(':foo') {
                configurations.create('conf')
                artifacts { add('conf', file('foo.jar')) }
                dependencies { 
                    conf('org:bar:2.0') { force = true }
                }
            }
            dependencies {
                constraints {
                    conf('org:bar:1.0') {
                       version { forSubgraph() }
                    }
                }
                conf(project(path: ':foo', configuration: 'conf'))
            }           
        """

        when:
        repositoryInteractions {
            'org:bar:2.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                constraint('org:bar:{require 1.0; subgraph}', 'org:bar:2.0').byConstraint()
                project(':foo', 'test:foo:') {
                    configuration = 'conf'
                    module('org:bar:2.0').byRequest().forced()
                }
            }
        }
    }

    def "can force a version over an ancestor provided version via resolution strategy"() {
        given:
        repository {
            'org:bar:1.0'()
            'org:bar:2.0'()
        }

        settingsFile << "\ninclude 'foo'"
        buildFile << """
            configurations.conf.resolutionStrategy.force('org:bar:2.0')
            project(':foo') {
                configurations.create('conf')
                artifacts { add('conf', file('foo.jar')) }
                dependencies { 
                    conf('org:bar:2.0')
                }
            }
            dependencies {
                constraints {
                    conf('org:bar:1.0') {
                       version { forSubgraph() }
                    }
                }
                conf(project(path: ':foo', configuration: 'conf'))
            }           
        """

        when:
        repositoryInteractions {
            'org:bar:2.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                constraint('org:bar:{require 1.0; subgraph}', 'org:bar:2.0').byConstraint()
                project(':foo', 'test:foo:') {
                    configuration = 'conf'
                    module('org:bar:2.0').byRequest().forced()
                }
            }
        }
    }

    def "subgraph constraints apply to modules provided through substitution"() {
        given:
        repository {
            'org:foo:1.0'()
            'org:foo:2.0'()
            'org:old:2.0'()
            'org:bar:1.0' {
                dependsOn 'org:old:2.0'
            }
        }

        buildFile << """
            configurations.conf.resolutionStrategy.dependencySubstitution {
                substitute module("org:old") because "better foo than old" with module("org:foo:2.0")
            }
            dependencies {
                conf('org:foo:1.0') {
                   version { forSubgraph() }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        repositoryInteractions {
            'org:foo:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                edge('org:foo:{require 1.0; subgraph}', 'org:foo:1.0').byRequest()
                module('org:bar:1.0') {
                    edge('org:old:2.0', 'org:foo:1.0').selectedByRule("better foo than old").byAncestor()
                }
            }
        }
    }

    def "subgraph constraints apply to modules provided through substitution with version selector"() {
        given:
        repository {
            'org:foo:1.0'()
            'org:foo:2.1'()
            'org:foo:2.2'()
            'org:foo:2.3'()
            'org:bar:1.0' {
                dependsOn(group: 'org', artifact: 'foo', version: '2.2')
            }
        }

        buildFile << """
            import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.*
            
            def VERSIONED_COMPARATOR = new DefaultVersionComparator()
            def VERSION_SCHEME = new DefaultVersionSelectorScheme(VERSIONED_COMPARATOR)
            
            configurations.all {
                resolutionStrategy {
                    dependencySubstitution.all {
                        def selector = VERSION_SCHEME.parseSelector("[2.1, 2.2]")
                        if (it.requested.group.startsWith("org") && it.requested.module.startsWith("foo") && selector.accept(it.requested.version)) {
                            it.useTarget("org:foo:2.3", "bad version")
                        }
                    }
                }
            }
            
            dependencies {
                constraints {
                    conf('org:foo:1.0') {
                       version { forSubgraph() }
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        repositoryInteractions {
            // no version listing needed!
            'org:foo:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                constraint('org:foo:{require 1.0; subgraph}', 'org:foo:1.0').byConstraint()
                module('org:bar:1.0') {
                    edge("org:foo:2.2", 'org:foo:1.0').selectedByRule("bad version").byAncestor()
                }
            }
        }
    }


    def "dependency resolve rules dominate over subgraph constraints"() {
        given:
        repository {
            'org:foo:0.11'()
            'org:bar:1.0' {
                dependsOn 'org:foo:2.0'
            }
        }

        buildFile << """
            configurations.conf.resolutionStrategy.eachDependency { DependencyResolveDetails details ->
                if (details.requested.name == 'foo') {
                    details.useVersion '0.11'
                    details.because 'because I can'
                }
            }
            dependencies {
                constraints {
                    conf('org:foo:1.0') {
                       version { forSubgraph() }
                    }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        repositoryInteractions {
            'org:foo:0.11' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                constraint('org:foo:{require 1.0; subgraph}', 'org:foo:0.11').byConstraint()
                module('org:bar:1.0') {
                    edge('org:foo:2.0', 'org:foo:0.11').byRequest().selectedByRule('because I can')
                }
            }
        }
    }

    def "a substitution does not leak substituted subgraph constraint"() {
        given:
        repository {
            'org:foo:1.0'()
            'org:foo:2.0'()
            'org:new:1.0'()
            'org:bar:1.0' {
                dependsOn 'org:foo:2.0'
            }
        }

        buildFile << """
           configurations.conf.resolutionStrategy.eachDependency { DependencyResolveDetails details ->
                if (details.requested.name == 'foo' && details.requested.version == '1.0') {
                    details.useTarget 'org:new:1.0' // this also has to remove the 'subgraph' state of all 'org:foo:1.0' edges
                }
            }
            dependencies {
                conf('org:foo:1.0') {
                   version { forSubgraph() }
                }
                conf('org:bar:1.0')
            }           
        """

        when:
        repositoryInteractions {
            'org:foo:2.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:new:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
            'org:bar:1.0' {
                expectGetMetadata()
                expectGetArtifact()
            }
        }
        run ':checkDeps'

        then:
        resolve.expectGraph {
            root(':', ':test:') {
                edge('org:foo:{require 1.0; subgraph}', 'org:new:1.0').byRequest().selectedByRule()
                module('org:bar:1.0') {
                    module('org:foo:2.0')
                }
            }
        }
    }
}

