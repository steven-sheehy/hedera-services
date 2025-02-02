/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxSigs.signMessage;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.propertyPreservingHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isContractWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bytecodePath;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicitContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicitEthereumTransaction;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.contractListWithPropertiesInheritedFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getPrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.HIGHLY_NON_DETERMINISTIC_FEES;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_CONTRACT_CALL_RESULTS;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_FUNCTION_PARAMETERS;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_NONCE;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_TRANSACTION_FEES;
import static com.hedera.services.bdd.suites.HapiSuite.CHAIN_ID;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_BYTECODE_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ERROR_DECODING_BYTESTRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.util.Integers;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.swirlds.common.utility.CommonUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractCreateSuite {

    public static final String EMPTY_CONSTRUCTOR_CONTRACT = "EmptyConstructor";
    public static final String PARENT_INFO = "parentInfo";
    private static final String PAYER = "payer";

    private static final Logger log = LogManager.getLogger(ContractCreateSuite.class);

    // The following constants are referenced from -
    // https://github.com/Arachnid/deterministic-deployment-proxy?tab=readme-ov-file#deployment-transaction
    private static final String DEPLOYMENT_SIGNER = "3fab184622dc19b6109349b94811493bf2a45362";
    private static final String DEPLOYMENT_TRANSACTION =
            "f8a58085174876e800830186a08080b853604580600e600039806000f350fe7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe03601600081602082378035828234f58015156039578182fd5b8082525050506014600cf31ba02222222222222222222222222222222222222222222222222222222222222222a02222222222222222222222222222222222222222222222222222222222222222";
    private static final String EXPECTED_DEPLOYER_ADDRESS = "4e59b44847b379578588920ca78fbf26c0b4956c";
    private static final String DEPLOYER = "DeployerContract";

    @HapiTest
    final Stream<DynamicTest> createDeterministicDeployer() {
        final var creatorAddress = ByteString.copyFrom(CommonUtils.unhex(DEPLOYMENT_SIGNER));
        final var transaction = ByteString.copyFrom(CommonUtils.unhex(DEPLOYMENT_TRANSACTION));
        final var systemFileId = FileID.newBuilder().setFileNum(159).build();

        return defaultHapiSpec("createDeterministicDeployer")
                .given(
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(PAYER).balance(6 * ONE_MILLION_HBARS),
                        cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                        cryptoTransfer(tinyBarsFromTo(PAYER, creatorAddress, ONE_HUNDRED_HBARS)))
                .when(explicitEthereumTransaction(DEPLOYER, (spec, b) -> b.setCallData(systemFileId)
                                .setEthereumData(transaction))
                        .payingWith(PAYER))
                .then(getContractInfo(DEPLOYER)
                        .has(contractWith().addressOrAlias(EXPECTED_DEPLOYER_ADDRESS))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> createContractWithStakingFields() {
        final var contract = "CreateTrivial";
        return defaultHapiSpec("createContractWithStakingFields", HIGHLY_NON_DETERMINISTIC_FEES)
                .given(
                        uploadInitCode(contract),
                        // refuse eth conversion because ethereum transaction is missing staking fields to map
                        contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(true)
                                .stakedNodeId(0)
                                .refusingEthConversion(),
                        getContractInfo(contract)
                                .has(contractWith()
                                        .isDeclinedReward(true)
                                        .noStakedAccountId()
                                        .stakedNodeId(0))
                                .logged())
                .when(
                        contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(true)
                                .stakedAccountId("0.0.10")
                                .refusingEthConversion(),
                        getContractInfo(contract)
                                .has(contractWith()
                                        .isDeclinedReward(true)
                                        .noStakingNodeId()
                                        .stakedAccountId("0.0.10"))
                                .logged())
                .then(
                        contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedNodeId(0)
                                .refusingEthConversion(),
                        getContractInfo(contract)
                                .has(contractWith()
                                        .isDeclinedReward(false)
                                        .noStakedAccountId()
                                        .stakedNodeId(0))
                                .logged(),
                        contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedAccountId("0.0.10")
                                .refusingEthConversion(),
                        getContractInfo(contract)
                                .has(contractWith()
                                        .isDeclinedReward(false)
                                        .noStakingNodeId()
                                        .stakedAccountId("0.0.10"))
                                .logged(),
                        /* sentinel values throw */
                        contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedAccountId("0.0.0")
                                .hasPrecheck(INVALID_STAKING_ID)
                                .refusingEthConversion(),
                        contractCreate(contract)
                                .adminKey(THRESHOLD)
                                .declinedReward(false)
                                .stakedNodeId(-1L)
                                .hasPrecheck(INVALID_STAKING_ID)
                                .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> insufficientPayerBalanceUponCreation() {
        return defaultHapiSpec("InsufficientPayerBalanceUponCreation", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(cryptoCreate("bankrupt").balance(0L), uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                .then(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .payingWith("bankrupt")
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> disallowCreationsOfEmptyInitCode() {
        final var contract = "EmptyContract";
        return defaultHapiSpec("allowCreationsOfEmptyContract")
                .given(
                        newKeyNamed(ADMIN_KEY),
                        // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                        // transaction
                        contractCreate(contract)
                                .adminKey(ADMIN_KEY)
                                .entityMemo("Empty Contract")
                                .inlineInitCode(ByteString.EMPTY)
                                .hasKnownStatus(CONTRACT_BYTECODE_EMPTY)
                                .refusingEthConversion())
                .when()
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> cannotSendToNonExistentAccount() {
        final var contract = "Multipurpose";
        Object[] donationArgs = new Object[] {666666L, "Hey, Ma!"};

        return defaultHapiSpec("CannotSendToNonExistentAccount", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(contract))
                .when(contractCreate(contract).balance(666))
                .then(contractCall(contract, "donate", donationArgs).hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> invalidSystemInitcodeFileFailsWithInvalidFileId() {
        final var neverToBe = "NeverToBe";
        final var systemFileId = FileID.newBuilder().setFileNum(159).build();
        return defaultHapiSpec("InvalidSystemInitcodeFileFailsWithInvalidFileId")
                .given(
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS))
                .when()
                .then(
                        explicitContractCreate(neverToBe, (spec, b) -> b.setFileID(systemFileId))
                                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                                // transaction
                                .hasKnownStatus(INVALID_FILE_ID)
                                .refusingEthConversion(),
                        explicitEthereumTransaction(neverToBe, (spec, b) -> {
                                    final var signedEthTx = signMessage(
                                            placeholderEthTx(), getPrivateKeyFromSpec(spec, SECP_256K1_SOURCE_KEY));
                                    b.setCallData(systemFileId)
                                            .setEthereumData(ByteString.copyFrom(signedEthTx.encodeTx()));
                                })
                                .hasPrecheck(INVALID_FILE_ID));
    }

    @HapiTest
    final Stream<DynamicTest> createsVanillaContractAsExpectedWithOmittedAdminKey() {
        return defaultHapiSpec("createsVanillaContractAsExpectedWithOmittedAdminKey")
                .given(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                .then(
                        contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).omitAdminKey(),
                        getContractInfo(EMPTY_CONSTRUCTOR_CONTRACT)
                                .has(contractWith().immutableContractKey(EMPTY_CONSTRUCTOR_CONTRACT))
                                .logged());
    }

    @LeakyHapiTest(PROPERTY_OVERRIDES)
    final Stream<DynamicTest> childCreationsHaveExpectedKeysWithOmittedAdminKey() {
        final AtomicLong firstStickId = new AtomicLong();
        final AtomicLong secondStickId = new AtomicLong();
        final AtomicLong thirdStickId = new AtomicLong();
        final var txn = "creation";
        final var contract = "Fuse";

        return propertyPreservingHapiSpec(
                        "ChildCreationsHaveExpectedKeysWithOmittedAdminKey", NONDETERMINISTIC_TRANSACTION_FEES)
                .preserving("contracts.evm.version")
                .given(
                        overriding("contracts.evm.version", "v0.46"),
                        uploadInitCode(contract),
                        contractCreate(contract).omitAdminKey().gas(600_000).via(txn),
                        withOpContext((spec, opLog) -> {
                            final var op = getTxnRecord(txn);
                            allRunFor(spec, op);
                            final var record = op.getResponseRecord();
                            final var creationResult = record.getContractCreateResult();
                            final var createdIds = creationResult.getCreatedContractIDsList();
                            assertEquals(4, createdIds.size(), "Expected four creations but got " + createdIds);
                            firstStickId.set(createdIds.get(1).getContractNum());
                            secondStickId.set(createdIds.get(2).getContractNum());
                            thirdStickId.set(createdIds.get(3).getContractNum());
                        }))
                .when(
                        sourcing(() -> getContractInfo("0.0." + firstStickId.get())
                                .has(contractWith().immutableContractKey("0.0." + firstStickId.get()))
                                .logged()),
                        sourcing(() -> getContractInfo("0.0." + secondStickId.get())
                                .has(contractWith().immutableContractKey("0.0." + secondStickId.get()))
                                .logged()),
                        sourcing(() ->
                                getContractInfo("0.0." + thirdStickId.get()).logged()),
                        contractCall(contract, "light").via("lightTxn"))
                .then(
                        sourcing(() -> getContractInfo("0.0." + firstStickId.get())
                                .has(contractWith().isDeleted())),
                        sourcing(() -> getContractInfo("0.0." + secondStickId.get())
                                .has(contractWith().isDeleted())),
                        sourcing(() -> getContractInfo("0.0." + thirdStickId.get())
                                .has(contractWith().isDeleted())));
    }

    @HapiTest
    final Stream<DynamicTest> createEmptyConstructor() {
        return defaultHapiSpec("createEmptyConstructor", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                .then(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> createCallInConstructor() {
        final var txn = "txn";
        return defaultHapiSpec("callInConstructor")
                .given(uploadInitCode("CallInConstructor"))
                .when()
                .then(
                        contractCreate("CallInConstructor").via(txn).hasKnownStatus(SUCCESS),
                        getTxnRecord(txn).logged(),
                        withOpContext((spec, opLog) -> {
                            final var op = getTxnRecord(txn);
                            allRunFor(spec, op);
                            final var record = op.getResponseRecord();
                            final var creationResult = record.getContractCreateResult();
                            final var createdIds = creationResult.getCreatedContractIDsList();
                            assertEquals(1, createdIds.size(), "Expected one creations but got " + createdIds);
                            assertTrue(
                                    createdIds.get(0).getContractNum() < 10000,
                                    "Expected contract num < 10000 but got " + createdIds);
                        }));
    }

    @HapiTest
    final Stream<DynamicTest> revertedTryExtCallHasNoSideEffects() {
        final var balance = 3_000;
        final int sendAmount = balance / 3;
        final var contract = "RevertingSendTry";
        final var aBeneficiary = "aBeneficiary";
        final var bBeneficiary = "bBeneficiary";
        final var txn = "txn";

        return defaultHapiSpec("RevertedTryExtCallHasNoSideEffects", NONDETERMINISTIC_FUNCTION_PARAMETERS)
                .given(
                        uploadInitCode(contract),
                        contractCreate(contract).balance(balance),
                        cryptoCreate(aBeneficiary).balance(0L),
                        cryptoCreate(bBeneficiary).balance(0L))
                .when(withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var aNum = (int) registry.getAccountID(aBeneficiary).getAccountNum();
                    final var bNum = (int) registry.getAccountID(bBeneficiary).getAccountNum();
                    final var sendArgs =
                            new Object[] {Long.valueOf(sendAmount), Long.valueOf(aNum), Long.valueOf(bNum)};

                    final var op = contractCall(contract, "sendTo", sendArgs)
                            .gas(110_000)
                            .via(txn);
                    allRunFor(spec, op);
                }))
                .then(
                        getTxnRecord(txn).logged(),
                        getAccountBalance(aBeneficiary).logged(),
                        getAccountBalance(bBeneficiary).logged());
    }

    @HapiTest
    final Stream<DynamicTest> createFailsIfMissingSigs() {
        final var shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
        final var validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));
        final var invalidSig = shape.signedWith(sigs(OFF, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

        return defaultHapiSpec("CreateFailsIfMissingSigs", HIGHLY_NON_DETERMINISTIC_FEES)
                .given(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                .then(
                        contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .adminKeyShape(shape)
                                .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, invalidSig))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion(),
                        contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .adminKeyShape(shape)
                                .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, validSig))
                                .hasKnownStatus(SUCCESS)
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInsufficientGas() {
        return defaultHapiSpec("RejectsInsufficientGas")
                .given(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                // refuse eth conversion because ethereum transaction fails in IngestChecker with precheck status
                // INSUFFICIENT_GAS
                .then(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .gas(0L)
                        .hasPrecheck(INSUFFICIENT_GAS)
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInvalidMemo() {
        return defaultHapiSpec("RejectsInvalidMemo")
                .given()
                .when()
                .then(
                        uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                        contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .entityMemo(TxnUtils.nAscii(101))
                                .hasPrecheck(MEMO_TOO_LONG),
                        contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .entityMemo(ZERO_BYTE_MEMO)
                                .hasPrecheck(INVALID_ZERO_BYTE_IN_STRING));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInsufficientFee() {
        return defaultHapiSpec("RejectsInsufficientFee", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(cryptoCreate(PAYER), uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                .then(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .payingWith(PAYER)
                        .fee(1L)
                        .hasPrecheck(INSUFFICIENT_TX_FEE));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInvalidBytecode() {
        final var contract = "InvalidBytecode";
        return defaultHapiSpec("RejectsInvalidBytecode")
                .given(uploadInitCode(contract))
                .when()
                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum transaction
                .then(contractCreate(contract)
                        .hasKnownStatus(ERROR_DECODING_BYTESTRING)
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> revertsNonzeroBalance() {
        return defaultHapiSpec("RevertsNonzeroBalance", HIGHLY_NON_DETERMINISTIC_FEES)
                .given(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                .then(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).balance(1L).hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> delegateContractIdRequiredForTransferInDelegateCall() {
        final var justSendContract = "JustSend";
        final var sendInternalAndDelegateContract = "SendInternalAndDelegate";

        final var beneficiary = "civilian";
        final var totalToSend = 1_000L;
        final var origKey = KeyShape.threshOf(1, SIMPLE, CONTRACT);
        final var revisedKey = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);
        final var newKey = "delegateContractKey";

        final AtomicLong justSendContractNum = new AtomicLong();
        final AtomicLong beneficiaryAccountNum = new AtomicLong();

        return defaultHapiSpec(
                        "DelegateContractIdRequiredForTransferInDelegateCall",
                        NONDETERMINISTIC_TRANSACTION_FEES,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS)
                .given(
                        uploadInitCode(justSendContract, sendInternalAndDelegateContract),
                        // refuse eth conversion because we can't delegate call contract by contract num
                        // when it has EVM address alias (isNotPriority check fails)
                        contractCreate(justSendContract)
                                .gas(300_000L)
                                .exposingNumTo(justSendContractNum::set)
                                .refusingEthConversion(),
                        contractCreate(sendInternalAndDelegateContract)
                                .gas(300_000L)
                                .balance(2 * totalToSend))
                .when(cryptoCreate(beneficiary)
                        .balance(0L)
                        .keyShape(origKey.signedWith(sigs(ON, sendInternalAndDelegateContract)))
                        .receiverSigRequired(true)
                        .exposingCreatedIdTo(id -> beneficiaryAccountNum.set(id.getAccountNum())))
                .then(
                        /* Without delegateContractId permissions, the second send via delegate call will
                         * fail, so only half of totalToSend will make it to the beneficiary. (Note the entire
                         * call doesn't fail because exceptional halts in "raw calls" don't automatically
                         * propagate up the stack like a Solidity revert does.) */
                        sourcing(() -> contractCall(
                                sendInternalAndDelegateContract,
                                "sendRepeatedlyTo",
                                BigInteger.valueOf(justSendContractNum.get()),
                                BigInteger.valueOf(beneficiaryAccountNum.get()),
                                BigInteger.valueOf(totalToSend / 2))),
                        getAccountBalance(beneficiary).hasTinyBars(totalToSend / 2),
                        /* But now we update the beneficiary to have a delegateContractId */
                        newKeyNamed(newKey).shape(revisedKey.signedWith(sigs(ON, sendInternalAndDelegateContract))),
                        cryptoUpdate(beneficiary).key(newKey),
                        sourcing(() -> contractCall(
                                sendInternalAndDelegateContract,
                                "sendRepeatedlyTo",
                                BigInteger.valueOf(justSendContractNum.get()),
                                BigInteger.valueOf(beneficiaryAccountNum.get()),
                                BigInteger.valueOf(totalToSend / 2))),
                        getAccountBalance(beneficiary).hasTinyBars(3 * (totalToSend / 2)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotCreateTooLargeContract() {
        ByteString contents;
        try {
            contents = ByteString.copyFrom(Files.readAllBytes(Path.of(bytecodePath("CryptoKitties"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final var FILE_KEY = "fileKey";
        final var KEY_LIST = "keyList";
        final var ACCOUNT = "acc";
        return defaultHapiSpec("cannotCreateTooLargeContract", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        newKeyNamed(FILE_KEY),
                        newKeyListNamed(KEY_LIST, List.of(FILE_KEY)),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS * 10).key(FILE_KEY),
                        fileCreate("bytecode")
                                .path(bytecodePath("CryptoKitties"))
                                .hasPrecheck(TRANSACTION_OVERSIZE)
                                // Modularized code will not allow a message larger than 6144 bytes at all
                                .orUnavailableStatus())
                .when(
                        fileCreate("bytecode").contents("").key(KEY_LIST),
                        UtilVerbs.updateLargeFile(ACCOUNT, "bytecode", contents))
                .then(contractCreate("contract")
                        .bytecode("bytecode")
                        .payingWith(ACCOUNT)
                        .hasKnownStatus(INSUFFICIENT_GAS)
                        // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                        // transaction
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> blockTimestampChangesWithinFewSeconds() {
        final var contract = "EmitBlockTimestamp";
        final var firstBlock = "firstBlock";
        final var timeLoggingTxn = "timeLoggingTxn";

        return defaultHapiSpec(
                        "blockTimestampChangesWithinFewSeconds",
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS,
                        NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(contract), contractCreate(contract))
                .when(
                        contractCall(contract, "logNow").via(firstBlock),
                        cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(GENESIS, FUNDING, 1)),
                        sleepFor(3_000),
                        contractCall(contract, "logNow").via(timeLoggingTxn))
                .then(
                        withOpContext((spec, opLog) -> {
                            final var firstBlockOp = getTxnRecord(firstBlock);
                            final var recordOp = getTxnRecord(timeLoggingTxn);
                            allRunFor(spec, firstBlockOp, recordOp);

                            // First block info
                            final var firstBlockRecord = firstBlockOp.getResponseRecord();
                            final var firstBlockLogs =
                                    firstBlockRecord.getContractCallResult().getLogInfoList();
                            final var firstBlockTimeLogData =
                                    firstBlockLogs.get(0).getData().toByteArray();
                            final var firstBlockTimestamp =
                                    Longs.fromByteArray(Arrays.copyOfRange(firstBlockTimeLogData, 24, 32));
                            final var firstBlockHashLogData =
                                    firstBlockLogs.get(1).getData().toByteArray();
                            final var firstBlockNumber =
                                    Longs.fromByteArray(Arrays.copyOfRange(firstBlockHashLogData, 24, 32));
                            final var firstBlockHash = Bytes32.wrap(Arrays.copyOfRange(firstBlockHashLogData, 32, 64));
                            assertEquals(Bytes32.ZERO, firstBlockHash);

                            // Second block info
                            final var secondBlockRecord = recordOp.getResponseRecord();
                            final var secondBlockLogs =
                                    secondBlockRecord.getContractCallResult().getLogInfoList();
                            assertEquals(2, secondBlockLogs.size());
                            final var secondBlockTimeLogData =
                                    secondBlockLogs.get(0).getData().toByteArray();
                            final var secondBlockTimestamp =
                                    Longs.fromByteArray(Arrays.copyOfRange(secondBlockTimeLogData, 24, 32));
                            assertNotEquals(
                                    firstBlockTimestamp, secondBlockTimestamp, "Block timestamps should change");

                            final var secondBlockHashLogData =
                                    secondBlockLogs.get(1).getData().toByteArray();
                            final var secondBlockNumber =
                                    Longs.fromByteArray(Arrays.copyOfRange(secondBlockHashLogData, 24, 32));
                            assertNotEquals(firstBlockNumber, secondBlockNumber, "Wrong previous block number");
                            final var secondBlockHash =
                                    Bytes32.wrap(Arrays.copyOfRange(secondBlockHashLogData, 32, 64));

                            assertEquals(Bytes32.ZERO, secondBlockHash);
                        }),
                        contractCallLocal(contract, "getLastBlockHash")
                                .exposingTypedResultsTo(
                                        results -> log.info("Results were {}", CommonUtils.hex((byte[]) results[0]))));
    }

    @HapiTest
    final Stream<DynamicTest> vanillaSuccess() {
        final var contract = "CreateTrivial";
        return defaultHapiSpec(
                        "VanillaSuccess",
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS,
                        NONDETERMINISTIC_TRANSACTION_FEES,
                        NONDETERMINISTIC_NONCE)
                .given(
                        uploadInitCode(contract),
                        // refuse eth conversion because ethereum transaction is missing admin key
                        contractCreate(contract).adminKey(THRESHOLD).refusingEthConversion(),
                        getContractInfo(contract).saveToRegistry(PARENT_INFO))
                .when(
                        contractCall(contract, "create").gas(1_000_000L).via("createChildTxn"),
                        contractCall(contract, "getIndirect").gas(1_000_000L).via("getChildResultTxn"),
                        contractCall(contract, "getAddress").gas(1_000_000L).via("getChildAddressTxn"))
                .then(
                        getTxnRecord("createChildTxn")
                                .saveCreatedContractListToRegistry("createChild")
                                .logged(),
                        getTxnRecord("getChildResultTxn")
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "getIndirect", contract),
                                                        isLiteralResult(new Object[] {BigInteger.valueOf(7L)})))),
                        getTxnRecord("getChildAddressTxn")
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "getAddress", contract),
                                                        isContractWith(contractWith()
                                                                .nonNullContractId()
                                                                .propertiesInheritedFrom(PARENT_INFO)))
                                                .logs(inOrder()))),
                        contractListWithPropertiesInheritedFrom("createChildCallResult", 1, PARENT_INFO));
    }

    @HapiTest
    final Stream<DynamicTest> newAccountsCanUsePureContractIdKey() {
        final var contract = "CreateTrivial";
        final var contractControlled = "contractControlled";
        return defaultHapiSpec("NewAccountsCanUsePureContractIdKey", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        uploadInitCode(contract),
                        contractCreate(contract),
                        cryptoCreate(contractControlled).keyShape(CONTRACT.signedWith(contract)))
                .when()
                .then(withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var contractIdKey = Key.newBuilder()
                            .setContractID(registry.getContractId(contract))
                            .build();
                    final var keyCheck =
                            getAccountInfo(contractControlled).has(accountWith().key(contractIdKey));
                    allRunFor(spec, keyCheck);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var autoRenewAccount = "autoRenewAccount";
        final var creationNumber = new AtomicLong();
        final var contract = "CreateTrivial";
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given(uploadInitCode(contract), cryptoCreate(autoRenewAccount).balance(ONE_HUNDRED_HBARS))
                .when()
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> contractCreate(
                                "contract" + creationNumber.getAndIncrement())
                        .bytecode(contract)
                        .autoRenewAccountId(autoRenewAccount)));
    }

    @HapiTest
    final Stream<DynamicTest> contractWithAutoRenewNeedSignatures() {
        final var contract = "CreateTrivial";
        final var autoRenewAccount = "autoRenewAccount";
        return defaultHapiSpec("contractWithAutoRenewNeedSignatures", HIGHLY_NON_DETERMINISTIC_FEES)
                .given(
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(contract),
                        cryptoCreate(autoRenewAccount).balance(ONE_HUNDRED_HBARS),
                        // refuse eth conversion because ethereum transaction is missing autoRenewAccountId field to map
                        contractCreate(contract)
                                .adminKey(ADMIN_KEY)
                                .autoRenewAccountId(autoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .refusingEthConversion(),
                        contractCreate(contract)
                                .adminKey(ADMIN_KEY)
                                .autoRenewAccountId(autoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY, autoRenewAccount)
                                .refusingEthConversion()
                                .logged(),
                        getContractInfo(contract)
                                .has(ContractInfoAsserts.contractWith().autoRenewAccountId(autoRenewAccount))
                                .logged())
                .when()
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> cannotSetMaxAutomaticAssociations() {
        return defaultHapiSpec("cannotSetMaxAutomaticAssociations")
                .given(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT))
                .when()
                .then(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .maxAutomaticTokenAssociations(10)
                        .hasKnownStatus(NOT_SUPPORTED));
    }

    private EthTxData placeholderEthTx() {
        return new EthTxData(
                null,
                EthTxData.EthTransactionType.EIP1559,
                Integers.toBytes(CHAIN_ID),
                0L,
                BigInteger.ONE.toByteArray(),
                BigInteger.ONE.toByteArray(),
                BigInteger.ONE.toByteArray(),
                150_000,
                new byte[] {1, 2, 3},
                BigInteger.ONE,
                new byte[] {},
                new byte[] {},
                0,
                null,
                null,
                null);
    }
}
