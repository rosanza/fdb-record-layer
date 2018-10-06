/*
 * OnlineIndexBuilderBase.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.provider.foundationdb;

import com.apple.foundationdb.FDBException;
import com.apple.foundationdb.Range;
import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.async.AsyncIterator;
import com.apple.foundationdb.async.AsyncUtil;
import com.apple.foundationdb.async.MoreAsyncUtil;
import com.apple.foundationdb.async.RangeSet;
import com.apple.foundationdb.record.EndpointType;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.IsolationLevel;
import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCoreStorageException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.logging.KeyValueLogMessage;
import com.apple.foundationdb.record.logging.LogMessageKeys;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.MetaDataException;
import com.apple.foundationdb.record.metadata.RecordType;
import com.apple.foundationdb.record.provider.common.RecordSerializer;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.ByteArrayUtil2;
import com.apple.foundationdb.tuple.Tuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Builds an index online, i.e., concurrently with other database operations. In order to minimize
 * the impact that these operations have with other operations, this attempts to minimize the
 * priorities of its transactions. Additionally, it attempts to limit the amount of work it will
 * done in a fashion that will decrease as the number of failures for a given build attempt increases.
 *
 * <p>
 * As ranges of elements are rebuilt, the fact that the range has rebuilt is added to a {@link RangeSet}
 * associated with the index being built. This {@link RangeSet} is used to (a) coordinate work between
 * different builders that might be running on different machines to ensure that the same work isn't
 * duplicated and to (b) make sure that non-idempotent indexes (like <code>COUNT</code> or <code>SUM_LONG</code>)
 * don't update themselves (or fail to update themselves) incorrectly.
 * </p>
 *
 * <p>
 * Unlike many other features in the Record Layer core, this has a retry loop.
 * </p>
 * @param <M> type used to represent stored records
 */
public class OnlineIndexBuilderBase<M extends Message> implements AutoCloseable {
    /**
     * Default number of records to attempt to run in a single transaction.
     */
    public static final int DEFAULT_LIMIT = 100;
    /**
     * Default limit to the number of records to attempt in a single second.
     */
    public static final int DEFAULT_RECORDS_PER_SECOND = 10_000;
    /**
     * Default number of times to retry a single range rebuild.
     */
    public static final int DEFAULT_MAX_RETRIES = 100;
    /**
     * Constant indicating that there should be no limit to some usually limited operation.
     */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    @Nonnull private static final byte[] START_BYTES = new byte[]{0x00};
    @Nonnull private static final byte[] END_BYTES = new byte[]{(byte)0xff};
    @Nonnull private static final Logger LOGGER = LoggerFactory.getLogger(OnlineIndexBuilderBase.class);

    // These error codes represent a list of errors that can occur if there is too much work to be done
    // in a single transaction.
    private static final Set<Integer> lessenWorkCodes = new HashSet<>(Arrays.asList(1004, 1007, 1020, 1031, 2002, 2101));

    @Nonnull private final FDBDatabaseRunner runner;
    @Nonnull private final FDBRecordStoreBuilder<M, ? extends FDBRecordStoreBase<M>> recordStoreBuilder;
    @Nonnull private final Index index;
    @Nonnull private final Collection<RecordType> recordTypes;
    @Nonnull private final TupleRange recordsRange;
    private int limit;
    private int maxRetries;
    private int recordsPerSecond;

    /**
     * This {@link Exception} can be thrown in the case that one calls one of the methods
     * that explicitly state that they are building an unbuilt range, i.e., a range of keys
     * that contains no keys which have yet been processed by the {@link OnlineIndexBuilderBase}
     * during an index build.
     */
    @SuppressWarnings("serial")
    public static class RecordBuiltRangeException extends RecordCoreException {
        public RecordBuiltRangeException(@Nullable Tuple start, @Nullable Tuple end) {
            super("Range specified as unbuilt contained subranges that had already been built");
            addLogInfo(LogMessageKeys.RANGE_START, start);
            addLogInfo(LogMessageKeys.RANGE_END, end);
        }
    }

    /**
     * Creates an <code>OnlineIndexBuilder</code> to construct an index within a record store built from the given store builder.
     * This constructor also lets the user set a few parameters that affect rate-limiting and error handling when the index is built.
     * Setting these parameters to {@link OnlineIndexBuilderBase#UNLIMITED OnlineIndexBuilder.UNLIMITED} will cause these limits to be ignored.
     *
     * @param fdb database that contains the record store
     * @param recordStoreBuilder builder to use to open the record store
     * @param index the index to build
     * @param recordTypes the record types for which to rebuild the index or {@code null} for all to which it applies
     * @param limit maximum number of records to process in one transaction
     * @param maxRetries maximum number of times to retry a single range rebuild
     * @param recordsPerSecond maximum number of records to process in a single second
     */
    public OnlineIndexBuilderBase(@Nonnull FDBDatabase fdb, @Nonnull FDBRecordStoreBuilder<M, ? extends FDBRecordStoreBase<M>> recordStoreBuilder,
                                  @Nonnull Index index, @Nullable Collection<RecordType> recordTypes,
                                  int limit, int maxRetries, int recordsPerSecond) {
        this.runner = fdb.newRunner();
        this.recordStoreBuilder = recordStoreBuilder.copyBuilder().setContext(null);
        this.index = index;
        this.recordTypes = validateIndex(recordTypes);
        this.recordsRange = computeRecordsRange();
        this.limit = limit;
        this.maxRetries = maxRetries;
        this.recordsPerSecond = recordsPerSecond;
        validateLimits();
    }

