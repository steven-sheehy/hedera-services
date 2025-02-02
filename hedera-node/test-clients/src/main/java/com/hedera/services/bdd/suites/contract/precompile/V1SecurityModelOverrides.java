/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.contract.precompile;

public class V1SecurityModelOverrides {

    public static final String CONTRACTS_ALLOW_SYSTEM_USE_OF_HAPI_SIGS = "contracts.allowSystemUseOfHapiSigs";

    public static final String CONTRACTS_MAX_NUM_WITH_HAPI_SIGS_ACCESS = "contracts.maxNumWithHapiSigsAccess";
    public static final String CONTRACTS_V1_SECURITY_MODEL_BLOCK_CUTOFF = "10_000_000";
    public static final String CONTRACTS_V2_SECURITY_MODEL_BLOCK_CUTOFF = "1";

    private V1SecurityModelOverrides() {}
}
