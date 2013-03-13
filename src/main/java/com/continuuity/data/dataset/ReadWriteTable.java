package com.continuuity.data.dataset;


import com.continuuity.api.data.OperationException;
import com.continuuity.api.data.dataset.table.Delete;
import com.continuuity.api.data.dataset.table.Increment;
import com.continuuity.api.data.dataset.table.Swap;
import com.continuuity.api.data.dataset.table.Table;
import com.continuuity.api.data.dataset.table.Write;
import com.continuuity.api.data.dataset.table.WriteOperation;
import com.continuuity.data.DataFabric;
import com.continuuity.data.operation.CompareAndSwap;
import com.continuuity.data.operation.executor.TransactionProxy;

import java.util.Map;

/**
 * The read/write runtime implementation of the Table data set.
 */
public class ReadWriteTable extends ReadOnlyTable {

  /**
   * Given a Table, create a new ReadWriteTable and make it the delegate for that
   * table.
   *
   * @param table the original table
   * @param fabric the data fabric
   * @param metricName the name to use for emitting metrics
   * @param proxy transaction proxy for all operations
   * @return the new ReadWriteTable
   */
  public static ReadWriteTable setReadWriteTable(Table table, DataFabric fabric,
                                                 String metricName, TransactionProxy proxy) {
    ReadWriteTable readWriteTable = new ReadWriteTable(table, fabric, proxy);
    readWriteTable.setMetricName(metricName);
    table.setDelegate(readWriteTable);
    return readWriteTable;
  }

  /**
   * private constructor, only to be called from @see #setReadWriteTable().
   * @param table the original table
   * @param fabric the data fabric
   * @param proxy transaction proxy for all operations
   */
  private ReadWriteTable(Table table, DataFabric fabric, TransactionProxy proxy) {
    super(table, fabric, proxy);
  }

  /**
   * Turn a table operation into a data fabric operation.
   *
   * @param op a table write operation
   * @return the corresponding data fabric operation
   */
  private com.continuuity.data.operation.WriteOperation toOperation(WriteOperation op) {
    com.continuuity.data.operation.WriteOperation operation;
    if (op instanceof Write) {
      Write write = (Write)op;
      operation = new com.continuuity.data.operation.Write(
          this.tableName(), write.getRow(), write.getColumns(), write.getValues());
    }
    else if (op instanceof Delete) {
      Delete delete = (Delete)op;
      operation = new com.continuuity.data.operation.Delete(
          this.tableName(), delete.getRow(), delete.getColumns());
    }
    else if (op instanceof Increment) {
      operation = toOperation((Increment)op);
    }
    else if (op instanceof Swap) {
      Swap swap = (Swap)op;
      operation = new CompareAndSwap(
          this.tableName(), swap.getRow(), swap.getColumn(), swap.getExpected(), swap.getValue());
    }
    else { // can't happen but...
      throw new IllegalArgumentException("Received an operation of unknown type " + op.getClass().getName());
    }
    operation.setMetricName(getMetricName());
    return operation;
  }

  /**
   * Helper to convert an increment operation
   * @param increment the table increment
   * @return a corresponding data fabric increment operation
   */
  private com.continuuity.data.operation.Increment toOperation(Increment increment) {
    com.continuuity.data.operation.Increment operation = new com.continuuity.data.operation.Increment(
      this.tableName(), increment.getRow(), increment.getColumns(), increment.getValues());
    operation.setMetricName(getMetricName());
    return operation;
  }

  @Override
  public void write(WriteOperation op) throws OperationException {
    this.getTransactionAgent().submit(toOperation(op));
  }

  @Override
  public Map<byte[], Long> incrementAndGet(Increment increment) throws OperationException {
    return this.getTransactionAgent().execute(toOperation(increment));
  }

}