    /**
     * Creates an <code>OnlineIndexBuilder</code> to construct an index within a record store built from the given store builder.
     * Default values are used for the parameters to tune rate-limiting and error handling within the index builder.
     * These values are:
     * <ul>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_LIMIT DEFAULT_LIMIT}: {@value DEFAULT_LIMIT}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_MAX_RETRIES DEFAULT_MAX_RETRIES}: {@value DEFAULT_MAX_RETRIES}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_RECORDS_PER_SECOND DEFAULT_RECORDS_PER_SECOND}: {@value DEFAULT_RECORDS_PER_SECOND}</li>
     * </ul>
     *
     * @param fdb database that contains the record store
     * @param recordStoreBuilder builder to use to open the record store
     * @param index the index to build
     * @param recordTypes the record types for which to rebuild the index or {@code null} for all to which it applies
     */
    public OnlineIndexBuilderBase(@Nonnull FDBDatabase fdb, @Nonnull FDBRecordStoreBuilder<M, ? extends FDBRecordStoreBase<M>> recordStoreBuilder,
                                  @Nonnull Index index, @Nullable Collection<RecordType> recordTypes) {
        this(fdb, recordStoreBuilder, index, recordTypes, DEFAULT_LIMIT, DEFAULT_MAX_RETRIES, DEFAULT_RECORDS_PER_SECOND);
    }

    /**
     * Creates an <code>OnlineIndexBuilder</code> to construct an index within a record store built from the given store builder.
     * Default values are used for the parameters to tune rate-limiting and error handling within the index builder.
     * These values are:
     * <ul>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_LIMIT DEFAULT_LIMIT}: {@value DEFAULT_LIMIT}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_MAX_RETRIES DEFAULT_MAX_RETRIES}: {@value DEFAULT_MAX_RETRIES}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_RECORDS_PER_SECOND DEFAULT_RECORDS_PER_SECOND}: {@value DEFAULT_RECORDS_PER_SECOND}</li>
     * </ul>
     *
     * @param fdb database that contains the record store
     * @param recordStoreBuilder builder to use to open the record store
     * @param index the index to build
     */
    public OnlineIndexBuilderBase(@Nonnull FDBDatabase fdb, @Nonnull FDBRecordStoreBuilder<M, ? extends FDBRecordStoreBase<M>> recordStoreBuilder, @Nonnull Index index) {
        this(fdb, recordStoreBuilder, index, null);
    }

    /**
     * Creates an <code>OnlineIndexBuilder</code> to construct an index within the given record store.
     * This constructor also lets the user set a few parameters that affect rate-limiting and error handling when the index is built.
     * Setting these parameters to {@link OnlineIndexBuilderBase#UNLIMITED OnlineIndexBuilder.UNLIMITED} will cause these limits to be ignored.
     *
     * @param recordStore the record store to use as a prototype
     * @param index the index to build
     * @param recordTypes the record types for which to rebuild the index or {@code null} for all to which it applies
     * @param limit maximum number of records to process in one transaction
     * @param maxRetries maximum number of times to retry a single range rebuild
     * @param recordsPerSecond maximum number of records to process in a single second
     */
    public OnlineIndexBuilderBase(@Nonnull FDBRecordStoreBase<M> recordStore,
                                  @Nonnull Index index, @Nullable Collection<RecordType> recordTypes,
                                  int limit, int maxRetries, int recordsPerSecond) {
        this(recordStore.getRecordContext().getDatabase(), recordStore.asBuilder(),
                index, recordTypes, limit, maxRetries, recordsPerSecond);
        setTimer(recordStore.getTimer());
        setMdcContext(recordStore.getRecordContext().getMdcContext());
    }

    /**
     * Creates an <code>OnlineIndexBuilder</code> to construct an index within the given record store.
     * Default values are used for the parameters to tune rate-limiting and error handling within the index builder.
     * These values are:
     * <ul>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_LIMIT DEFAULT_LIMIT}: {@value DEFAULT_LIMIT}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_MAX_RETRIES DEFAULT_MAX_RETRIES}: {@value DEFAULT_MAX_RETRIES}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_RECORDS_PER_SECOND DEFAULT_RECORDS_PER_SECOND}: {@value DEFAULT_RECORDS_PER_SECOND}</li>
     * </ul>
     *
     * @param recordStore the record store to use as a prototype
     * @param index the index to build
     * @param recordTypes the record types for which to rebuild the index or {@code null} for all to which it applies
     */
    public OnlineIndexBuilderBase(@Nonnull FDBRecordStoreBase<M> recordStore,
                                  @Nonnull Index index, @Nullable Collection<RecordType> recordTypes) {
        this(recordStore, index, recordTypes, DEFAULT_LIMIT, DEFAULT_MAX_RETRIES, DEFAULT_RECORDS_PER_SECOND);
    }

    /**
     * Creates an <code>OnlineIndexBuilder</code> to construct an index within the given record store.
     * Default values are used for the parameters to tune rate-limiting and error handling within the index builder.
     * These values are:
     * <ul>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_LIMIT DEFAULT_LIMIT}: {@value DEFAULT_LIMIT}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_MAX_RETRIES DEFAULT_MAX_RETRIES}: {@value DEFAULT_MAX_RETRIES}</li>
     *     <li>{@link OnlineIndexBuilderBase#DEFAULT_RECORDS_PER_SECOND DEFAULT_RECORDS_PER_SECOND}: {@value DEFAULT_RECORDS_PER_SECOND}</li>
     * </ul>
     *
     * @param recordStore the record store to use as a prototype
     * @param index the index to build
     */
    public OnlineIndexBuilderBase(@Nonnull FDBRecordStoreBase<M> recordStore, @Nonnull Index index) {
        this(recordStore, index, null);
    }

