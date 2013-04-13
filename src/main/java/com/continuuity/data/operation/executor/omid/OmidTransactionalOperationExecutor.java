/**
 * Copyright (C) 2012 Continuuity, Inc.
 */
package com.continuuity.data.operation.executor.omid;

import com.continuuity.api.data.OperationException;
import com.continuuity.api.data.OperationResult;
import com.continuuity.common.metrics.CMetrics;
import com.continuuity.common.metrics.MetricType;
import com.continuuity.common.utils.ImmutablePair;
import com.continuuity.data.metadata.MetaDataEntry;
import com.continuuity.data.metadata.MetaDataStore;
import com.continuuity.data.metadata.SerializingMetaDataStore;
import com.continuuity.data.operation.ClearFabric;
import com.continuuity.data.operation.CompareAndSwap;
import com.continuuity.data.operation.Delete;
import com.continuuity.data.operation.Increment;
import com.continuuity.data.operation.OpenTable;
import com.continuuity.data.operation.OperationContext;
import com.continuuity.data.operation.Read;
import com.continuuity.data.operation.ReadAllKeys;
import com.continuuity.data.operation.ReadColumnRange;
import com.continuuity.data.operation.StatusCode;
import com.continuuity.data.operation.Write;
import com.continuuity.data.operation.WriteOperation;
import com.continuuity.data.operation.WriteOperationComparator;
import com.continuuity.data.operation.executor.ReadPointer;
import com.continuuity.data.operation.executor.Transaction;
import com.continuuity.data.operation.executor.TransactionalOperationExecutor;
import com.continuuity.data.operation.ttqueue.DequeueResult;
import com.continuuity.data.operation.ttqueue.EnqueueResult;
import com.continuuity.data.operation.ttqueue.QueueAck;
import com.continuuity.data.operation.ttqueue.QueueAdmin;
import com.continuuity.data.operation.ttqueue.QueueAdmin.GetGroupID;
import com.continuuity.data.operation.ttqueue.QueueConsumer;
import com.continuuity.data.operation.ttqueue.QueueDequeue;
import com.continuuity.data.operation.ttqueue.QueueEnqueue;
import com.continuuity.data.operation.ttqueue.QueueFinalize;
import com.continuuity.data.operation.ttqueue.QueueProducer;
import com.continuuity.data.operation.ttqueue.TTQueue;
import com.continuuity.data.operation.ttqueue.TTQueueTable;
import com.continuuity.data.table.OVCTableHandle;
import com.continuuity.data.table.OrderedVersionedColumnarTable;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static com.continuuity.data.operation.ttqueue.QueueAdmin.GetQueueInfo;
import static com.continuuity.data.operation.ttqueue.QueueAdmin.QueueInfo;

/**
 * Implementation of an {@link com.continuuity.data.operation.executor.OperationExecutor}
 * that executes all operations within Omid-style transactions.
 *
 * See https://github.com/yahoo/omid/ for more information on the Omid design.
 */
