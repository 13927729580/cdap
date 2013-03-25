package com.continuuity.data.table;

import com.continuuity.api.data.OperationException;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.utils.ImmutablePair;
import com.continuuity.data.operation.executor.omid.memory.MemoryReadPointer;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.continuuity.data.hbase.HBaseTestBase;
import com.continuuity.data.runtime.DataFabricDistributedModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class  TestHBaseOVCTable extends TestOVCTable {

  private static final Logger Log = LoggerFactory.getLogger(TestHBaseOVCTable.class);
  protected static Injector injector;

  @BeforeClass
  public static void startEmbeddedHBase() {
    try {
      HBaseTestBase.startHBase();
      CConfiguration conf = CConfiguration.create();
      conf.setBoolean(DataFabricDistributedModule.CONF_ENABLE_NATIVE_QUEUES, false);
      injector = Guice.createInjector(new DataFabricDistributedModule(HBaseTestBase.getConfiguration(),conf));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @AfterClass
  public static void stopEmbeddedHBase() {
    try {
      HBaseTestBase.stopHBase();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected OVCTableHandle injectTableHandle() {
    return injector.getInstance(OVCTableHandle.class);
  }

  // Tests that produce different expected results on Vanilla HBase

  /**
   * Currently produces different results on Vanilla HBase.  Will be fixed in ENG-1840.
   */
  @Override @Test
  public void testMultiColumnReadsAndWrites() throws OperationException {

    byte [] row = Bytes.toBytes("testMultiColumnReadsAndWrites");

    int ncols = 100;
    assertTrue(ncols % 2 == 0); // needs to be even in this test
    byte [][] columns = new byte[ncols][];
    for (int i=0;i<ncols;i++) {
      columns[i] = Bytes.toBytes(new Long(i));
    }
    byte [][] values = new byte[ncols][];
    for (int i=0;i<ncols;i++) values[i] = Bytes.toBytes(Math.abs(r.nextLong()));

    // insert a version of every column, two at a time
    long version = 10L;
    for (int i=0;i<ncols;i+=2) {
      this.table.put(row, new byte [][] {columns[i], columns[i+1]}, version, new byte [][] {values[i], values[i+1]});
    }

    // read them all back at once using all the various read apis

    // get(row)
    Map<byte[],byte[]> colMap = this.table.get(row, RP_MAX).getValue();
    assertEquals(ncols, colMap.size());
    int idx=0;
    for (Map.Entry<byte[], byte[]> entry : colMap.entrySet()) {
      assertTrue(Bytes.equals(entry.getKey(), columns[idx]));
      assertTrue(Bytes.equals(entry.getValue(), values[idx]));
      idx++;
    }

    // get(row,col)
    for(int i=0;i<ncols;i++) {
      byte [] value = this.table.get(row, columns[i], RP_MAX).getValue();
      assertTrue(Bytes.equals(value, values[i]));
    }

    // getWV(row,col)
    for(int i=0;i<ncols;i++) {
      ImmutablePair<byte[],Long> valueAndVersion = this.table.getWithVersion(row, columns[i], RP_MAX).getValue();
      assertTrue(Bytes.equals(valueAndVersion.getFirst(), values[i]));
      assertEquals(new Long(version), valueAndVersion.getSecond());
    }

    // get(row,startCol,stopCol)

    // get(row,start=null,stop=null,unlimited)
    colMap = this.table.get(row, null, null, -1, RP_MAX).getValue();
    assertEquals(ncols, colMap.size());
    idx=0;
    for (Map.Entry<byte[], byte[]> entry : colMap.entrySet()) {
      assertTrue(Bytes.equals(entry.getKey(), columns[idx]));
      assertTrue(Bytes.equals(entry.getValue(), values[idx]));
      idx++;
    }

    // get(row,start=0,stop=ncols+1, limit)
    colMap = this.table.get(row, Bytes.toBytes((long)0),
                            Bytes.toBytes((long)ncols+1), -1, RP_MAX).getValue();
    assertEquals(ncols, colMap.size());
    idx=0;
    for (Map.Entry<byte[], byte[]> entry : colMap.entrySet()) {
      assertTrue(Bytes.equals(entry.getKey(), columns[idx]));
      assertTrue(Bytes.equals(entry.getValue(), values[idx]));
      idx++;
    }

    // get(row,cols[ncols])
    colMap = this.table.get(row, columns, RP_MAX).getValue();
    assertEquals(ncols, colMap.size());
    idx=0;
    for (Map.Entry<byte[], byte[]> entry : colMap.entrySet()) {
      assertTrue(Bytes.equals(entry.getKey(), columns[idx]));
      assertTrue(Bytes.equals(entry.getValue(), values[idx]));
      idx++;
    }

    // get(row,cols[ncols-2])
    byte [][] subCols = Arrays.copyOfRange(columns, 1, ncols - 1);
    colMap = this.table.get(row, subCols, RP_MAX).getValue();
    assertEquals(ncols - 2, colMap.size());
    idx=1;
    for (Map.Entry<byte[], byte[]> entry : colMap.entrySet()) {
      assertTrue(Bytes.equals(entry.getKey(), columns[idx]));
      assertTrue(Bytes.equals(entry.getValue(), values[idx]));
      idx++;
    }

    // get(row,RP=9) = 0 cols
    colMap = this.table.get(row, new MemoryReadPointer(9)).getValue();
    assertEquals(0, colMap.size());

    // delete the first 5 as point deletes
    subCols = Arrays.copyOfRange(columns, 0, 5);
    this.table.delete(row, subCols, version);

    // get returns 5 less
    colMap = this.table.get(row, RP_MAX).getValue();
    assertEquals(ncols - 5, colMap.size());

    // delete the second 5 as delete alls
    subCols = Arrays.copyOfRange(columns, 5, 10);
    this.table.deleteAll(row, subCols, version);

    // get returns 10 less
    colMap = this.table.get(row, RP_MAX).getValue();
    assertEquals(ncols - 10, colMap.size());

    // delete the third 5 as delete alls
    subCols = Arrays.copyOfRange(columns, 10, 15);
    this.table.deleteAll(row, subCols, version);

    // get returns 15 less
    colMap = this.table.get(row, RP_MAX).getValue();
    assertEquals(ncols - 15, colMap.size());

    // undelete the second 5 with old code
    // with new code, undeleteAll would not restore puts from same transation !!
    // so, with new code
    subCols = Arrays.copyOfRange(columns, 5, 10);
    this.table.undeleteAll(row, subCols, version);  // problem!!!

    // get returns 10 less for old code
    // get returns 15 less for new code
    colMap = this.table.get(row, RP_MAX).getValue();
    //assertEquals(ncols - 10, colMap.size());
    assertEquals(ncols - 15, colMap.size());
  }

  /**
   * Currently produces different results on Vanilla HBase.  Will be fixed in ENG-1840.
   */
  @Override @Test
  public void testDeleteBehavior() throws OperationException {

    byte [] row = Bytes.toBytes("testDeleteBehavior");

    // Verify row dne
    assertTrue(this.table.get(row, COL, RP_MAX).isEmpty());

    // Write values 1, 2, 3 @ ts 1, 2, 3
    this.table.put(row, COL, 1L, Bytes.toBytes(1L));
    this.table.put(row, COL, 3L, Bytes.toBytes(3L));
    this.table.put(row, COL, 2L, Bytes.toBytes(2L));

    // Read value, should be 3
    assertEquals(3L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

    // Point delete at 2
    this.table.delete(row, COL, 2L);

    // Read value, should be 3
    assertEquals(3L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

    // Point delete at 3
    this.table.delete(row, COL, 3L);

    // Read value, should be 1 (2 and 3 point deleted)
    assertEquals(1L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

    // DeleteAll at 3
    this.table.deleteAll(row, COL, 3L);

    // Read value, should not exist
    assertTrue(this.table.get(row, COL, RP_MAX).isEmpty());

    // Write at 3 (trying to overwrite existing deletes @ 3)
    this.table.put(row, COL, 3L, Bytes.toBytes(3L));

    // Read value
    // If writes can overwrite deletes at the same timestamp:
    // assertEquals(3L, Bytes.toLong(this.table.get(row, COL, RP_MAX)));
    // Currently, a delete cannot be overwritten on the same version:
    //assertTrue(this.table.get(row, COL, RP_MAX).isEmpty());   //fails
    assertEquals(3L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

    // Undelete the delete all at 3
    this.table.undeleteAll(row, COL, 3L);

    // There is still a point delete at 3, should uncover 1
    assertEquals(1L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

    // DeleteAll at 5
    this.table.deleteAll(row, COL, 5L);

    // Read value, should not exist
    assertTrue(this.table.get(row, COL, RP_MAX).isEmpty());

    // Write at 4
    this.table.put(row, COL, 4L, Bytes.toBytes(4L));

    // Read value, should not exist
    assertTrue(this.table.get(row, COL, RP_MAX).isEmpty());

    // Write at 6
    this.table.put(row, COL, 6L, Bytes.toBytes(6L));

    // Read value, should be 6
    assertEquals(6L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

    // Undelete the delete all at 5
    this.table.undeleteAll(row, COL, 5L);

    // 6 still visible
    assertEquals(6L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

    // 4 is still visible at ts=4
    assertEquals(4L, Bytes.toLong(
      this.table.get(row, COL, new MemoryReadPointer(4)).getValue()));

    // Point delete 6
    this.table.delete(row, COL, 6L);

    // Read value, should now be 4
    assertEquals(4L, Bytes.toLong(this.table.get(row, COL, RP_MAX).getValue()));

  }

  @Test
  public void testDeleteDirty() throws OperationException {
    final int MAX_ROWS = 10;

    // Generate row keys
    byte [] [] rows = new byte[MAX_ROWS][];
    for(int i = 0; i < MAX_ROWS; ++i) {
      rows[i] = Bytes.toBytes(this.getClass().getCanonicalName() + ".testDeleteDirty." + i);
      // Verify row[i] dne
      assertTrue(this.table.get(rows[i], RP_MAX).getValue().isEmpty());
    }

    for(int i = 0; i < MAX_ROWS; ++i) {
      // Write values 1, 2, 3 @ ts 1, 2, 3
      this.table.put(rows[i], Bytes.toBytes("col1_" + i), 1L, Bytes.toBytes(1L));
      this.table.put(rows[i], Bytes.toBytes("col2_" + i), 3L, Bytes.toBytes(MAX_ROWS * 2 + 3L));
      this.table.put(rows[i], Bytes.toBytes("col3_" + i), 2L, Bytes.toBytes(MAX_ROWS * 3 + 2L));
    }

    for(int i = 0; i < MAX_ROWS; ++i) {
      // Check written values
      Map<byte[], byte[]> colMap = this.table.get(rows[i], RP_MAX).getValue();
      assertEquals(1, Bytes.toLong(colMap.get(Bytes.toBytes("col1_" + i))));
      assertEquals(MAX_ROWS * 2 + 3L, Bytes.toLong(colMap.get(Bytes.toBytes("col2_" + i))));
      assertEquals(MAX_ROWS * 3 + 2L, Bytes.toLong(colMap.get(Bytes.toBytes("col3_" + i))));
    }

    // Delete 1st, 2nd row
    this.table.deleteDirty(new byte[][]{rows[0], rows[1]});
    // Assert rows 1st and 2nd are deleted
    assertTrue(this.table.get(rows[0], RP_MAX).getValue().isEmpty());
    assertTrue(this.table.get(rows[1], RP_MAX).getValue().isEmpty());
    // Assert other rows still exist
    for(int i = 2; i < MAX_ROWS; ++i) {
      Map<byte[], byte[]> colMap = this.table.get(rows[i], RP_MAX).getValue();
      assertEquals(1, Bytes.toLong(colMap.get(Bytes.toBytes("col1_" + i))));
      assertEquals(MAX_ROWS * 2 + 3L, Bytes.toLong(colMap.get(Bytes.toBytes("col2_" + i))));
      assertEquals(MAX_ROWS * 3 + 2L, Bytes.toLong(colMap.get(Bytes.toBytes("col3_" + i))));
    }

    // Delete all other rows
    for(int i = 2; i < MAX_ROWS; ++i) {
      this.table.deleteDirty(new byte[][]{rows[i]});
    }

    // Assert all rows are deleted
    for(int i = 0; i < MAX_ROWS; ++i) {
      assertTrue(this.table.get(rows[i], RP_MAX).getValue().isEmpty());
    }
  }

  @Test
  public void testMultiRowPut() throws OperationException {
    final int MAX_ROWS = 10;

    // Generate row keys, cols, values
    byte [] [] rows = new byte[MAX_ROWS][];
    byte [] [] cols = new byte[MAX_ROWS][];
    byte [] [] values = new byte[MAX_ROWS][];
    for(int i = 0; i < MAX_ROWS; ++i) {
      rows[i] = Bytes.toBytes(this.getClass().getCanonicalName() + ".testMultiRowPut." + i);
      // Verify row[i] dne
      assertTrue(this.table.get(rows[i], RP_MAX).getValue().isEmpty());

      cols[i] = Bytes.toBytes("col" + i);
      values[i] = Bytes.toBytes(i);
    }

    // Put data
    this.table.put(rows, cols, 1L, values);

    // Verify data
    for(int i = 0; i < MAX_ROWS; ++i) {
      Map<byte[], byte[]> colMap = this.table.get(rows[i], RP_MAX).getValue();
        assertEquals(i, Bytes.toInt(colMap.get(cols[i])));
    }
  }

  @Test
  public void testMultiRowGet() throws OperationException {
    final int MAX_ROWS = 10;

    // Generate row keys, cols, values
    byte [] [] rows = new byte[MAX_ROWS][];
    byte [] [] cols = new byte[MAX_ROWS][];
    byte [] [] values = new byte[MAX_ROWS][];
    for(int i = 0; i < MAX_ROWS; ++i) {
      rows[i] = Bytes.toBytes(this.getClass().getCanonicalName() + ".testMultiRowGet." + i);
      // Verify row[i] dne
      assertTrue(this.table.get(rows[i], RP_MAX).getValue().isEmpty());

      cols[i] = Bytes.toBytes("col" + i);
      values[i] = Bytes.toBytes(i);
    }

    // Put data
    this.table.put(rows, cols, 1L, values);

    // Get data
    Map<byte[], Map<byte[], byte[]>> valuesMap = this.table.get(rows, cols, RP_MAX).getValue();
    assertFalse(valuesMap.isEmpty());

    // Verify data
    for(int i = 0; i < MAX_ROWS; ++i) {
      assertEquals(i, Bytes.toInt(valuesMap.get(rows[i]).get(cols[i])));
    }
  }

  // Tests that do not work on HBase

  /**
   * Currently not working.  Will be fixed in ENG-421.
   */
  @Override @Test @Ignore
  public void testIncrementCASIncrementWithSameTimestamp() {}

}