    // Check pointer equality to make sure other objects really came from given metaData.
    // Also resolve record types to use if not specified.
    private Collection<RecordType> validateIndex(@Nullable Collection<RecordType> recordTypes) {
        if (recordStoreBuilder.getMetaDataProvider() == null) {
            throw new RecordCoreArgumentException("record store builder must include metadata");
        }
        final RecordMetaData metaData = recordStoreBuilder.getMetaDataProvider().getRecordMetaData();
        if (!metaData.hasIndex(index.getName()) || index != metaData.getIndex(index.getName())) {
            throw new MetaDataException("Index " + index.getName() + " not contained within specified metadata");
        }
        if (recordTypes == null) {
            return metaData.recordTypesForIndex(index);
        } else {
            for (RecordType recordType : recordTypes) {
                if (recordType != metaData.getRecordTypes().get(recordType.getName())) {
                    throw new MetaDataException("Record type " + recordType.getName() + " not contained within specified metadata");
                }
            }
            return recordTypes;
        }
    }

    private void validateLimits() {
        checkPositive(maxRetries, "maximum retries");
        checkPositive(limit, "record limit");
        checkPositive(recordsPerSecond, "records per second value");
    }

    private static void checkPositive(int value, String desc) {
        if (value <= 0) {
            throw new RecordCoreException("Non-positive value " + value + " given for " + desc);
        }
    }

    private TupleRange computeRecordsRange() {
        Tuple low = null;
        Tuple high = null;
        for (RecordType recordType : recordTypes) {
            if (!recordType.primaryKeyHasRecordTypePrefix()) {
                // If any of the types to build for does not have a prefix, give up.
                return TupleRange.ALL;
            }
            Tuple prefix = Tuple.from(recordType.getRecordTypeKey());
            if (low == null) {
                low = high = prefix;
            } else {
                if (low.compareTo(prefix) > 0) {
                    low = prefix;
                }
                if (high.compareTo(prefix) < 0) {
                    high = prefix;
                }
            }
        }
        if (low == null) {
            return TupleRange.ALL;
        } else {
            // Both ends inclusive.
            return new TupleRange(low, high, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE);
        }
    }

    // Finds the FDBException that ultimately caused some throwable or
    // null if there is none. This can be then used to determine, for
    // example, the error code associated with this FDBException.
    @Nullable
    private FDBException getFDBException(@Nullable Throwable e) {
        Throwable curr = e;
        while (curr != null) {
            if (curr instanceof FDBException) {
                return (FDBException)curr;
            } else {
                curr = curr.getCause();
            }
        }
        return null;
    }

    // Turn a (possibly null) key into its tuple representation.
    @Nullable
    private Tuple convertOrNull(@Nullable Key.Evaluated key) {
        return (key == null) ? null : key.toTuple();
    }

    // Turn a (possibly null) tuple into a (possibly null) byte array.
    @Nullable
    private byte[] packOrNull(@Nullable Tuple tuple) {
        return (tuple == null) ? null : tuple.pack();
    }

    /**
     * Get the database that contains the record store.
     * @return the database used to build indexes
     */
    @Nonnull
    public FDBDatabase getDatabase() {
        return runner.getDatabase();
    }

    /**
     * Get the timer used in {@link #buildIndex}.
     * @return the timer or <code>null</code> if none is set
     */
    @Nullable
    public FDBStoreTimer getTimer() {
        return runner.getTimer();
    }

    /**
     * Set the timer used in {@link #buildIndex}.
     * @param timer timer to use
     */
    public void setTimer(@Nullable FDBStoreTimer timer) {
        runner.setTimer(timer);
    }

    /**
     * Get the logging context used in {@link #buildIndex}.
     * @return the logging context of <code>null</code> if none is set
     */
    @Nullable
    public Map<String, String> getMdcContext() {
        return runner.getMdcContext();
    }

    /**
     * Set the logging context used in {@link #buildIndex}.
     * @param mdcContext the logging context to set while running
     * @see FDBDatabase#openContext(Map,FDBStoreTimer)
     */
    public void setMdcContext(@Nullable Map<String, String> mdcContext) {
        runner.setMdcContext(mdcContext);
    }

    /**
     * Get the maximum number of transaction retry attempts.
     * @return the maximum number of attempts
     * @see FDBDatabaseRunner#getMaxAttempts
     */
    public int getMaxAttempts() {
        return runner.getMaxAttempts();
    }

    /**
     * Set the maximum number of transaction retry attempts.
     * @param maxAttempts the maximum number of attempts
     * @see FDBDatabaseRunner#setMaxAttempts
     */
    public void setMaxAttempts(int maxAttempts) {
        runner.setMaxAttempts(maxAttempts);
    }

    /**
     * Get the maximum delay between transaction retry attempts.
     * @return the maximum delay
     * @see FDBDatabaseRunner#getMaxDelayMillis
     */
    public long getMaxDelayMillis() {
        return runner.getMaxDelayMillis();
    }

    /**
     * Set the maximum delay between transaction retry attempts.
     * @param maxDelayMillis the maximum delay
     * @see FDBDatabaseRunner#setMaxDelayMillis
     */
    public void setMaxDelayMillis(long maxDelayMillis) {
        runner.setMaxDelayMillis(maxDelayMillis);
    }

    /**
     * Get the initial delay between transaction retry attempts.
     * @return the initial delay
     * @see FDBDatabaseRunner#getInitialDelayMillis
     */
    public long getInitialDelayMillis() {
        return runner.getInitialDelayMillis();
    }