@Singleton
public class OmidTransactionalOperationExecutor
  implements TransactionalOperationExecutor {

  private static final Logger Log
    = LoggerFactory.getLogger(OmidTransactionalOperationExecutor.class);

  public String getName() {
    return "omid(" + tableHandle.getName() + ")";
  }

  /**
   * The Transaction Oracle used by this executor instance.
   */
  @Inject
  TransactionOracle oracle;

  /**
   * The {@link OVCTableHandle} handle used to get references to tables.
   */
  @Inject
  OVCTableHandle tableHandle;

  private OrderedVersionedColumnarTable metaTable;
  private OrderedVersionedColumnarTable randomTable;

  private MetaDataStore metaStore;

  private TTQueueTable queueTable;
  private TTQueueTable streamTable;

  public static boolean DISABLE_QUEUE_PAYLOADS = false;

  static int MAX_DEQUEUE_RETRIES = 200;
  static long DEQUEUE_RETRY_SLEEP = 5;

  // Metrics

  /* -------------------  data fabric system metrics ---------------- */
  private CMetrics cmetric = new CMetrics(MetricType.System);

  private static final String METRIC_PREFIX = "omid-opex-";

  private void requestMetric(String requestType) {
    // TODO rework metric emission to avoid always generating the metric names
    cmetric.meter(METRIC_PREFIX + requestType + "-numops", 1);
  }

  private long begin() { return System.currentTimeMillis(); }
  private void end(String requestType, long beginning) {
    cmetric.histogram(METRIC_PREFIX + requestType + "-latency",
        System.currentTimeMillis() - beginning);
  }

  /* -------------------  (interstitial) queue metrics ---------------- */
  private ConcurrentMap<String, CMetrics> queueMetrics =
      new ConcurrentHashMap<String, CMetrics>();

  private CMetrics getQueueMetric(String group) {
    CMetrics metric = queueMetrics.get(group);
    if (metric == null) {
      queueMetrics.putIfAbsent(group,
          new CMetrics(MetricType.FlowSystem, group));
      metric = queueMetrics.get(group);
      Log.trace("Created new CMetrics for group '" + group + "'.");
      // System.err.println("Created new CMetrics for group '" + group + "'.");
    }
    return metric;
  }

  private ConcurrentMap<byte[], ImmutablePair<String, String>>
      queueMetricNames = new ConcurrentSkipListMap<byte[],
      ImmutablePair<String, String>>(Bytes.BYTES_COMPARATOR);

  private ImmutablePair<String, String> getQueueMetricNames(byte[] queue) {
    ImmutablePair<String, String> names = queueMetricNames.get(queue);
    if (names == null) {
      String name = new String(queue).replace(":", "");
      queueMetricNames.putIfAbsent(queue, new ImmutablePair<String, String>
          ("q.enqueue." + name, "q.ack." + name));
      names = queueMetricNames.get(queue);
      Log.trace("using metric name '" + names.getFirst() + "' and '"
          + names.getSecond() + "' for queue '" + new String(queue) + "'");
      //System.err.println("using metric name '" + names.getFirst() + "' and '"
      //    + names.getSecond() + "' for queue '" + new String(queue) + "'");
    }
    return names;
  }

  private void enqueueMetric(byte[] queue, QueueProducer producer) {
    if (producer != null && producer.getProducerName() != null) {
      String metricName = getQueueMetricNames(queue).getFirst();
      getQueueMetric(producer.getProducerName()).meter(metricName, 1);
    }
  }

  private void ackMetric(byte[] queue, QueueConsumer consumer) {
    if (consumer != null && consumer.getGroupName() != null) {
      String metricName = getQueueMetricNames(queue).getSecond();
      getQueueMetric(consumer.getGroupName()).meter(metricName, 1);
    }
  }


  /* -------------------  (global) stream metrics ---------------- */
  private CMetrics streamMetric = // we use a global flow group
      new CMetrics(MetricType.FlowSystem, "-.-.-.-.-.0");

  private ConcurrentMap<byte[], ImmutablePair<String, String>>
      streamMetricNames = new ConcurrentSkipListMap<byte[],
      ImmutablePair<String, String>>(Bytes.BYTES_COMPARATOR);

  private ImmutablePair<String, String> getStreamMetricNames(byte[] stream) {
    ImmutablePair<String, String> names = streamMetricNames.get(stream);
    if (names == null) {
      String name = new String(stream).replace(":", "");
      streamMetricNames.putIfAbsent(stream, new ImmutablePair<String, String>(
        "stream.enqueue." + name, "stream.storage." + name));
      names = streamMetricNames.get(stream);
      Log.trace("using metric name '" + names.getFirst() + "' and '"
          + names.getSecond() + "' for stream '" + new String(stream) + "'");
      //System.err.println("using metric name '" + names.getFirst() + "' and '"
      //    + names.getSecond() + "' for stream '" + new String(stream) + "'");
    }
    return names;
  }

  private boolean isStream(byte[] queueName) {
    return Bytes.startsWith(queueName, TTQueue.STREAM_NAME_PREFIX);
  }

  private int streamSizeEstimate(byte[] streamName, int dataSize, int numEntries) {
    // assume HBase uses space for the stream name, the data, and some metadata
    return dataSize + numEntries * (streamName.length + 50);
  }

  private void streamMetric(byte[] streamName, int dataSize, int numEntries) {
    ImmutablePair<String, String> names = getStreamMetricNames(streamName);
    streamMetric.meter(names.getFirst(), 1);
    streamMetric.meter(names.getSecond(), streamSizeEstimate(streamName, dataSize, numEntries));
  }

  private void dataSetMetric_read(String dataSetName) {
    streamMetric.meter("dataset.read." + dataSetName, 1);
  }
  
  private void dataSetMetric_write(String dataSetName, int dataSize) {
    streamMetric.meter("dataset.write." + dataSetName, 1);
    streamMetric.meter("dataset.storage." + dataSetName, dataSize);
  }
  
  /* -------------------  end metrics ---------------- */

  // named table management

  // a map of logical table name to existing <real name, table>, caches
  // the meta data store and the ovc table handle
  // there are three possible states for a table:
  // 1. table does not exist or is not known -> no entry
  // 2. table is being created -> entry with real name, but null for the table
  // 3. table is known -> entry with name and table
  ConcurrentMap<ImmutablePair<String,String>,
      ImmutablePair<byte[], OrderedVersionedColumnarTable>> namedTables;

  // method to find - and if necessary create - a table
  OrderedVersionedColumnarTable findRandomTable(OperationContext context, String name) throws OperationException {

    // check whether it is one of the default tables these are always
    // pre-loaded at initializaton and we can just return them
    if (null == name)
      return this.randomTable;
    if ("meta".equals(name))
      return this.metaTable;

    // look up table in in-memory map. if this returns:
    // an actual name and OVCTable, return that OVCTable
    ImmutablePair<String, String> tableKey = new
        ImmutablePair<String, String>(context.getAccount(), name);
    ImmutablePair<byte[], OrderedVersionedColumnarTable> nameAndTable =
        this.namedTables.get(tableKey);
    if (nameAndTable != null) {
      if (nameAndTable.getSecond() != null)
        return nameAndTable.getSecond();

      // an actual name and null for the table, then sleep/repeat until the look
      // up returns non-null for the table. This is the case when some other
      // thread in the same process has generated an actual name and is in the
      // process of creating that table.
      return waitForTableToMaterialize(tableKey);
    }
    // null: then this table has not been opened by any thread in this
    // process. In this case:
    // Read the meta data for the logical name from MDS.
    MetaDataEntry meta = this.metaStore.get(
        context, context.getAccount(), null, "namedTable", name);
    if (null != meta) {
      return waitForTableToMaterializeInMeta(context, name, meta);

    // Null: Nobody has started to create this.
    } else {
      // Generate a new actual name, and write that name with status Pending
      // to MDS in a Compare-and-Swap operation
      byte[] actualName = generateActualName(context, name);
      MetaDataEntry newMeta = new MetaDataEntry(context.getAccount(), null,
          "namedTable", name);
      newMeta.addField("actual", actualName);
      newMeta.addField("status", "pending");
      try {
        this.metaStore.add(context, newMeta);
      } catch (OperationException e) {
        if (e.getStatus() == StatusCode.WRITE_CONFLICT) {
          // If C-a-S failed with write conflict, then some other process (or
          // thread) has concurrently attempted the same and wins.
          return waitForTableToMaterializeInMeta(context, name, newMeta);
        }
        else throw e;
      }
      //C-a-S succeeded, add <actual name, null> to MEM to inform other threads
      //in this process to wait (no other thread could have updated in the
      //    mean-time without updating MDS)
      this.namedTables.put(tableKey,
          new ImmutablePair<byte[], OrderedVersionedColumnarTable>(
              actualName, null));

      //Create a new actual table for the actual name. This should never fail.
      OrderedVersionedColumnarTable table =
          getTableHandle().getTable(actualName);

      // Update MDS with the new status Ready. This can be an ordinary Write
      newMeta.addField("status", "ready");
      this.metaStore.update(context, newMeta);

      // because all others are waiting.
      // Update MEM with the actual created OVCTable.
      this.namedTables.put(tableKey,
          new ImmutablePair<byte[], OrderedVersionedColumnarTable>(
              actualName, table));
      //Return the created table.
      return table;
    }
  }

  private byte[] generateActualName(OperationContext context, String name) {
    // TODO make this generate a new id every time it is called
    return ("random_" + context.getAccount() + "_" + name).getBytes();
  }

  private OrderedVersionedColumnarTable waitForTableToMaterialize(ImmutablePair<String, String> tableKey) {
    while (true) {
      ImmutablePair<byte[], OrderedVersionedColumnarTable> nameAndTable =
          this.namedTables.get(tableKey);
      if (nameAndTable == null) {
        throw new RuntimeException("In-memory entry went from non-null to null " +
            "for named table \"" + tableKey.getSecond() + "\"");
      }
      if (nameAndTable.getSecond() != null) {
        return nameAndTable.getSecond();
      }
      // sleep a little
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        // what the heck should I do?
      }
    }
    // TODO should this time out after some time or number of attempts?
  }

  OrderedVersionedColumnarTable waitForTableToMaterializeInMeta(OperationContext context,
                                                                String name,
                                                                MetaDataEntry meta)
    throws OperationException {

    while(true) {
      // If this returns: An actual name and status Ready: The table is ready
      // to use, open the table, add it to MEM and return it
      if ("ready".equals(meta.getTextField("status"))) {
        byte[] actualName = meta.getBinaryField("actual");
        if (actualName == null)
          throw new RuntimeException("Encountered meta data entry of type " +
              "\"namedTable\" without actual name for table name \"" +
              name +"\".");
        OrderedVersionedColumnarTable table =
            getTableHandle().getTable(actualName);
        if (table == null)
          throw new RuntimeException("table handle \"" + getTableHandle()
              .getName() + "\": getTable returned null for actual table name "
              + "\"" + new String(actualName) + "\"");

        // update MEM. This can be ordinary put, because even if some other
        // thread updated it in the meantime, it would have put the same table.
        ImmutablePair<String, String> tableKey = new
            ImmutablePair<String, String>(context.getAccount(), name);
        this.namedTables.put(tableKey,
            new ImmutablePair<byte[], OrderedVersionedColumnarTable>(
                actualName, table));
        return table;
      }
      // An actual name and status Pending: The table is being created. Loop
      // and repeat MDS read until status is Ready and see previous case
      else if (!"pending".equals(meta.getTextField("status"))) {
        throw new RuntimeException("Found meta data entry with unkown status " +
            Objects.toStringHelper(meta.getTextField("status")));
      }

      // sleep a little
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        // what the heck should I do?
      }

      // reread the meta data, hopefully it has changed to ready
      meta = this.metaStore.get(
          context, context.getAccount(), null, "namedTable", name);
      if (meta == null) {
        // this should never happen - we only enter this method in two cases:
        // 1. there is already an entry, but it might be pending
        // 2. we encountered a read conflict when attempting to create it
        //    - hence this get must see that conflicting write
        // TODO what if someone deleted it after the write conflict happened?
        // TODO what if the write conflict was caused by a delete?
        throw new RuntimeException("Meta data entry went from non-null to null " +
            "for table \"" + name + "\"");
      }
      // TODO should this time out after some time or number of attempts?
    }
  }

  // Single reads

  @Override
  public OperationResult<List<byte[]>> execute(OperationContext context,
                                               ReadAllKeys readKeys)
    throws OperationException {
    return execute(context, null, readKeys);
  }

  @Override
  public OperationResult<List<byte[]>> execute(OperationContext context,
                                               Transaction transaction,
                                               ReadAllKeys readKeys)
    throws OperationException {

    initialize();
    requestMetric("ReadAllKeys");
    long begin = begin();
    OrderedVersionedColumnarTable table = this.findRandomTable(context, readKeys.getTable());
    ReadPointer pointer =
      transaction == null ? this.oracle.getReadPointer() : transaction.getReadPointer();
    List<byte[]> result = table.getKeys(readKeys.getLimit(), readKeys.getOffset(), pointer);
    end("ReadAllKeys", begin);
    dataSetMetric_read(readKeys.getMetricName());
    return new OperationResult<List<byte[]>>(result);
  }

  @Override
  public OperationResult<Map<byte[], byte[]>> execute(OperationContext context,
                                                      Read read)
    throws OperationException {
    return execute(context, null, read);
  }

  @Override
  public OperationResult<Map<byte[], byte[]>> execute(OperationContext context,
                                                      Transaction transaction,
                                                      Read read)
    throws OperationException {
    initialize();
    requestMetric("Read");
    long begin = begin();
    OrderedVersionedColumnarTable table = this.findRandomTable(context, read.getTable());
    ReadPointer pointer =
      transaction == null ? this.oracle.getReadPointer() : transaction.getReadPointer();
    OperationResult<Map<byte[], byte[]>> result =
      table.get(read.getKey(), read.getColumns(), pointer);
    end("Read", begin);
    dataSetMetric_read(read.getMetricName());
    return result;
  }

  @Override
  public OperationResult<Map<byte[], byte[]>> execute(OperationContext context,
                                                      ReadColumnRange readColumnRange)
    throws OperationException {
    return execute(context, null, readColumnRange);
  }

  @Override
  public OperationResult<Map<byte[], byte[]>> execute(OperationContext context,
                                                      Transaction transaction,
                                                      ReadColumnRange readColumnRange)
    throws OperationException {

    initialize();
    requestMetric("ReadColumnRange");
    long begin = begin();
    OrderedVersionedColumnarTable table =
      this.findRandomTable(context, readColumnRange.getTable());
    ReadPointer pointer =
      transaction == null ? this.oracle.getReadPointer() : transaction.getReadPointer();
    OperationResult<Map<byte[], byte[]>> result = table.get(
      readColumnRange.getKey(), readColumnRange.getStartColumn(),
      readColumnRange.getStopColumn(), readColumnRange.getLimit(),
      pointer);
    end("ReadColumnRange", begin);
    dataSetMetric_read(readColumnRange.getMetricName());
    return result;
  }

  // Administrative calls

  @Override
  public void execute(OperationContext context,
                      ClearFabric clearFabric) throws OperationException {
    initialize();
    requestMetric("ClearFabric");
    long begin = begin();
    if (clearFabric.shouldClearData()) this.randomTable.clear();
    if (clearFabric.shouldClearTables()) {
      List<MetaDataEntry> entries = this.metaStore.list(
          context, context.getAccount(), null, "namedTable", null);
      for (MetaDataEntry entry : entries) {
        String name = entry.getId();
        OrderedVersionedColumnarTable table = findRandomTable(context, name);
        table.clear();
        this.namedTables.remove(new ImmutablePair<String,
            String>(context.getAccount(),name));
        this.metaStore.delete(context, entry.getAccount(),
            entry.getApplication(), entry.getType(), entry.getId());
      }
    }
    if (clearFabric.shouldClearMeta()) this.metaTable.clear();
    if (clearFabric.shouldClearQueues()) this.queueTable.clear();
    if (clearFabric.shouldClearStreams()) this.streamTable.clear();
    end("ClearFabric", begin);
  }

  @Override
  public void execute(OperationContext context, OpenTable openTable)
    throws OperationException {
    initialize();
    findRandomTable(context, openTable.getTableName());
  }

  // Write batches

  @Override
  public void commit(OperationContext context, List<WriteOperation> writes)
    throws OperationException {
    initialize();
    requestMetric("WriteOperationBatch");
    long begin = begin();
    cmetric.meter(METRIC_PREFIX + "WriteOperationBatch_NumReqs", writes.size());
    commit(context, startTransaction(), writes);
    end("WriteOperationBatch", begin);
  }

  @Override
  public Transaction startTransaction(OperationContext context)
    throws OperationException {
    return this.startTransaction();
  }

  @Override
  public Transaction execute(OperationContext context,
                             Transaction transaction,
                             List<WriteOperation> writes) throws OperationException {
    // make sure we have a transaction
    if (transaction == null) {
      transaction = startTransaction();
    }

    // TODO should we add an empty batch of undos to the transaction in oracle?
    // TODO That would update the timestamp of the transaction and prevent it from time out
    if (writes.isEmpty()) {
      return transaction;
    }

    // Re-order operations (create a copy for now)
    List<WriteOperation> orderedWrites = new ArrayList<WriteOperation>(writes);
    Collections.sort(orderedWrites, new WriteOperationComparator());

    // Execute operations
    List<Undo> undos = new ArrayList<Undo>(writes.size());

    boolean abort = false;
    WriteTransactionResult writeTxReturn = null;
    for (WriteOperation write : orderedWrites) {

      writeTxReturn = dispatchWrite(context, write, transaction);

      if (!writeTxReturn.success) {
        // Write operation failed
        cmetric.meter(METRIC_PREFIX + "WriteOperationBatch_FailedWrites", 1);
        abort = true;
        break;
      } else {
        // Write was successful.  Store undo if we need to abort and continue
        undos.addAll(writeTxReturn.undos);
      }
    }

    // whether success or not, we must notify the oracle of all operations
    if (!undos.isEmpty()) {
      addToTransaction(transaction, undos);
    }

    // if any write failed, abort the transaction
    if (abort) {
      abort(context, transaction);
      throw new OmidTransactionException(
        writeTxReturn.statusCode, writeTxReturn.message);
      }
    return transaction; // TODO auto generated body
    }

  @Override
  public void commit(OperationContext context,
                     Transaction transaction)
    throws OperationException {

    // attempt to commit in Oracle
    TransactionResult txResult = commitTransaction(transaction);
    if (!txResult.isSuccess()) {
      // make sure to emit the metric for failed commits
      cmetric.meter(METRIC_PREFIX + "WriteOperationBatch_FailedCommits", 1);

      // attempt to undo all the writes of the transaction
      // (transaction is already marked as invalid in oracle)
      attemptUndo(context, transaction, txResult.getUndos());

      throw new OmidTransactionException(StatusCode.WRITE_CONFLICT,
                                         "Commit of transaction failed, transaction aborted");
    }
    // Commit was successful.

    // TODO this must go away with the new queue implementation
    // If the transaction did a queue ack, finalize it
    QueueFinalize finalize = txResult.getFinalize();
    if (finalize != null) {
      finalize.execute(getQueueTable(finalize.getQueueName()), transaction.getWriteVersion());
    }

    // emit metrics for the transaction and the queues/streams involved
    cmetric.meter(
      METRIC_PREFIX + "WriteOperationBatch_SuccessfulTransactions", 1);
    // for each queue operation (enqueue or ack)
    for (Undo undo : txResult.getUndos()) {
      if (undo instanceof QueueUndo.QueueUnenqueue) {
        QueueUndo.QueueUnenqueue unenqueue = (QueueUndo.QueueUnenqueue)undo;
        QueueProducer producer = unenqueue.producer;
        enqueueMetric(unenqueue.queueName, producer);
        if (isStream(unenqueue.queueName)) {
          streamMetric(unenqueue.queueName, unenqueue.sumOfSizes, unenqueue.numEntries());
        }
      } else if (undo instanceof QueueUndo.QueueUnack) {
        QueueUndo.QueueUnack unack = (QueueUndo.QueueUnack)undo;
        QueueConsumer consumer = unack.consumer;
        ackMetric(unack.queueName, consumer);
      }
    }
    // done
  }

  @Override
  public void commit(OperationContext context,
                     Transaction transaction,
                     List<WriteOperation> writes) throws OperationException {
    transaction = execute(context, transaction, writes);
    commit(context, transaction);
  }

  @Override
  public void abort(OperationContext context, Transaction transaction) throws OperationException {
    // abort transaction in oracle, that returns the undos to be performed
    TransactionResult txResult = abortTransaction(transaction);
    // attempt to ubdo all the writes of the transaction
    // (transaction is already marked as invalid in oracle)
    attemptUndo(context, transaction, txResult.getUndos());
  }

  @Override
  public Map<byte[], Long> increment(OperationContext context,
                                     Increment increment) throws
    OperationException {
    // start transaction, execute increment, commit transaction, return result
    Transaction tx = startTransaction();
    Map<byte[], Long> result = increment(context, tx, increment);
    commit(context, tx);
    return result;
  }

  @Override
  public Map<byte[], Long> increment(OperationContext context,
                                     Transaction transaction,
                                     Increment increment) throws OperationException {
    // if a null transaction is passed in,
    // call the companion method that wraps this into a new transaction
    if (transaction == null) {
      return increment(context, increment);
    }

    WriteTransactionResult writeTxReturn = write(context, increment, transaction);
    List<Undo> undos = writeTxReturn.undos;
    // whether success or not, we must notify the oracle of all operations
    if (null != undos && !undos.isEmpty()) {
      addToTransaction(transaction, undos);
    }
    if (writeTxReturn.success) {
      // increment was successful. the return value is in the write transaction result
      return writeTxReturn.incrementResult;
    } else {
      // operation failed
      cmetric.meter(METRIC_PREFIX + "WriteOperationBatch_FailedWrites", 1);
      abort(context, transaction);
      throw new OmidTransactionException(writeTxReturn.statusCode, writeTxReturn.message);
    }
  }

  public OVCTableHandle getTableHandle() {
    return this.tableHandle;
  }

  static final List<Undo> noUndos = Collections.emptyList();

  class WriteTransactionResult {
    final boolean success;
    final int statusCode;
    final String message;
    final List<Undo> undos;
    Map<byte[], Long> incrementResult;

    WriteTransactionResult(boolean success, int status, String message, List<Undo> undos) {
      this.success = success;
      this.statusCode = status;
      this.message = message;
      this.undos = undos;
    }

    // successful, one delete to undo
    WriteTransactionResult(Undo undo) {
      this(true, StatusCode.OK, null, Collections.singletonList(undo));
    }

    // successful increment, one delete to undo
    WriteTransactionResult(Undo undo, Map<byte[], Long> incrementResult) {
      this(true, StatusCode.OK, null, Collections.singletonList(undo));
      this.incrementResult = incrementResult;
    }

    // failure with status code and message, nothing to undo
    WriteTransactionResult(int status, String message) {
      this(false, status, message, noUndos);
    }
  }

  /**
   * Actually perform the various write operations.
   */
  private WriteTransactionResult dispatchWrite(
      OperationContext context, WriteOperation write,
      Transaction transaction) throws OperationException {
    if (write instanceof Write) {
      return write(context, (Write)write, transaction);
    } else if (write instanceof Delete) {
      return write(context, (Delete)write, transaction);
    } else if (write instanceof Increment) {
      return write(context, (Increment)write, transaction);
    } else if (write instanceof CompareAndSwap) {
      return write(context, (CompareAndSwap)write, transaction);
    } else if (write instanceof QueueEnqueue) {
      return write((QueueEnqueue)write, transaction);
    } else if (write instanceof QueueAck) {
      return write((QueueAck)write, transaction);
    }
    return new WriteTransactionResult(StatusCode.INTERNAL_ERROR,
        "Unknown write operation " + write.getClass().getName());
  }

  WriteTransactionResult write(OperationContext context, Write write, Transaction transaction)
    throws OperationException {
    initialize();
    requestMetric("Write");
    long begin = begin();
    OrderedVersionedColumnarTable table = this.findRandomTable(context, write.getTable());
    table.put(write.getKey(), write.getColumns(), transaction.getWriteVersion(), write.getValues());
    end("Write", begin);
    dataSetMetric_write(write.getMetricName(), write.getSize());
//    return new WriteTransactionResult(new Delete(write.getTable(), write.getKey(), write.getColumns()));
    return new WriteTransactionResult(new UndoWrite(write.getTable(), write.getKey(), write.getColumns()));
  }

  WriteTransactionResult write(OperationContext context, Delete delete,
      Transaction transaction) throws OperationException {
    initialize();
    requestMetric("Delete");
    long begin = begin();
    OrderedVersionedColumnarTable table =
        this.findRandomTable(context, delete.getTable());
    table.deleteAll(delete.getKey(), delete.getColumns(), transaction.getWriteVersion());
    end("Delete", begin);
    dataSetMetric_write(delete.getMetricName(), delete.getSize());
    return new WriteTransactionResult(
        new UndoDelete(delete.getTable(), delete.getKey(), delete.getColumns()));
  }

  WriteTransactionResult write(OperationContext context, Increment increment,
      Transaction transaction) throws OperationException {
    initialize();
    requestMetric("Increment");
    long begin = begin();
    Map<byte[],Long> map;
    try {
      OrderedVersionedColumnarTable table =
          this.findRandomTable(context, increment.getTable());
      map = table.increment(increment.getKey(), increment.getColumns(), increment.getAmounts(),
                            transaction.getReadPointer(), transaction.getWriteVersion());
    } catch (OperationException e) {
      return new WriteTransactionResult(e.getStatus(), e.getMessage());
    }
    end("Increment", begin);
    dataSetMetric_write(increment.getMetricName(), increment.getSize());
    return new WriteTransactionResult(
        new UndoWrite(increment.getTable(), increment.getKey(), increment.getColumns()), map);
  }

  WriteTransactionResult write(OperationContext context, CompareAndSwap write,
      Transaction transaction) throws OperationException {
    initialize();
    requestMetric("CompareAndSwap");
    long begin = begin();
    try {
      OrderedVersionedColumnarTable table =
          this.findRandomTable(context, write.getTable());
      table.compareAndSwap(write.getKey(), write.getColumn(), write.getExpectedValue(), write.getNewValue(),
                           transaction.getReadPointer(), transaction.getWriteVersion());
    } catch (OperationException e) {
      return new WriteTransactionResult(e.getStatus(), e.getMessage());
    }
    end("CompareAndSwap", begin);
    dataSetMetric_write(write.getMetricName(), write.getSize());
    return new WriteTransactionResult(
        new UndoWrite(write.getTable(), write.getKey(), new byte[][] { write.getColumn() }));
  }

  // TTQueues

  /**
   * Enqueue operations always succeed but can be rolled back.
   *
   * They are rolled back with an invalidate.
   */
  WriteTransactionResult write(QueueEnqueue enqueue, Transaction transaction) throws OperationException {
    initialize();
    requestMetric("QueueEnqueue");
    long begin = begin();
    EnqueueResult result = getQueueTable(enqueue.getKey()).enqueue(enqueue.getKey(), enqueue.getEntries(),
                                                                   transaction.getWriteVersion());
    end("QueueEnqueue", begin);
    return new WriteTransactionResult(
        new QueueUndo.QueueUnenqueue(enqueue.getKey(), enqueue.getEntries(), enqueue.getProducer(),
                                     result.getEntryPointers()));
  }

  WriteTransactionResult write(QueueAck ack, Transaction transaction)
    throws  OperationException {

    initialize();
    requestMetric("QueueAck");
    long begin = begin();
    try {
      getQueueTable(ack.getKey()).ack(ack.getKey(), ack.getEntryPointers(), ack.getConsumer(),
                                      transaction.getReadPointer());
    } catch (OperationException e) {
      // Ack failed, roll back transaction
      return new WriteTransactionResult(e.getStatus(), e.getMessage());
    } finally {
      end("QueueAck", begin);
    }
    return new WriteTransactionResult(
        new QueueUndo.QueueUnack(ack.getKey(), ack.getEntryPointers(), ack.getConsumer(), ack.getNumGroups()));
  }

  @Override
  public DequeueResult execute(OperationContext context, QueueDequeue dequeue) throws OperationException {
    initialize();
    requestMetric("QueueDequeue");
    long begin = begin();
    int retries = 0;
    long start = System.currentTimeMillis();
    TTQueueTable queueTable = getQueueTable(dequeue.getKey());
    // TODO remove retry loop, new queues don't return retriable ever
    while (retries < MAX_DEQUEUE_RETRIES) {
      DequeueResult result = queueTable.dequeue(dequeue.getKey(), dequeue.getConsumer(),
          this.oracle.getReadPointer());
      if (result.shouldRetry()) {
        retries++;
        try {
          if (DEQUEUE_RETRY_SLEEP > 0) Thread.sleep(DEQUEUE_RETRY_SLEEP);
        } catch (InterruptedException e) {
          e.printStackTrace();
          // continue in loop
        }
        continue;
      }
      end("QueueDequeue", begin);
      return result;
    }
    long end = System.currentTimeMillis();
    end("QueueDequeue", begin);
    throw new OperationException(StatusCode.TOO_MANY_RETRIES,
        "Maximum retries (retried " + retries + " times over " + (end-start) +
        " millis");
  }

  @Override
  public long execute(OperationContext context, GetGroupID getGroupId) throws OperationException {
    initialize();
    requestMetric("GetGroupID");
    long begin = begin();
    TTQueueTable table = getQueueTable(getGroupId.getQueueName());
    long groupid = table.getGroupID(getGroupId.getQueueName());
    end("GetGroupID", begin);
    return groupid;
  }

  @Override
  public OperationResult<QueueAdmin.QueueInfo> execute(OperationContext context, GetQueueInfo getQueueInfo)
                                                       throws OperationException
  {
    initialize();
    requestMetric("GetQueueInfo");
    long begin = begin();
    TTQueueTable table = getQueueTable(getQueueInfo.getQueueName());
    QueueInfo queueInfo = table.getQueueInfo(getQueueInfo.getQueueName());
    end("GetQueueInfo", begin);
    return queueInfo == null ?
        new OperationResult<QueueInfo>(StatusCode.QUEUE_NOT_FOUND) :
        new OperationResult<QueueAdmin.QueueInfo>(queueInfo);
  }

  @Override
  public void execute(OperationContext context, Transaction transaction, QueueAdmin.QueueConfigure configure)
    throws OperationException
  {
    initialize();
    requestMetric("QueueConfigure");
    long begin = begin();
    TTQueueTable table = getQueueTable(configure.getQueueName());
    table.configure(configure.getQueueName(), configure.getConfig(), configure.getGroupId(),
                    configure.getNewConsumerCount());
    end("QueueConfigure", begin);
  }

  Transaction startTransaction() {
    requestMetric("StartTransaction");
    return this.oracle.startTransaction();
  }

  void addToTransaction(Transaction transaction, List<Undo> undos)
      throws OmidTransactionException {
    this.oracle.addToTransaction(transaction, undos);
  }

  TransactionResult commitTransaction(Transaction transaction)
    throws OmidTransactionException {
    requestMetric("CommitTransaction");
    return this.oracle.commitTransaction(transaction);
  }

  TransactionResult abortTransaction(Transaction transaction)
    throws OmidTransactionException {
    requestMetric("CommitTransaction");
    return this.oracle.abortTransaction(transaction);
  }


  private void attemptUndo(OperationContext context,
                           Transaction transaction,
                           List<Undo> undos)
      throws OperationException {
    // Perform queue invalidates
    cmetric.meter(METRIC_PREFIX + "WriteOperationBatch_AbortedTransactions", 1);
    for (Undo undo : undos) {
      if (undo instanceof QueueUndo) {
        QueueUndo queueUndo = (QueueUndo)undo;
        queueUndo.execute(getQueueTable(queueUndo.queueName), transaction);
      }
      if (undo instanceof UndoWrite) {
        UndoWrite tableUndo = (UndoWrite)undo;
        OrderedVersionedColumnarTable table =
            this.findRandomTable(context, tableUndo.getTable());
        if (tableUndo instanceof UndoDelete) {
          table.undeleteAll(tableUndo.getKey(), tableUndo.getColumns(), transaction.getWriteVersion());
        } else {
          table.delete(tableUndo.getKey(), tableUndo.getColumns(), transaction.getWriteVersion());
        }
      }
    }
    // if any of the undos fails, we won't reach this point.
    // That is, the tx will remain in the oracle as invalid
    oracle.removeTransaction(transaction);
  }

  // Single Write Operations (Wrapped and called in a transaction batch)

  @SuppressWarnings("unused")
  private void unsupported(String msg) {
    throw new RuntimeException(msg);
  }

  @Override
  public void commit(OperationContext context, WriteOperation write) throws OperationException {
    commit(context, Collections.singletonList(write));
  }

  private TTQueueTable getQueueTable(byte[] queueName) {
    if (Bytes.startsWith(queueName, TTQueue.QUEUE_NAME_PREFIX))
      return this.queueTable;
    if (Bytes.startsWith(queueName, TTQueue.STREAM_NAME_PREFIX))
      return this.streamTable;
    // by default, use queue table
    return this.queueTable;
  }

  /**
   * A utility method that ensures this class is properly initialized before
   * it can be used. This currently entails creating real objects for all
   * our table handlers.
   */
  private synchronized void initialize() throws OperationException {

    if (this.randomTable == null) {
      this.metaTable = this.tableHandle.getTable(Bytes.toBytes("meta"));
      this.randomTable = this.tableHandle.getTable(Bytes.toBytes("random"));
      this.queueTable = this.tableHandle.getQueueTable(Bytes.toBytes("queues"));
      this.streamTable = this.tableHandle.getStreamTable(Bytes.toBytes("streams"));
      this.namedTables = Maps.newConcurrentMap();
      this.metaStore = new SerializingMetaDataStore(this);
    }
  }
} // end of OmitTransactionalOperationExecutor
