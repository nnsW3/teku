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

package tech.pegasys.teku.statetransition.blobs;

import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.ethereum.events.SlotEventsChannel;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.subscribers.Subscribers;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.kzg.KZG;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blobs.versions.deneb.BlobSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.logic.versions.deneb.blobs.BlobSidecarsAndValidationResult;
import tech.pegasys.teku.spec.logic.versions.deneb.blobs.BlobSidecarsAvailabilityChecker;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoiceBlobSidecarsAvailabilityChecker;
import tech.pegasys.teku.statetransition.util.FutureItems;
import tech.pegasys.teku.statetransition.validation.BlobSidecarGossipValidator;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.storage.client.RecentChainData;

public class BlobSidecarManagerImpl implements BlobSidecarManager, SlotEventsChannel {

  private final Spec spec;
  private final RecentChainData recentChainData;
  private final BlobSidecarGossipValidator validator;
  private final BlockBlobSidecarsTrackersPool blockBlobSidecarsTrackersPool;
  private final FutureItems<BlobSidecar> futureBlobSidecars;
  private final Map<Bytes32, InternalValidationResult> invalidBlobSidecarRoots;
  private final ForkChoiceBlobSidecarsAvailabilityCheckerProvider
      forkChoiceBlobSidecarsAvailabilityCheckerProvider;
  private final UnpooledBlockBlobSidecarsTrackerProvider unpooledBlockBlobSidecarsTrackerProvider;

  private final Subscribers<ReceivedBlobSidecarListener> receivedBlobSidecarSubscribers =
      Subscribers.create(true);

  public BlobSidecarManagerImpl(
      final Spec spec,
      final RecentChainData recentChainData,
      final BlockBlobSidecarsTrackersPool blockBlobSidecarsTrackersPool,
      final BlobSidecarGossipValidator validator,
      final KZG kzg,
      final FutureItems<BlobSidecar> futureBlobSidecars,
      final Map<Bytes32, InternalValidationResult> invalidBlobSidecarRoots) {
    this(
        spec,
        recentChainData,
        blockBlobSidecarsTrackersPool,
        validator,
        futureBlobSidecars,
        invalidBlobSidecarRoots,
        (tracker) ->
            new ForkChoiceBlobSidecarsAvailabilityChecker(spec, recentChainData, tracker, kzg),
        (block) -> new BlockBlobSidecarsTracker(block.getSlotAndBlockRoot()));
  }

