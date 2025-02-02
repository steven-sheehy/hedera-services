/*
 * Copyright (C) 2018-2024 Hedera Hashgraph, LLC
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

package com.swirlds.common.constructable;

import java.util.function.Supplier;

/**
 * A no arguments constructor for {@link RuntimeConstructable}, this is a replacement for the previous default
 * {@link Supplier}
 */
@FunctionalInterface
public interface NoArgsConstructor extends Supplier<RuntimeConstructable> {
    /**
     * @return a new instance of the {@link RuntimeConstructable}
     */
    @Override
    RuntimeConstructable get();
}
