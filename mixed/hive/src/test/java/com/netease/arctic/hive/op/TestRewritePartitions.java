/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.hive.op;

import static com.netease.arctic.hive.op.UpdateHiveFiles.DELETE_UNTRACKED_HIVE_FILE;

import com.netease.arctic.TableTestHelper;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.catalog.CatalogTestHelper;
import com.netease.arctic.hive.HiveTableProperties;
import com.netease.arctic.hive.MixedHiveTableTestBase;
import com.netease.arctic.hive.TestHMS;
import com.netease.arctic.hive.catalog.HiveCatalogTestHelper;
import com.netease.arctic.hive.catalog.HiveTableTestHelper;
import com.netease.arctic.hive.io.HiveDataTestHelpers;
import com.netease.arctic.table.UnkeyedTable;
import com.netease.arctic.utils.ArcticTableUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.thrift.TException;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.List;

@RunWith(Parameterized.class)
public class TestRewritePartitions extends MixedHiveTableTestBase {

  @ClassRule public static TestHMS TEST_HMS = new TestHMS();

  private List<DataFile> initDataFiles;

  public TestRewritePartitions(
      CatalogTestHelper catalogTestHelper, TableTestHelper tableTestHelper) {
    super(catalogTestHelper, tableTestHelper);
  }

  @Parameterized.Parameters(name = "{0}, {1}")
  public static Object[] parameters() {
    return new Object[][] {
      {
        new HiveCatalogTestHelper(TableFormat.MIXED_HIVE, TEST_HMS.getHiveConf()),
        new HiveTableTestHelper(true, true)
      },
      {
        new HiveCatalogTestHelper(TableFormat.MIXED_HIVE, TEST_HMS.getHiveConf()),
        new HiveTableTestHelper(
            false,
            true,
            ImmutableMap.of(HiveTableProperties.HIVE_CONSISTENT_WRITE_ENABLED, "false"))
      }
    };
  }

  private void initDataFiles() {
    List<Record> insertRecords = Lists.newArrayList();
    insertRecords.add(tableTestHelper().generateTestRecord(1, "john", 0, "2022-01-01T12:00:00"));
    insertRecords.add(tableTestHelper().generateTestRecord(2, "lily", 0, "2022-01-02T12:00:00"));
    initDataFiles =
        HiveDataTestHelpers.writerOf(getArcticTable()).transactionId(1L).writeHive(insertRecords);
    UnkeyedTable baseStore = ArcticTableUtil.baseStore(getArcticTable());
    OverwriteFiles overwriteFiles = baseStore.newOverwrite();
    initDataFiles.forEach(overwriteFiles::addFile);
    overwriteFiles.commit();

    initDataFiles = HiveDataTestHelpers.lastedAddedFiles(baseStore);
  }

  @Test
  public void testRewriteAllPartitions() throws TException {
    initDataFiles();
    List<Record> insertRecords = Lists.newArrayList();
    insertRecords.add(tableTestHelper().generateTestRecord(1, "sam", 0, "2022-01-01T12:00:00"));
    insertRecords.add(tableTestHelper().generateTestRecord(2, "lily", 0, "2022-01-02T12:00:00"));
    insertRecords.add(tableTestHelper().generateTestRecord(3, "john", 0, "2022-01-03T12:00:00"));
    List<DataFile> dataFiles =
        HiveDataTestHelpers.writerOf(getArcticTable()).transactionId(2L).writeHive(insertRecords);
    HiveDataTestHelpers.assertWriteConsistentFilesName(getArcticTable(), dataFiles);
    UnkeyedTable baseStore = ArcticTableUtil.baseStore(getArcticTable());
    ReplacePartitions replacePartitions = baseStore.newReplacePartitions();
    dataFiles.forEach(replacePartitions::addFile);
    replacePartitions.commit();
    HiveDataTestHelpers.assertWriteConsistentFilesCommit(getArcticTable());
    dataFiles = HiveDataTestHelpers.lastedAddedFiles(baseStore);

    UpdateHiveFilesTestHelpers.validateHiveTableValues(
        TEST_HMS.getHiveClient(), getArcticTable(), dataFiles);
  }