  @VisibleForTesting
  BlobSidecarManagerImpl(
      final Spec spec,
      final RecentChainData recentChainData,
      final BlockBlobSidecarsTrackersPool blockBlobSidecarsTrackersPool,
      final BlobSidecarGossipValidator validator,
      final FutureItems<BlobSidecar> futureBlobSidecars,
      final Map<Bytes32, InternalValidationResult> invalidBlobSidecarRoots,
      final ForkChoiceBlobSidecarsAvailabilityCheckerProvider
          forkChoiceBlobSidecarsAvailabilityCheckerProvider,
      final UnpooledBlockBlobSidecarsTrackerProvider unpooledBlockBlobSidecarsTrackerProvider) {
    this.spec = spec;
    this.recentChainData = recentChainData;
    this.validator = validator;
    this.blockBlobSidecarsTrackersPool = blockBlobSidecarsTrackersPool;
    this.futureBlobSidecars = futureBlobSidecars;
    this.invalidBlobSidecarRoots = invalidBlobSidecarRoots;
    this.forkChoiceBlobSidecarsAvailabilityCheckerProvider =
        forkChoiceBlobSidecarsAvailabilityCheckerProvider;
    this.unpooledBlockBlobSidecarsTrackerProvider = unpooledBlockBlobSidecarsTrackerProvider;
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  public SafeFuture<InternalValidationResult> validateAndPrepareForBlockImport(
      final BlobSidecar blobSidecar, final Optional<UInt64> arrivalTimestamp) {

    final Optional<InternalValidationResult> maybeInvalid =
        Optional.ofNullable(invalidBlobSidecarRoots.get(blobSidecar.hashTreeRoot()));
    if (maybeInvalid.isPresent()) {
      return SafeFuture.completedFuture(maybeInvalid.get());
    }

    final SafeFuture<InternalValidationResult> validationResult = validator.validate(blobSidecar);

    validationResult.thenAccept(
        result -> {
          switch (result.code()) {
            case IGNORE:
              // do nothing
              break;
            case REJECT:
              invalidBlobSidecarRoots.put(blobSidecar.hashTreeRoot(), result);
              break;
            case SAVE_FOR_FUTURE:
              futureBlobSidecars.add(blobSidecar);
              break;
            case ACCEPT:
              prepareForBlockImport(blobSidecar, RemoteOrigin.GOSSIP);
              break;
          }
        });

    return validationResult;
  }

  @Override
  public void prepareForBlockImport(final BlobSidecar blobSidecar, final RemoteOrigin origin) {
    blockBlobSidecarsTrackersPool.onNewBlobSidecar(blobSidecar, origin);
    receivedBlobSidecarSubscribers.forEach(s -> s.onBlobSidecarReceived(blobSidecar));
  }

  @Override
  public void subscribeToReceivedBlobSidecar(
      final ReceivedBlobSidecarListener receivedBlobSidecarListener) {
    receivedBlobSidecarSubscribers.subscribe(receivedBlobSidecarListener);
  }

  @Override
  public boolean isAvailabilityRequiredAtSlot(final UInt64 slot) {
    return spec.isAvailabilityOfBlobSidecarsRequiredAtSlot(recentChainData.getStore(), slot);
  }

  @Override
  public BlobSidecarsAvailabilityChecker createAvailabilityChecker(final SignedBeaconBlock block) {
    // Block is pre-Deneb, blobs are not supported yet
    if (block.getMessage().getBody().toVersionDeneb().isEmpty()) {
      return BlobSidecarsAvailabilityChecker.NOT_REQUIRED;
    }

    final BlockBlobSidecarsTracker blockBlobSidecarsTracker =
        blockBlobSidecarsTrackersPool.getOrCreateBlockBlobSidecarsTracker(block);

    return forkChoiceBlobSidecarsAvailabilityCheckerProvider.create(blockBlobSidecarsTracker);
  }

  @Override
  public BlobSidecarsAndValidationResult createAvailabilityCheckerAndValidateImmediately(
      final SignedBeaconBlock block, final List<BlobSidecar> blobSidecars) {
    // Block is pre-Deneb, blobs are not supported yet
    if (block.getMessage().getBody().toVersionDeneb().isEmpty()) {
      return BlobSidecarsAndValidationResult.NOT_REQUIRED;
    }

    final BlockBlobSidecarsTracker blockBlobSidecarsTracker =
        unpooledBlockBlobSidecarsTrackerProvider.create(block);
    blockBlobSidecarsTracker.setBlock(block);

    boolean allAdded = blobSidecars.stream().allMatch(blockBlobSidecarsTracker::add);
    if (!allAdded) {
      return BlobSidecarsAndValidationResult.invalidResult(
          blobSidecars,
          new IllegalStateException(
              "Failed to add all blobs to tracker, possible blobs with same index or index out of blocks commitment range"));
    }

    if (!blockBlobSidecarsTracker.isComplete()) {
      return BlobSidecarsAndValidationResult.NOT_AVAILABLE;
    }

    final ForkChoiceBlobSidecarsAvailabilityChecker forkChoiceBlobSidecarsAvailabilityChecker =
        forkChoiceBlobSidecarsAvailabilityCheckerProvider.create(blockBlobSidecarsTracker);

    forkChoiceBlobSidecarsAvailabilityChecker.initiateDataAvailabilityCheck();

    final SafeFuture<BlobSidecarsAndValidationResult> availabilityCheckResult =
        forkChoiceBlobSidecarsAvailabilityChecker.getAvailabilityCheckResult();

    if (availabilityCheckResult.isDone()) {
      return availabilityCheckResult.join();
    } else {
      throw new IllegalStateException(
          "Availability check expected to be done synchronously when providing immediate blobs");
    }
  }

  @Override
  public void onSlot(final UInt64 slot) {
    blockBlobSidecarsTrackersPool.onSlot(slot);

    futureBlobSidecars.onSlot(slot);
    futureBlobSidecars
        .prune(slot)
        .forEach(
            blobSidecar ->
                validateAndPrepareForBlockImport(blobSidecar, Optional.empty())
                    .ifExceptionGetsHereRaiseABug());
  }

  @VisibleForTesting
  @FunctionalInterface
  interface ForkChoiceBlobSidecarsAvailabilityCheckerProvider {
    ForkChoiceBlobSidecarsAvailabilityChecker create(
        final BlockBlobSidecarsTracker blockBlobSidecarsTracker);
  }

  @VisibleForTesting
  @FunctionalInterface
  interface UnpooledBlockBlobSidecarsTrackerProvider {
    BlockBlobSidecarsTracker create(final SignedBeaconBlock block);
  }
}
