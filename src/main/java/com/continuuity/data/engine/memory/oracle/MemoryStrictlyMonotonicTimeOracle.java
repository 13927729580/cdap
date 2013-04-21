package com.continuuity.data.engine.memory.oracle;

import com.continuuity.data.operation.executor.omid.TimestampOracle;
import com.google.inject.Singleton;

@Singleton
public class MemoryStrictlyMonotonicTimeOracle implements TimestampOracle {

  long last = 0;

  @Override
  public synchronized long getTimestamp() {
    long cur = System.currentTimeMillis();
    if (cur <= last) {
      return ++last;
    }
    last = cur;
    return cur;
  }

}