    /**
     * Set the initial delay between transaction retry attempts.
     * @param initialDelayMillis the initial delay
     * @see FDBDatabaseRunner#setInitialDelayMillis
     */
    public void setInitialDelayMillis(long initialDelayMillis) {
        runner.setInitialDelayMillis(initialDelayMillis);
    }

    public void setIndexMaintenanceFilter(@Nonnull IndexMaintenanceFilter indexMaintenanceFilter) {
        recordStoreBuilder.setIndexMaintenanceFilter(indexMaintenanceFilter);
    }

    public void setSerializer(@Nonnull RecordSerializer<M> serializer) {
        recordStoreBuilder.setSerializer(serializer);
    }

    public void setFormatVersion(int formatVersion) {
        recordStoreBuilder.setFormatVersion(formatVersion);
    }

    private CompletableFuture<? extends FDBRecordStoreBase<M>> openRecordStore(@Nonnull FDBRecordContext context) {
        return recordStoreBuilder.copyBuilder().setContext(context).openAsync();
    }

    @Override
    public void close() {
        runner.close();
    }

    // This retry loop runs an operation on a record store. The reason that this retry loop exists
    // (despite the fact that FDBDatabase.runAsync exists and is (in fact) used by the logic here)
    // is that this will adjust the value of limit in the case that we encounter errors like transaction_too_large
    // that are not retriable but can be addressed by decreasing the amount of work that has to
    // be done in a single transaction. This allows the OnlineIndexBuilder to respond to being given
    // a bad value for limit.
    @Nonnull
    @VisibleForTesting
    <R> CompletableFuture<R> runAsync(@Nonnull Function<? super FDBRecordStoreBase<M>, CompletableFuture<R>> function) {
        AtomicInteger tries = new AtomicInteger(0);
        CompletableFuture<R> ret = new CompletableFuture<>();
        AtomicLong toWait = new AtomicLong(FDBDatabaseFactory.instance().getInitialDelayMillis());

        AsyncUtil.whileTrue(() ->
                runner.runAsync(context -> {
                    // One difference here from your standard retry loop is that within this method, we set the
                    // priority to "batch" on all transactions in order to avoid other stepping on the toes of other work.
                    context.ensureActive().options().setPriorityBatch();
                    return openRecordStore(context).thenCompose(store -> {
                        if (!store.isIndexWriteOnly(index)) {
                            throw new RecordCoreStorageException("Attempted to build readable index",
                                    LogMessageKeys.INDEX_NAME, index.getName(),
                                    recordStoreBuilder.subspaceProvider.logKey(), recordStoreBuilder.subspaceProvider);
                        }
                        return function.apply(store);
                    });
                }).handle((value, e) -> {
                    if (e == null) {
                        ret.complete(value);
                        return AsyncUtil.READY_FALSE;
                    } else {
                        int currTries = tries.getAndIncrement();
                        if (currTries >= maxRetries) {
                            ret.completeExceptionally(runner.getDatabase().mapAsyncToSyncException(e));
                            return AsyncUtil.READY_FALSE;
                        }

                        FDBException fdbE = getFDBException(e);
                        if (fdbE == null) {
                            ret.completeExceptionally(runner.getDatabase().mapAsyncToSyncException(e));
                            return AsyncUtil.READY_FALSE;
                        } else {
                            if (lessenWorkCodes.contains(fdbE.getCode())) {
                                limit = Math.max(1, (3 * limit) / 4);
                                if (LOGGER.isInfoEnabled()) {
                                    LOGGER.info(KeyValueLogMessage.of("Lessening limit of online index build",
                                                    "indexName", index.getName(),
                                                    "indexVersion", index.getVersion(),
                                                    "error", fdbE.getMessage(),
                                                    "errorCode", fdbE.getCode(),
                                                    "limit", limit),
                                            fdbE);
                                }
                                long delay = (long)(Math.random() * toWait.get());
                                toWait.set(Math.min(delay * 2, FDBDatabaseFactory.instance().getMaxDelayMillis()));
                                return MoreAsyncUtil.delayedFuture(delay, TimeUnit.MILLISECONDS).thenApply(vignore3 -> true);
                            } else {
                                ret.completeExceptionally(runner.getDatabase().mapAsyncToSyncException(e));
                                return AsyncUtil.READY_FALSE;
                            }
                        }
                    }
                }).thenCompose(Function.identity()), runner.getExecutor());

        return ret;
    }

    // Builds the index for all of the keys within a given range. This does not update the range set
    // associated with this index, so it is really designed to be a helper for other methods.
    @Nonnull
    private CompletableFuture<Tuple> buildRangeOnly(@Nonnull FDBRecordStoreBase<M> store,
                                                    @Nullable Tuple start, @Nullable Tuple end) {
        return buildRangeOnly(store, TupleRange.between(start, end)).thenApply(realEnd -> realEnd == null ? end : realEnd);
    }

