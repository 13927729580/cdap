.. meta::
    :author: Cask Data, Inc.
    :copyright: Copyright © 2015 Cask Data, Inc.

.. _tx-maintenance:

===============================
Transaction Service Maintenance
===============================

.. highlight:: console

The Transaction Service keeps track of all invalid transactions to exclude their writes from all future reads. 
Over time, this *invalid* list can grow and lead to performance degradation. To avoid that, you can prune the *invalid*
list after the data of invalid transactions has been removed |---| which happens during major HBase compactions of the
transactional tables.

To prune the invalid list manually, follow the below steps:

- Run a major compaction on all CDAP transactional tables.

- Find the minimum transaction state cache reload time across all region servers before the major compaction started.
  This can be done by grepping for ``Transaction state reloaded with snapshot`` in HBase region server logs.
  This should give lines like::

    15/08/22 00:22:34 INFO coprocessor.TransactionStateCache: Transaction state reloaded with snapshot from 1440202895873
    15/08/22 00:22:42 INFO coprocessor.TransactionStateCache: Transaction state reloaded with snapshot from 1440202956306
    15/08/22 00:22:44 INFO coprocessor.TransactionStateCache: Transaction state reloaded with snapshot from 1440202956306
    15/08/22 00:22:47 INFO coprocessor.TransactionStateCache: Transaction state reloaded with snapshot from 1440202956306
    15/08/22 00:23:34 INFO coprocessor.TransactionStateCache: Transaction state reloaded with snapshot from 1440202956306

  Pick the minimum time across all region servers.

- Find the minimum prune time across all queues by running tool ``SimpleHBaseQueueDebugger``. This should print lines like::

    $ /opt/cdap/master/bin/svc-master run co.cask.cdap.data.tools.SimpleHBaseQueueDebugger
    Results for queue queue:///ns1/WordCount/WordCounter/counter/queue: min tx timestamp: 1440198510309
    Results for queue queue:///ns1/WordCount/WordCounter/splitter/wordArrayOut: min tx timestamp: 1440198510280
    Results for queue queue:///ns2/WordCount/WordCounter/counter/queue: min tx timestamp: n/a
    Results for queue queue:///ns2/WordCount/WordCounter/splitter/wordArrayOut: min tx timestamp: n/a
    Results for queue queue:///default/WordCount/WordCounter/counter/queue: min tx timestamp: 1440194184568
    Results for queue queue:///default/WordCount/WordCounter/splitter/wordArrayOut: min tx timestamp: 1440194184476
    Results for queue queue:///default/WordCount/WordCounter/splitter/wordOut: min tx timestamp: 1440194184476
    Total results for all queues: min tx timestamp: 1440194184476

  Pick the timestamp from line ``Total results for all queues``.

- Get the minimum time from the above two steps, let this be time ``t``.
Now, the invalid transaction list can be safely pruned
until ``t - 1 day`` using :ref:`a call <http-restful-api-transactions-truncate>`
from the :ref:`Transaction Service HTTP RESTful API <http-restful-api-transactions-truncate>`.

The current length of the invalid transaction list can be obtained using 
:ref:`a call <http-restful-api-transactions-number>` 
from the :ref:`Transaction Service HTTP RESTful API <http-restful-api-transactions-number>`.
