# Sports example

Example application that illustrates PartitionedFileSet datasets using sports results.

Features introduced: Partitioned file sets, MapReduce with runtime arguments, Ad-hoh queries over file sets.

- Uses a partitioned file set to store game results. It is partitioned by league and season, and ech partition
  is a file containing the results of one league for ine season, for example the 2014 season of the NFL.
- Results are uploaded into the file set using a service.
- The results can be explored using ad-hoc SQL queries.
- A MapReduce program reads all results for one league and aggregates total counts across all seasons, and writes
  these to another partitioned table that is partitioned only by league.
- The totals can also be queried using ad-hoc SQL.

For more information on running CDAP examples, see
http://docs.cask.co/cdap/current/en/examples-manual/examples/index.html.

Cask is a trademark of Cask Data, Inc. All rights reserved.

Copyright © 2015 Cask Data, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
except in compliance with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific language governing permissions
and limitations under the License.