  @Test
  public void testRewritePartPartitions() throws TException {
    initDataFiles();
    DataFile p1DataFile =
        initDataFiles.stream()
            .filter(dataFile -> dataFile.path().toString().contains("2022-01-01"))
            .findAny()
            .orElseThrow(() -> new IllegalStateException("Cannot find expect data file"));
    List<Record> insertRecords = Lists.newArrayList();
    insertRecords.add(tableTestHelper().generateTestRecord(2, "lily", 0, "2022-01-02T12:00:00"));
    insertRecords.add(tableTestHelper().generateTestRecord(3, "john", 0, "2022-01-03T12:00:00"));
    List<DataFile> dataFiles =
        HiveDataTestHelpers.writerOf(getArcticTable()).transactionId(2L).writeHive(insertRecords);
    UnkeyedTable baseStore = ArcticTableUtil.baseStore(getArcticTable());
    ReplacePartitions replacePartitions = baseStore.newReplacePartitions();
    dataFiles.forEach(replacePartitions::addFile);
    replacePartitions.commit();
    dataFiles = HiveDataTestHelpers.lastedAddedFiles(baseStore);

    List<DataFile> expectDataFiles = Lists.newArrayList(dataFiles);
    expectDataFiles.add(p1DataFile);

    UpdateHiveFilesTestHelpers.validateHiveTableValues(
        TEST_HMS.getHiveClient(), getArcticTable(), expectDataFiles);
  }

  @Test
  public void testRewritePartitionInTransaction() throws TException {
    initDataFiles();
    List<Record> insertRecords = Lists.newArrayList();
    insertRecords.add(tableTestHelper().generateTestRecord(1, "sam", 0, "2022-01-01T12:00:00"));
    insertRecords.add(tableTestHelper().generateTestRecord(2, "lily", 0, "2022-01-02T12:00:00"));
    insertRecords.add(tableTestHelper().generateTestRecord(3, "john", 0, "2022-01-03T12:00:00"));
    List<DataFile> dataFiles =
        HiveDataTestHelpers.writerOf(getArcticTable()).transactionId(2L).writeHive(insertRecords);
    Transaction transaction = getBaseStore().newTransaction();
    ReplacePartitions replacePartitions = transaction.newReplacePartitions();
    dataFiles.forEach(replacePartitions::addFile);
    replacePartitions.commit();
    transaction.commitTransaction();
    dataFiles = HiveDataTestHelpers.lastedAddedFiles(getBaseStore());

    UpdateHiveFilesTestHelpers.validateHiveTableValues(
        TEST_HMS.getHiveClient(), getArcticTable(), dataFiles);
  }

  @Test
  public void testRewriteCleanUntrackedFiles() throws TException {
    initDataFiles();
    List<Record> insertRecords = Lists.newArrayList();
    insertRecords.add(tableTestHelper().generateTestRecord(1, "john", 0, "2022-01-01T12:00:00"));
    insertRecords.add(tableTestHelper().generateTestRecord(2, "lily", 0, "2022-01-02T12:00:00"));

    String hiveLocation = "test_hive_location";
    HiveDataTestHelpers.writerOf(getArcticTable())
        .transactionId(1L)
        .customHiveLocation(hiveLocation)
        .writeHive(insertRecords);
    // rewrite data files
    List<DataFile> rewriteDataFiles =
        HiveDataTestHelpers.writerOf(getArcticTable())
            .transactionId(2L)
            .customHiveLocation(hiveLocation)
            .writeHive(insertRecords);
    ReplacePartitions replacePartitions = getBaseStore().newReplacePartitions();
    rewriteDataFiles.forEach(replacePartitions::addFile);
    replacePartitions.set(DELETE_UNTRACKED_HIVE_FILE, "true");
    replacePartitions.commit();

    rewriteDataFiles = HiveDataTestHelpers.lastedAddedFiles(getBaseStore());
    UpdateHiveFilesTestHelpers.validateHiveTableValues(
        TEST_HMS.getHiveClient(), getArcticTable(), rewriteDataFiles);
  }
}
