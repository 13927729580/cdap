/*
 * Copyright (c) 2012 Continuuity Inc. All rights reserved.
 */
package com.continuuity.data.runtime;

import com.continuuity.common.runtime.RuntimeModule;
import com.continuuity.data.engine.memory.MemoryOVCTableHandle;
import com.continuuity.data.engine.memory.oracle.MemoryStrictlyMonotonicTimeOracle;
import com.continuuity.data.operation.executor.NoOperationExecutor;
import com.continuuity.data.operation.executor.OperationExecutor;
import com.continuuity.data.operation.executor.omid.OmidTransactionalOperationExecutor;
import com.continuuity.data.operation.executor.omid.TimestampOracle;
import com.continuuity.data.operation.executor.omid.TransactionOracle;
import com.continuuity.data.operation.executor.omid.memory.MemoryOracle;
import com.continuuity.data.table.OVCTableHandle;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Singleton;

/**
 * DataFabricModules defines all of the bindings for the different data
 * fabric modes.
 */
public class DataFabricModules extends RuntimeModule {

  public Module getNoopModules() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        bind(OperationExecutor.class).
            to(NoOperationExecutor.class).in(Singleton.class);
      }
    };
  }

  @Override
  public Module getInMemoryModules() {

      return new AbstractModule() {
      @Override
      protected void configure() {
        bind(TimestampOracle.class).to(MemoryStrictlyMonotonicTimeOracle.class).in(Singleton.class);
        bind(TransactionOracle.class).to(MemoryOracle.class);
        bind(OVCTableHandle.class).to(MemoryOVCTableHandle.class);
        bind(OperationExecutor.class).to(OmidTransactionalOperationExecutor.class).in(Singleton.class);
      }
    };
  }

  @Override
  public Module getSingleNodeModules() {
    return new DataFabricLocalModule();
  }

  @Override
  public Module getDistributedModules() {
    return new DataFabricDistributedModule();
  }

} // end of DataFabricModules