    // TupleRange version of above.
    @Nonnull
    private CompletableFuture<Tuple> buildRangeOnly(@Nonnull FDBRecordStoreBase<M> store, @Nonnull TupleRange range) {
        // Test whether the same up to store state by checking something that is pointer-copied but not normally
        // otherwise shared.
        if (store.getRecordMetaData().getRecordTypes() != recordStoreBuilder.getMetaDataProvider().getRecordMetaData().getRecordTypes()) {
            throw new MetaDataException("Store does not have the same metadata");
        }
        final IndexMaintainer<M> maintainer = store.getIndexMaintainer(index);
        final ExecuteProperties executeProperties = ExecuteProperties.newBuilder()
                .setReturnedRowLimit(limit)
                .setIsolationLevel(IsolationLevel.SERIALIZABLE)
                .build();
        final ScanProperties scanProperties = new ScanProperties(executeProperties);
        final RecordCursor<FDBStoredRecord<M>> cursor = store.scanRecords(range, null, scanProperties);
        final AtomicBoolean empty = new AtomicBoolean(true);
        final FDBStoreTimer timer = getTimer();

        // Note: This runs all of the updates in serial in order to not invoke a race condition
        // in the rank code that was causing incorrect results. If everything were thread safe,
        // a larger pipeline size would be possible.
        return cursor.forEachAsync(rec -> {
            empty.set(false);
            if (timer != null) {
                timer.increment(FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RECORDS_SCANNED);
            }
            if (recordTypes.contains(rec.getRecordType())) {
                if (timer != null) {
                    timer.increment(FDBStoreTimer.Counts.ONLINE_INDEX_BUILDER_RECORDS_INDEXED);
                }
                return maintainer.update(null, rec);
            } else {
                return AsyncUtil.DONE;
            }
        }, 1).thenCompose(vignore -> {
            byte[] nextCont = empty.get() ? null : cursor.getContinuation();
            if (nextCont == null) {
                return CompletableFuture.completedFuture(null);
            } else {
                // Get the next record and return its primary key.
                final ExecuteProperties executeProperties2 = ExecuteProperties.newBuilder()
                        .setReturnedRowLimit(1)
                        .setIsolationLevel(IsolationLevel.SERIALIZABLE)
                        .build();
                final ScanProperties scanProperties2 = new ScanProperties(executeProperties2);
                RecordCursor<FDBStoredRecord<M>> nextCursor = store.scanRecords(range, nextCont, scanProperties2);
                return nextCursor.onHasNext().thenApply(hasNext -> {
                    if (hasNext) {
                        FDBStoredRecord<M> rec = nextCursor.next();
                        return rec.getPrimaryKey();
                    } else {
                        return null;
                    }
                });
            }
        });
    }

    // Builds a range within a single transaction. It will look for the missing ranges within the given range and build those while
    // updating the range set.
    @Nonnull
    private CompletableFuture<Void> buildRange(@Nonnull FDBRecordStoreBase<M> store, @Nullable Tuple start, @Nullable Tuple end) {
        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        AsyncIterator<Range> ranges = rangeSet.missingRanges(store.ensureContextActive(), packOrNull(start), packOrNull(end)).iterator();
        return ranges.onHasNext().thenCompose(hasAny -> {
            if (hasAny) {
                return AsyncUtil.whileTrue(() -> {
                    Range range = ranges.next();
                    Tuple rangeStart = Arrays.equals(range.begin, START_BYTES) ? null : Tuple.fromBytes(range.begin);
                    Tuple rangeEnd = Arrays.equals(range.end, END_BYTES) ? null : Tuple.fromBytes(range.end);
                    return CompletableFuture.allOf(
                            // TODO: This isn't generally correct, since buildRangeOnly might hit its limit first.
                            // Should insertRange only up to the returned endpoint (or rangeEnd), like buildUnbuiltRange.
                            // This method works because it is only called for the endpoint ranges, which are empty and
                            // one long, respectively.
                            buildRangeOnly(store, rangeStart, rangeEnd),
                            rangeSet.insertRange(store.ensureContextActive(), range, true)
                    ).thenCompose(vignore -> ranges.onHasNext());
                }, store.getExecutor());
            } else {
                return AsyncUtil.DONE;
            }
        });
    }

