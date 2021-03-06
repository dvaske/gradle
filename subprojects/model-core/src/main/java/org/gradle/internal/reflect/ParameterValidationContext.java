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

package org.gradle.internal.reflect;

import javax.annotation.Nullable;

public interface ParameterValidationContext {
    ParameterValidationContext NOOP = new ParameterValidationContext() {
        @Override
        public void visitWarning(@Nullable String ownerPath, String propertyName, String message) {
        }

        @Override
        public void visitWarning(String message) {
        }

        @Override
        public void visitError(@Nullable String ownerPath, String propertyName, String message) {
        }

        @Override
        public void visitError(String message) {
        }
    };

    /**
     * Visits a validation warning associated with the given property.
     */
    void visitWarning(@Nullable String ownerPath, String propertyName, String message);

    /**
     * Visits a validation warning.
     */
    void visitWarning(String message);

    /**
     * Visits a validation error associated with the given property.
     * Errors are not ignored for tasks, whereas for backwards compatibility warnings are ignored at runtime.
     */
    void visitError(@Nullable String ownerPath, String propertyName, String message);

    /**
     * Visits a validation error.
     * Errors are not ignored for tasks, whereas for backwards compatibility warnings are ignored at runtime.
     */
    void visitError(String message);

    static String decorateMessage(@Nullable String ownerPath, String propertyName, String message) {
        String decoratedMessage;
        if (ownerPath == null) {
            decoratedMessage = "Property '" + propertyName + "' " + message + ".";
        } else {
            decoratedMessage = "Property '" + ownerPath + '.' + propertyName + "' " + message + ".";
        }
        return decoratedMessage;
    }
}
