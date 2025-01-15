/*
 * Copyright Consensys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.ethereum.executionclient.rest;

import static tech.pegasys.teku.infrastructure.http.RestApiConstants.HEADER_CONSENSUS_VERSION;
import static tech.pegasys.teku.spec.config.Constants.BUILDER_GET_PAYLOAD_TIMEOUT;
import static tech.pegasys.teku.spec.config.Constants.BUILDER_PROPOSAL_DELAY_TOLERANCE;
import static tech.pegasys.teku.spec.config.Constants.BUILDER_REGISTER_VALIDATOR_TIMEOUT;
import static tech.pegasys.teku.spec.config.Constants.BUILDER_STATUS_TIMEOUT;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.ethereum.executionclient.BuilderApiMethod;
import tech.pegasys.teku.ethereum.executionclient.BuilderClient;
import tech.pegasys.teku.ethereum.executionclient.rest.RestClient.ResponseSchemaAndDeserializableTypeDefinition;
import tech.pegasys.teku.ethereum.executionclient.schema.BuilderApiResponse;
import tech.pegasys.teku.ethereum.executionclient.schema.Response;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.json.types.DeserializableTypeDefinition;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.infrastructure.version.VersionProvider;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.builder.BuilderPayload;
import tech.pegasys.teku.spec.datastructures.builder.BuilderPayloadSchema;
import tech.pegasys.teku.spec.datastructures.builder.SignedBuilderBid;
import tech.pegasys.teku.spec.datastructures.builder.SignedBuilderBidSchema;
import tech.pegasys.teku.spec.datastructures.builder.SignedValidatorRegistration;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsBellatrix;

public class RestBuilderClient implements BuilderClient {

  private static final Map.Entry<String, String> USER_AGENT_HEADER =
      Map.entry(
          "User-Agent",
          VersionProvider.CLIENT_IDENTITY + "/" + VersionProvider.IMPLEMENTATION_VERSION);

  private static final Map.Entry<String, String> ACCEPT_HEADER =
      Map.entry("Accept", "application/octet-stream;q=1.0,application/json;q=0.9");

  private static final Map<String, String> GET_HEADER_HTTP_HEADERS_WITH_USER_AGENT =
      Map.ofEntries(USER_AGENT_HEADER, ACCEPT_HEADER);
  private static final Map<String, String> GET_HEADER_HTTP_HEADERS_WITHOUT_USER_AGENT =
      Map.ofEntries(ACCEPT_HEADER);

  private static final AtomicBoolean LAST_RECEIVED_HEADER_WAS_IN_SSZ = new AtomicBoolean(false);

  private final Map<
          SpecMilestone, ResponseSchemaAndDeserializableTypeDefinition<? extends BuilderPayload>>
      cachedBuilderApiPayloadResponseType = new ConcurrentHashMap<>();

  private final Map<SpecMilestone, ResponseSchemaAndDeserializableTypeDefinition<SignedBuilderBid>>
      cachedBuilderApiSignedBuilderBidResponseType = new ConcurrentHashMap<>();

  private final RestClient restClient;
  private final Spec spec;
  private final boolean setUserAgentHeader;

  public RestBuilderClient(
      final RestClient restClient, final Spec spec, final boolean setUserAgentHeader) {
    this.restClient = restClient;
    this.spec = spec;
    this.setUserAgentHeader = setUserAgentHeader;
  }

  @Override
  public SafeFuture<Response<Void>> status() {
    return restClient
        .getAsync(BuilderApiMethod.GET_STATUS.getPath())
        .orTimeout(BUILDER_STATUS_TIMEOUT);
  }

  @Override
  public SafeFuture<Response<Void>> registerValidators(
      final UInt64 slot, final SszList<SignedValidatorRegistration> signedValidatorRegistrations) {

    if (signedValidatorRegistrations.isEmpty()) {
      return SafeFuture.completedFuture(Response.fromNullPayload());
    }

    return restClient
        .postAsync(
            BuilderApiMethod.REGISTER_VALIDATOR.getPath(), signedValidatorRegistrations, false)
        .orTimeout(BUILDER_REGISTER_VALIDATOR_TIMEOUT);
  }

  @Override
  public SafeFuture<Response<Optional<SignedBuilderBid>>> getHeader(
      final UInt64 slot, final BLSPublicKey pubKey, final Bytes32 parentHash) {

    final Map<String, String> urlParams = new HashMap<>();
    urlParams.put("slot", slot.toString());
    urlParams.put("parent_hash", parentHash.toHexString());
    urlParams.put("pubkey", pubKey.toBytesCompressed().toHexString());

    final SpecVersion specVersion = spec.atSlot(slot);
    final SpecMilestone milestone = specVersion.getMilestone();

    final ResponseSchemaAndDeserializableTypeDefinition<SignedBuilderBid> responseTypeDefinition =
        cachedBuilderApiSignedBuilderBidResponseType.computeIfAbsent(
            milestone,
            __ -> {
              final SchemaDefinitionsBellatrix schemaDefinitionsBellatrix =
                  getSchemaDefinitionsBellatrix(specVersion);
              final SignedBuilderBidSchema signedBuilderBidSchema =
                  schemaDefinitionsBellatrix.getSignedBuilderBidSchema();

              return new ResponseSchemaAndDeserializableTypeDefinition<>(
                  signedBuilderBidSchema,
                  BuilderApiResponse.createTypeDefinition(
                      signedBuilderBidSchema.getJsonTypeDefinition()));
            });

    return restClient
        .getAsync(
            BuilderApiMethod.GET_HEADER.resolvePath(urlParams),
            setUserAgentHeader
                ? GET_HEADER_HTTP_HEADERS_WITH_USER_AGENT
                : GET_HEADER_HTTP_HEADERS_WITHOUT_USER_AGENT,
            responseTypeDefinition)
        .orTimeout(BUILDER_PROPOSAL_DELAY_TOLERANCE)
        .thenApply(
            response ->
                response.unwrapVersioned(
                    this::extractSignedBuilderBid, milestone, BuilderApiResponse::version, true))
        .thenApply(Response::convertToOptional)
        .thenPeek(response -> LAST_RECEIVED_HEADER_WAS_IN_SSZ.set(response.receivedAsSsz()));
  }

  @Override
  public SafeFuture<Response<BuilderPayload>> getPayload(
      final SignedBeaconBlock signedBlindedBeaconBlock) {

    final UInt64 blockSlot = signedBlindedBeaconBlock.getSlot();
    final SpecVersion specVersion = spec.atSlot(blockSlot);
    final SpecMilestone milestone = specVersion.getMilestone();
    final SchemaDefinitionsBellatrix schemaDefinitionsBellatrix =
        getSchemaDefinitionsBellatrix(specVersion);

    final ResponseSchemaAndDeserializableTypeDefinition<? extends BuilderPayload>
        responseTypeDefinition =
            cachedBuilderApiPayloadResponseType.computeIfAbsent(
                milestone,
                __ -> payloadTypeDefinition(schemaDefinitionsBellatrix.getBuilderPayloadSchema()));

    return restClient
        .postAsync(
            BuilderApiMethod.GET_PAYLOAD.getPath(),
            Map.of(HEADER_CONSENSUS_VERSION, milestone.name().toLowerCase(Locale.ROOT)),
            signedBlindedBeaconBlock,
            LAST_RECEIVED_HEADER_WAS_IN_SSZ.get(),
            responseTypeDefinition)
        .thenApply(
            response ->
                response.unwrapVersioned(
                    this::extractBuilderPayload, milestone, BuilderApiResponse::version, false))
        .orTimeout(BUILDER_GET_PAYLOAD_TIMEOUT);
  }

  private <T extends BuilderPayload>
      ResponseSchemaAndDeserializableTypeDefinition<T> payloadTypeDefinition(
          final BuilderPayloadSchema<T> schema) {
    final DeserializableTypeDefinition<T> typeDefinition = schema.getJsonTypeDefinition();
    return new ResponseSchemaAndDeserializableTypeDefinition<>(
        schema, BuilderApiResponse.createTypeDefinition(typeDefinition));
  }

  private <T extends SignedBuilderBid> SignedBuilderBid extractSignedBuilderBid(
      final BuilderApiResponse<T> builderApiResponse) {
    return builderApiResponse.data();
  }

  private <T extends BuilderPayload> BuilderPayload extractBuilderPayload(
      final BuilderApiResponse<T> builderApiResponse) {
    return builderApiResponse.data();
  }

  private SchemaDefinitionsBellatrix getSchemaDefinitionsBellatrix(final SpecVersion specVersion) {
    return specVersion
        .getSchemaDefinitions()
        .toVersionBellatrix()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    specVersion.getMilestone()
                        + " is not a supported milestone for the builder rest api. Milestones >= Bellatrix are supported."));
  }
}