    /**
     * Builds (transactionally) the index by adding records with primary keys within the given range.
     * This will look for gaps of keys within the given range that haven't yet been rebuilt and then
     * rebuild only those ranges. As a result, if this method is called twice, the first time, it will
     * build whatever needs to be built, and then the second time, it will notice that there are no ranges
     * that need to be built, so it will do nothing. In this way, it is idempotent and thus safe to
     * use in retry loops.
     *
     * This method will fail if there is too much work to be done in a single transaction. If one wants
     * to handle building a range that does not fit in a single transaction, one should use the
     * {@link OnlineIndexBuilderBase#buildRange(Key.Evaluated, Key.Evaluated) buildRange()}
     * function that takes an {@link FDBDatabase} as its first parameter.
     *
     * @param store the record store in which to rebuild the range
     * @param start the (inclusive) beginning primary key of the range to build (or <code>null</code> to go to the end)
     * @param end the (exclusive) end primary key of the range to build (or <code>null</code> to go to the end)
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildRange(@Nonnull FDBRecordStoreBase<M> store, @Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        byte[] startBytes = packOrNull(convertOrNull(start));
        byte[] endBytes = packOrNull(convertOrNull(end));
        AsyncIterator<Range> ranges = rangeSet.missingRanges(store.ensureContextActive(), startBytes, endBytes).iterator();
        return ranges.onHasNext().thenCompose(hasNext -> {
            if (hasNext) {
                return AsyncUtil.whileTrue(() -> {
                    Range toBuild = ranges.next();
                    Tuple startTuple = Tuple.fromBytes(toBuild.begin);
                    Tuple endTuple = Arrays.equals(toBuild.end, END_BYTES) ? null : Tuple.fromBytes(toBuild.end);
                    AtomicReference<Tuple> currStart = new AtomicReference<>(startTuple);
                    return AsyncUtil.whileTrue(() ->
                        // Bold claim: this will never cause a RecordBuiltRangeException because of transactions.
                        buildUnbuiltRange(store, currStart.get(), endTuple).thenApply(realEnd -> {
                            if (realEnd != null && !realEnd.equals(endTuple)) {
                                currStart.set(realEnd);
                                return true;
                            } else {
                                return false;
                            }
                        }), store.getExecutor()).thenCompose(vignore -> ranges.onHasNext());
                }, store.getExecutor());
            } else {
                return AsyncUtil.DONE;
            }
        });
    }

    /**
     * Builds (with a retry loop) the index by adding records with primary keys within the given range.
     * This will look for gaps of keys within the given range that haven't yet been rebuilt and then rebuild
     * only those ranges. It will also limit each transaction to the number of records specified by the
     * <code>limit</code> parameter of this class's constructor. In the case that that limit is too high (i.e.,
     * it can't make any progress or errors out on a non-retriable error like <code>transaction_too_large</code>,
     * this method will actually decrease the limit so that less work is attempted each transaction. It will
     * also rate limit itself as to not make too many requests per second.
     *
     * @param start the (inclusive) beginning primary key of the range to build (or <code>null</code> to go from the beginning)
     * @param end the (exclusive) end primary key of the range to build (or <code>null</code> to go to the end)
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildRange(@Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        return recordStoreBuilder.subspaceProvider.getSubspaceAsync().thenCompose(subspace -> buildRange(subspace, start, end));
    }

    @Nonnull
    private CompletableFuture<Void> buildRange(@Nonnull Subspace subspace, @Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        RangeSet rangeSet = new RangeSet(subspace.subspace(Tuple.from(FDBRecordStoreBase.INDEX_RANGE_SPACE_KEY, index.getSubspaceKey())));
        byte[] startBytes = packOrNull(convertOrNull(start));
        byte[] endBytes = packOrNull(convertOrNull(end));
        Queue<Range> rangeDeque = new ArrayDeque<>();
        return rangeSet.missingRanges(runner.getDatabase().database(), startBytes, endBytes)
                .thenAccept(rangeDeque::addAll)
                .thenCompose(vignore -> buildRanges(subspace, rangeSet, rangeDeque));
    }

    @Nonnull
    private CompletableFuture<Void> buildRanges(@Nonnull Subspace subspace, RangeSet rangeSet, Queue<Range> rangeDeque) {
        return AsyncUtil.whileTrue(() -> {
            if (rangeDeque.isEmpty()) {
                return CompletableFuture.completedFuture(false); // We're done.
            }
            Range toBuild = rangeDeque.remove();

            // This only works if the things included within the rangeSet are serialized Tuples.
            Tuple startTuple = Tuple.fromBytes(toBuild.begin);
            Tuple endTuple = Arrays.equals(toBuild.end, END_BYTES) ? null : Tuple.fromBytes(toBuild.end);
            return buildUnbuiltRange(startTuple, endTuple)
                    .handle((realEnd, ex) -> handleBuiltRange(subspace, rangeSet, rangeDeque, startTuple, endTuple, realEnd, ex))
                    .thenCompose(Function.identity());
        }, runner.getExecutor());
    }

    @Nonnull
    private CompletableFuture<Boolean> handleBuiltRange(@Nonnull Subspace subspace, RangeSet rangeSet, Queue<Range> rangeDeque, Tuple startTuple, Tuple endTuple, Tuple realEnd, Throwable ex) {
        final RuntimeException unwrappedEx = ex == null ? null : runner.getDatabase().mapAsyncToSyncException(ex);
        long toWait = (recordsPerSecond == UNLIMITED) ? 0 : 1000 * limit / recordsPerSecond;
        if (unwrappedEx == null) {
            if (realEnd != null && !realEnd.equals(endTuple)) {
                // We didn't make it to the end. Continue on to the next item.
                if (endTuple != null) {
                    rangeDeque.add(new Range(realEnd.pack(), endTuple.pack()));
                } else {
                    rangeDeque.add(new Range(realEnd.pack(), END_BYTES));
                }
            }
            return MoreAsyncUtil.delayedFuture(toWait, TimeUnit.MILLISECONDS).thenApply(vignore3 -> true);
        } else {
            Throwable cause = unwrappedEx;
            while (cause != null) {
                if (cause instanceof OnlineIndexBuilderBase.RecordBuiltRangeException) {
                    return rangeSet.missingRanges(runner.getDatabase().database(), startTuple.pack(), endTuple.pack())
                            .thenCompose(list -> {
                                rangeDeque.addAll(list);
                                return MoreAsyncUtil.delayedFuture(toWait, TimeUnit.MILLISECONDS);
                            }).thenApply(vignore3 -> true);
                } else {
                    cause = cause.getCause();
                }
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(KeyValueLogMessage.of("possibly non-fatal error encountered building range",
                        LogMessageKeys.RANGE_START, startTuple,
                        LogMessageKeys.RANGE_END, endTuple,
                        LogMessageKeys.SUBSPACE, ByteArrayUtil2.loggable(subspace.pack())), ex);
            }
            throw unwrappedEx; // made it to the bottom, throw original exception
        }
    }

    // Helper function that works on Tuples instead of keys.
    @Nonnull
    private CompletableFuture<Tuple> buildUnbuiltRange(@Nonnull FDBRecordStoreBase<M> store, @Nullable Tuple start, @Nullable Tuple end) {
        CompletableFuture<Tuple> buildFuture = buildRangeOnly(store, start, end);

        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        byte[] startBytes = packOrNull(start);

        AtomicReference<Tuple> toReturn = new AtomicReference<>();
        return buildFuture.thenCompose(realEnd -> {
            toReturn.set(realEnd);
            return rangeSet.insertRange(store.ensureContextActive(), startBytes, packOrNull(realEnd), true);
        }).thenApply(changed -> {
            if (changed) {
                return toReturn.get();
            } else {
                throw new RecordBuiltRangeException(start, end);
            }
        });
    }

    /**
     * Builds (transactionally) the index by adding records with primary keys within the given range.
     * This requires that the range is initially "unbuilt", i.e., no records within the given
     * range have yet been processed by the index build job. It is acceptable if there
     * are records within that range that have already been added to the index because they were
     * added to the store after the index was added in write-only mode but have not yet been
     * processed by the index build job.
     *
     * Note that this function is not idempotent in that if the first time this function runs, if it
     * fails with <code>commit_unknown_result</code> but the transaction actually succeeds, running this
     * function again will result in a {@link RecordBuiltRangeException} being thrown the second
     * time. Retry loops used by the <code>OnlineIndexBuilder</code> class that call this method
     * handle this contingency. For the most part, this method should only be used by those who know
     * what they are doing. It is included because it is less expensive to make this call if one
     * already knows that the range will be unbuilt, but the caller must be ready to handle the
     * circumstance that the range might be built the second time.
     *
     * Most users should use the
     * {@link OnlineIndexBuilderBase#buildRange(FDBRecordStoreBase, Key.Evaluated, Key.Evaluated) buildRange()}
     * method with the same parameters in the case that they want to build a range of keys into the index. That
     * method <i>is</i> idempotent, but it is slightly more costly as it firsts determines what ranges are
     * have not yet been built before building them.
     *
     * @param store the record store in which to rebuild the range
     * @param start the (inclusive) beginning primary key of the range to build (or <code>null</code> to start from the beginning)
     * @param end the (exclusive) end primary key of the range to build (or <code>null</code> to go to the end)
     * @return a future with the key of the first record not processed by this range rebuild
     * @throws RecordBuiltRangeException if the given range contains keys already processed by the index build
     */
    @Nonnull
    public CompletableFuture<Key.Evaluated> buildUnbuiltRange(@Nonnull FDBRecordStoreBase<M> store, @Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        return buildUnbuiltRange(store, convertOrNull(start), convertOrNull(end))
                .thenApply(tuple -> (tuple == null) ? null : Key.Evaluated.fromTuple(tuple));
    }

