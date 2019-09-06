/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.workers.internal;

import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.api.internal.MutationGuards;
import org.gradle.api.internal.collections.DefaultDomainObjectCollectionFactory;
import org.gradle.api.internal.collections.DomainObjectCollectionFactory;
import org.gradle.api.internal.file.DefaultFileOperations;
import org.gradle.api.internal.file.DefaultFileSystemOperations;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.FilePropertyFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.TemporaryFileProvider;
import org.gradle.api.internal.file.collections.DirectoryFileTreeFactory;
import org.gradle.api.internal.model.DefaultObjectFactory;
import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.api.model.ObjectFactory;
import org.gradle.initialization.LegacyTypesSupport;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.StreamHasher;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.service.scopes.WorkerSharedGlobalScopeServices;
import org.gradle.internal.snapshot.impl.DefaultValueSnapshotter;
import org.gradle.internal.state.ManagedFactoryRegistry;
import org.gradle.process.internal.worker.request.RequestArgumentSerializers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;

public class WorkerDaemonServer implements WorkerProtocol {
    private final ServiceRegistry internalServices;
    private final LegacyTypesSupport legacyTypesSupport;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;
    private final InstantiatorFactory instantiatorFactory;
    private Worker isolatedClassloaderWorker;

    @Inject
    public WorkerDaemonServer(ServiceRegistry parentServices, RequestArgumentSerializers argumentSerializers) {
        this.internalServices = createWorkerDaemonServices(parentServices);
        this.legacyTypesSupport = internalServices.get(LegacyTypesSupport.class);
        this.actionExecutionSpecFactory = internalServices.get(ActionExecutionSpecFactory.class);
        this.instantiatorFactory = internalServices.get(InstantiatorFactory.class);
        argumentSerializers.add(WorkerDaemonMessageSerializer.create());
    }

    static ServiceRegistry createWorkerDaemonServices(ServiceRegistry parent) {
        return ServiceRegistryBuilder.builder()
                .parent(parent)
                .provider(new WorkerSharedGlobalScopeServices())
                .provider(new WorkerDaemonServices())
                .build();
    }

    @Override
    public DefaultWorkResult execute(ActionExecutionSpec spec) {
        try {
            ServiceRegistry workServices = new WorkerPublicServicesBuilder(internalServices).withInternalServicesVisible(spec.isUsesInternalServices()).build();
            Worker worker = getIsolatedClassloaderWorker(spec.getClassLoaderStructure(), workServices);
            return worker.execute(spec);
        } catch (Throwable t) {
            return new DefaultWorkResult(true, t);
        }
    }

    private Worker getIsolatedClassloaderWorker(ClassLoaderStructure classLoaderStructure, ServiceRegistry workServices) {
        if (isolatedClassloaderWorker == null) {
            if (classLoaderStructure instanceof FlatClassLoaderStructure) {
                isolatedClassloaderWorker = new FlatClassLoaderWorker(this.getClass().getClassLoader(), workServices, actionExecutionSpecFactory, instantiatorFactory);
            } else {
                isolatedClassloaderWorker = new IsolatedClassloaderWorker(classLoaderStructure, this.getClass().getClassLoader(), workServices, legacyTypesSupport, actionExecutionSpecFactory, instantiatorFactory, true);
            }
        }
        return isolatedClassloaderWorker;
    }

    @Override
    public String toString() {
        return "WorkerDaemonServer{}";
    }

    private static class WorkerDaemonServices {
        IsolatableSerializerRegistry createIsolatableSerializerRegistry(ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ManagedFactoryRegistry managedFactoryRegistry) {
            return new IsolatableSerializerRegistry(classLoaderHierarchyHasher, managedFactoryRegistry);
        }

        ActionExecutionSpecFactory createActionExecutionSpecFactory(IsolatableFactory isolatableFactory, IsolatableSerializerRegistry serializerRegistry) {
            return new DefaultActionExecutionSpecFactory(isolatableFactory, serializerRegistry);
        }

        DefaultValueSnapshotter createValueSnapshotter(ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ManagedFactoryRegistry managedFactoryRegistry) {
            return new DefaultValueSnapshotter(classLoaderHierarchyHasher, managedFactoryRegistry);
        }

        ClassLoaderHierarchyHasher createClassLoaderHierarchyHasher() {
            // Return a dummy implementation of this as creating a real hasher drags ~20 more services
            // along with it, and a hasher isn't actually needed on the worker process side at the moment.
            return new ClassLoaderHierarchyHasher() {
                @Nullable
                @Override
                public HashCode getClassLoaderHash(@Nonnull ClassLoader classLoader) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        ObjectFactory createObjectFactory(InstantiatorFactory instantiatorFactory, ServiceRegistry services, FileResolver fileResolver, DirectoryFileTreeFactory directoryFileTreeFactory, FilePropertyFactory filePropertyFactory, FileCollectionFactory fileCollectionFactory, DomainObjectCollectionFactory domainObjectCollectionFactory, NamedObjectInstantiator instantiator) {
            return new DefaultObjectFactory(
                    instantiatorFactory.injectAndDecorate(services),
                    instantiator,
                    fileResolver,
                    directoryFileTreeFactory,
                    filePropertyFactory,
                    fileCollectionFactory,
                    domainObjectCollectionFactory);
        }

        DomainObjectCollectionFactory createDomainObjectCollectionFactory(InstantiatorFactory instantiatorFactory, ServiceRegistry services) {
            return new DefaultDomainObjectCollectionFactory(instantiatorFactory, services, CollectionCallbackActionDecorator.NOOP, MutationGuards.identity());
        }

        protected FileSystemOperations createFileSystemOperations(FileOperations fileOperations) {
            return new DefaultFileSystemOperations(fileOperations);
        }

        protected DefaultFileOperations createFileOperations(
                FileResolver fileResolver,
                TemporaryFileProvider temporaryFileProvider,
                InstantiatorFactory instantiatorFactory,
                ServiceRegistry services,
                DirectoryFileTreeFactory directoryFileTreeFactory,
                StreamHasher streamHasher,
                FileCollectionFactory fileCollectionFactory,
                FileSystem fileSystem,
                Deleter deleter
        ) {
            return new DefaultFileOperations(
                    fileResolver,
                    temporaryFileProvider,
                    instantiatorFactory.inject(services),
                    directoryFileTreeFactory,
                    streamHasher,
                    null,
                    null,
                    fileCollectionFactory,
                    fileSystem,
                    deleter
            );
        }
    }
}
