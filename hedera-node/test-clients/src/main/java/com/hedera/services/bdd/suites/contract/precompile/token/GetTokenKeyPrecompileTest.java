/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.contract.precompile.token;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.keyTupleFor;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_ADDRESS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.FREEZE_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType.SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_NOT_PROVIDED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.ContractSpec;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleTokenSpec;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("getTokenKey")
@SuppressWarnings("java:S1192")
@HapiTestLifecycle
public class GetTokenKeyPrecompileTest {
    @ContractSpec(contract = "UpdateTokenInfoContract", creationGas = 4_000_000L)
    static SpecContract getTokenKeyContract;

    @NonFungibleTokenSpec(numPreMints = 1)
    static SpecNonFungibleToken nonFungibleToken;

    @HapiTest
    @DisplayName("can get a token's supply key via static call")
    public Stream<DynamicTest> canGetSupplyKeyViaStaticCall() {
        return hapiTest(nonFungibleToken.doWith(token -> getTokenKeyContract
                .staticCall("getKeyFromToken", nonFungibleToken, SUPPLY_KEY.asBigInteger())
                .andAssert(query -> query.has(resultWith()
                        .resultThruAbi(
                                getABIFor(FUNCTION, "getKeyFromToken", "UpdateTokenInfoContract"),
                                isLiteralResult(new Object[] {keyTupleFor(token.supplyKeyOrThrow())}))))));
    }

    @HapiTest
    @DisplayName("cannot get a nonsense key type")
    public Stream<DynamicTest> cannotGetNonsenseKeyType() {
        return hapiTest(getTokenKeyContract
                .call("getKeyFromToken", nonFungibleToken, BigInteger.valueOf(123L))
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)));
    }

    @HapiTest
    @DisplayName("cannot get a key from a missing token")
    public Stream<DynamicTest> cannotGetMissingTokenKey() {
        return hapiTest(getTokenKeyContract
                .call("getKeyFromToken", ZERO_ADDRESS, SUPPLY_KEY.asBigInteger())
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, INVALID_TOKEN_ID)));
    }

    @HapiTest
    @DisplayName("cannot get a key not set on the token")
    public Stream<DynamicTest> cannotGetUnsetTokenKey() {
        return hapiTest(getTokenKeyContract
                .call("getKeyFromToken", nonFungibleToken, FREEZE_KEY.asBigInteger())
                .andAssert(txn -> txn.hasKnownStatuses(CONTRACT_REVERT_EXECUTED, KEY_NOT_PROVIDED)));
    }
}