    // Helper function with the same behavior as buildUnbuiltRange, but it works on tuples instead of primary keys.
    @Nonnull
    private CompletableFuture<Tuple> buildUnbuiltRange(@Nullable Tuple start, @Nullable Tuple end) {
        return runAsync(store -> buildUnbuiltRange(store, start, end));
    }

    @VisibleForTesting
    @Nonnull
    CompletableFuture<Key.Evaluated> buildUnbuiltRange(@Nullable Key.Evaluated start, @Nullable Key.Evaluated end) {
        return runAsync(store -> buildUnbuiltRange(store, start, end));
    }

    /**
     * Transactionally rebuild an entire index. This will (1) delete any data in the index that is
     * already there and (2) rebuild the entire key range for the given index. It will attempt to
     * do this within a single transaction, and it may fail if there are too many records, so this
     * is only safe to do for small record stores.
     *
     * Many large use-cases should use the {@link #buildIndexAsync} method along with temporarily
     * changing an index to write-only mode while the index is being rebuilt.
     *
     * @param store the record store in which to rebuild the index
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> rebuildIndexAsync(@Nonnull FDBRecordStoreBase<M> store) {
        Transaction tr = store.ensureContextActive();
        store.clearIndexData(index);

        // Clear the associated range set and make it instead equal to
        // the complete range. This isn't super necessary, but it is done
        // to avoid (1) concurrent OnlineIndexBuilders doing more work and
        // (2) to allow for write-only indexes to continue to do the right thing.
        RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        CompletableFuture<Boolean> rangeFuture = rangeSet.clear(tr)
                .thenCompose(vignore -> rangeSet.insertRange(tr, null, null));

        // Rebuild the index by going through all of the records in a transaction.
        AtomicReference<TupleRange> rangeToGo = new AtomicReference<>(recordsRange);
        CompletableFuture<Void> buildFuture = AsyncUtil.whileTrue(() ->
                buildRangeOnly(store, rangeToGo.get()).thenApply(nextStart -> {
                    if (nextStart == null) {
                        return false;
                    } else {
                        rangeToGo.set(new TupleRange(nextStart, rangeToGo.get().getHigh(), EndpointType.RANGE_INCLUSIVE, rangeToGo.get().getHighEndpoint()));
                        return true;
                    }
                }), store.getExecutor());

        return CompletableFuture.allOf(rangeFuture, buildFuture);
    }

    /**
     * Transactionally rebuild an entire index.
     * Synchronous version of {@link #rebuildIndexAsync}
     *
     * @param store the record store in which to rebuild the index
     * @see #buildIndex
     */
    public void rebuildIndex(@Nonnull FDBRecordStoreBase<M> store) {
        asyncToSync(rebuildIndexAsync(store));
    }

