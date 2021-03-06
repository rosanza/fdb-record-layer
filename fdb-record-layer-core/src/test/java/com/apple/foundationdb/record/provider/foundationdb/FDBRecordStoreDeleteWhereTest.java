/*
 * FDBRecordStoreDeleteWhereTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2020 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataBuilder;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TestRecordsWithHeaderProto;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.IndexTypes;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.query.expressions.Query;
import com.apple.foundationdb.record.query.expressions.QueryComponent;
import com.apple.test.Tags;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.apple.foundationdb.record.metadata.Key.Expressions.concat;
import static com.apple.foundationdb.record.metadata.Key.Expressions.concatenateFields;
import static com.apple.foundationdb.record.metadata.Key.Expressions.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests related to {@link FDBRecordStore#deleteRecordsWhere(QueryComponent)} and its variants.
 */
@Tag(Tags.RequiresFDB)
public class FDBRecordStoreDeleteWhereTest extends FDBRecordStoreTestBase {

    @Test
    public void testDeleteWhereCountIndex() throws Exception {
        testDeleteWhere(true);
    }

    @Test
    public void testDeleteWhere() throws Exception {
        testDeleteWhere(false);
    }

    private void testDeleteWhere(boolean useCountIndex) throws Exception {
        try (FDBRecordContext context = openContext()) {
            openRecordWithHeaderPrimaryKey(context, useCountIndex);

            saveHeaderRecord(1, "a", 0, "lynx");
            saveHeaderRecord(1, "b", 1, "bobcat");
            saveHeaderRecord(1, "c", 2, "panther");

            saveHeaderRecord(2, "a", 3, "jaguar");
            saveHeaderRecord(2, "b", 4, "leopard");
            saveHeaderRecord(2, "c", 5, "lion");
            saveHeaderRecord(2, "d", 6, "tiger");
            context.commit();
        }

        try (FDBRecordContext context = openContext()) {
            final KeyExpression groupExpr = openRecordWithHeaderPrimaryKey(context, useCountIndex);

            assertEquals(3, recordStore.getSnapshotRecordCount(groupExpr, Key.Evaluated.scalar(1)).join().longValue());

            recordStore.deleteRecordsWhere(Query.field("header").matches(Query.field("rec_no").equalsValue(1)));
            context.commit();
        }

        try (FDBRecordContext context = openContext()) {
            final KeyExpression groupExpr = openRecordWithHeaderPrimaryKey(context, useCountIndex);

            assertEquals(0, recordStore.getSnapshotRecordCount(groupExpr, Key.Evaluated.scalar(1)).join().longValue());

            int expectedNum = 3;
            for (FDBStoredRecord<Message> storedRecord : recordStore.scanRecords(null, ScanProperties.FORWARD_SCAN).asList().join()) {
                TestRecordsWithHeaderProto.MyRecord record = parseMyRecord(storedRecord.getRecord());
                assertEquals(2, record.getHeader().getRecNo());
                assertEquals(expectedNum++, record.getHeader().getNum());
            }
            assertEquals(7, expectedNum);
            expectedNum = 3;
            for (FDBIndexedRecord<Message> indexedRecord : recordStore.scanIndexRecords("MyRecord$str_value").asList().join()) {
                TestRecordsWithHeaderProto.MyRecord record = parseMyRecord(indexedRecord.getRecord());
                assertEquals(2, record.getHeader().getRecNo());
                assertEquals(expectedNum++, record.getHeader().getNum());
            }
            assertEquals(7, expectedNum);
            context.commit();
        }
    }