    /**
     * Builds (transactionally) the endpoints of an index. What this means is that builds everything from the beginning of
     * the key space to the first record and everything from the last record to the end of the key space.
     * There won't be any records within these ranges (except for the last record of the record store), but
     * it does mean that any records in the future that get added to these ranges will correctly update
     * the index. This means, e.g., that if the workload primarily adds records to the record store
     * after the current last record (because perhaps the primary key is based off of an atomic counter
     * or the current time), running this method will be highly contentious, but once it completes,
     * the rest of the index build should happen without any more conflicts.
     *
     * This will return a (possibly null) {@link TupleRange} that contains the primary keys of the
     * first and last records within the record store. This can then be used to either build the
     * range right away or to then divy-up the remaining ranges between multiple agents working
     * in parallel if one desires.
     *
     * @param store the record store in which to rebuild the index
     * @return a future that will contain the range of records in the interior of the record store
     */
    @Nonnull
    public CompletableFuture<TupleRange> buildEndpoints(@Nonnull FDBRecordStoreBase<M> store) {
        final RangeSet rangeSet = new RangeSet(store.indexRangeSubspace(index));
        if (TupleRange.ALL.equals(recordsRange)) {
            return buildEndpoints(store, rangeSet);
        }
        // If records do not occupy whole range, first mark outside as built.
        final Range asRange = recordsRange.toRange();
        return CompletableFuture.allOf(
                rangeSet.insertRange(store.ensureContextActive(), null, asRange.begin),
                rangeSet.insertRange(store.ensureContextActive(), asRange.end, null))
                .thenCompose(vignore -> buildEndpoints(store, rangeSet));
    }

    @Nonnull
    private CompletableFuture<TupleRange> buildEndpoints(@Nonnull FDBRecordStoreBase<M> store, @Nonnull RangeSet rangeSet) {
        final ExecuteProperties limit1 = ExecuteProperties.newBuilder()
                .setReturnedRowLimit(1)
                .setIsolationLevel(IsolationLevel.SERIALIZABLE)
                .build();
        final ScanProperties forward = new ScanProperties(limit1);
        RecordCursor<FDBStoredRecord<M>> beginCursor = store.scanRecords(recordsRange, null, forward);
        CompletableFuture<Tuple> begin = beginCursor.onHasNext().thenCompose(present -> {
            if (present) {
                Tuple firstTuple = beginCursor.next().getPrimaryKey();
                return buildRange(store, null, firstTuple).thenApply(vignore -> firstTuple);
            } else {
                // Empty range -- add the whole thing.
                return rangeSet.insertRange(store.ensureContextActive(), null, null).thenApply(bignore -> null);
            }
        });

        final ScanProperties backward = new ScanProperties(limit1, true);
        RecordCursor<FDBStoredRecord<M>> endCursor = store.scanRecords(recordsRange, null, backward);
        CompletableFuture<Tuple> end = endCursor.onHasNext().thenCompose(present -> {
            if (present) {
                Tuple lastTuple = endCursor.next().getPrimaryKey();
                return buildRange(store, lastTuple, null).thenApply(vignore -> lastTuple);
            } else {
                // As the range is empty, the whole range needs to be added, but that is accomplished
                // by the above future, so this has nothing to do.
                return CompletableFuture.completedFuture(null);
            }
        });

        return begin.thenCombine(end, (firstTuple, lastTuple) -> {
            if (firstTuple == null || firstTuple.equals(lastTuple)) {
                return null;
            } else {
                return new TupleRange(firstTuple, lastTuple, EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_EXCLUSIVE);
            }
        });
    }

    /**
     * Builds (with a retry loop) the endpoints of an index. See the
     * {@link OnlineIndexBuilderBase#buildEndpoints(FDBRecordStoreBase) buildEndpoints()} method that takes
     * an {@link FDBRecordStoreBase} as its parameter for more details. This will retry on that function
     * until it gets a non-exceptional result and return the results back.
     *
     * @return a future that will contain the range of records in the interior of the record store
     */
    @Nonnull
    public CompletableFuture<TupleRange> buildEndpoints() {
        return runAsync(this::buildEndpoints);
    }

    /**
     * Builds an index across multiple transactions. This will honor the rate-limiting
     * parameters set in the constructor of this class. It will also retry
     * any retriable errors that it encounters while it runs the build. At the
     * end, it will mark the index readable in the store if specified.
     *
     * @param markReadable whether to mark the index as readable after building the index
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildIndexAsync(boolean markReadable) {
        CompletableFuture<Void> buildFuture = buildEndpoints().thenCompose(tupleRange -> {
            if (tupleRange != null) {
                return buildRange(Key.Evaluated.fromTuple(tupleRange.getLow()), Key.Evaluated.fromTuple(tupleRange.getHigh()));
            } else {
                return CompletableFuture.completedFuture(null);
            }
        });

        if (markReadable) {
            return buildFuture.thenCompose(vignore ->
                runner.runAsync(context ->
                        openRecordStore(context)
                                .thenCompose(store -> store.markIndexReadable(index))
                                .thenApply(ignore -> null))
            );
        } else {
            return buildFuture;
        }
    }

    /**
     * Builds an index across multiple transactions. This will honor the rate-limiting
     * parameters set in the constructor of this class. It will also retry
     * any retriable errors that it encounters while it runs the build.
     *
     * @return a future that will be ready when the build has completed
     */
    @Nonnull
    public CompletableFuture<Void> buildIndexAsync() {
        return buildIndexAsync(true);
    }

    /**
     * Builds an index across multiple transactions.
     * Synchronous version of {@link #buildIndexAsync}.
     * @param markReadable whether to mark the index as readable after building the index
     */
    @Nonnull
    public void buildIndex(boolean markReadable) {
        asyncToSync(buildIndexAsync(markReadable));
    }

    /**
     * Builds an index across multiple transactions.
     * Synchronous version of {@link #buildIndexAsync}.
     */
    @Nonnull
    public void buildIndex() {
        asyncToSync(buildIndexAsync());
    }

    /**
     * Wait for asynchronous index build to complete.
     * @param buildIndexFuture the result of {@link #buildIndexAsync}
     */
    public void asyncToSync(@Nonnull CompletableFuture<Void> buildIndexFuture) {
        runner.asyncToSync(FDBStoreTimer.Waits.WAIT_ONLINE_BUILD_INDEX, buildIndexFuture);
    }
}