    @Test
    public void testDeleteWhereGroupedCount() throws Exception {
        KeyExpression groupExpr = concat(
                field("header").nest(field("rec_no")),
                field("header").nest(field("path")));
        RecordMetaDataHook hook = metaData -> {
            metaData.getRecordType("MyRecord")
                    .setPrimaryKey(concat(
                            field("header").nest(field("rec_no")),
                            field("header").nest(field("path")),
                            field("header").nest(field("num"))));

            metaData.addUniversalIndex(new Index("MyRecord$groupedCount", new GroupingKeyExpression(groupExpr, 0), IndexTypes.COUNT));
            metaData.addUniversalIndex(new Index("MyRecord$groupedUpdateCount", new GroupingKeyExpression(groupExpr, 0), IndexTypes.COUNT_UPDATES));
        };
        try (FDBRecordContext context = openContext()) {
            openRecordWithHeader(context, hook);

            saveHeaderRecord(1, "a", 0, "lynx");
            saveHeaderRecord(1, "a", 1, "bobcat");
            saveHeaderRecord(1, "b", 2, "panther");

            saveHeaderRecord(2, "a", 3, "jaguar");
            saveHeaderRecord(2, "b", 4, "leopard");
            saveHeaderRecord(2, "c", 5, "lion");
            saveHeaderRecord(2, "d", 6, "tiger");
            context.commit();
        }
        try (FDBRecordContext context = openContext()) {
            openRecordWithHeader(context, hook);

            // Number of records where first component of primary key is 1
            assertEquals(3, recordStore.getSnapshotRecordCount(groupExpr, Key.Evaluated.scalar(1)).join().longValue());

            // Number of updates to such records
            assertEquals(3, recordStore.getSnapshotRecordUpdateCount(groupExpr, Key.Evaluated.scalar(1)).join().longValue());

            recordStore.deleteRecordsWhere(Query.and(
                    Query.field("header").matches(Query.field("rec_no").equalsValue(1)),
                    Query.field("header").matches(Query.field("path").equalsValue("a"))));

            assertEquals(1, recordStore.getSnapshotRecordCount(groupExpr, Key.Evaluated.scalar(1)).join().longValue());

            // Deleting by group prefix resets the update counter for the group(s)
            assertEquals(1, recordStore.getSnapshotRecordUpdateCount(groupExpr, Key.Evaluated.scalar(1)).join().longValue());

            context.commit();
        }
    }

    @Test
    public void testDeleteWhereMissingPrimaryKey() throws Exception {
        try (FDBRecordContext context = openContext()) {
            openRecordWithHeaderPrimaryKey(context, false);
            assertThrows(Query.InvalidExpressionException.class, () ->
                    recordStore.deleteRecordsWhere(Query.field("header").matches(Query.field("path").equalsValue(1))));
        }
    }

    @Test
    public void testDeleteWhereMissingIndex() {
        try (FDBRecordContext context = openContext()) {
            RecordMetaDataBuilder builder = RecordMetaData.newBuilder().setRecords(TestRecordsWithHeaderProto.getDescriptor());
            builder.getRecordType("MyRecord")
                    .setPrimaryKey(field("header").nest(concatenateFields("rec_no", "path")));
            builder.addIndex("MyRecord", "MyRecord$str_value", concat(field("header").nest("path"),
                    field("str_value")));
            RecordMetaData metaData = builder.getRecordMetaData();
            createOrOpenRecordStore(context, metaData);
            assertThrows(Query.InvalidExpressionException.class, () ->
                    recordStore.deleteRecordsWhere(Query.field("header").matches(Query.field("rec_no").equalsValue(1))));
        }
    }

    @SuppressWarnings("deprecation")
    private KeyExpression openRecordWithHeaderPrimaryKey(FDBRecordContext context, boolean useCountIndex) throws Exception {
        final KeyExpression groupExpr = field("header").nest("rec_no");
        openRecordWithHeader(context, metaData -> {
            metaData.getRecordType("MyRecord")
                    .setPrimaryKey(field("header").nest(concatenateFields("rec_no", "path")));
            metaData.addIndex("MyRecord", "MyRecord$str_value", concat(groupExpr, field("str_value")));
            if (useCountIndex) {
                metaData.addUniversalIndex(new Index("MyRecord$count", new GroupingKeyExpression(groupExpr, 0), IndexTypes.COUNT));
            } else {
                metaData.setRecordCountKey(groupExpr);
            }
        });
        return groupExpr;
    }

}
