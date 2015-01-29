/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.cli;

/**
 * Various categories.
 */
public enum CommandCategory {
  GENERAL("General"), APPS("Applications"), STREAMS("Streams"), DATASETS("Datasets"),
  EXPLORE("Explore"), PREFERENCES("Preferences"),
  NAMESPACES("Namespaces"), ADAPTERS("Adapters"), PROGRAMS("Programs"),
  FLOWS("Flows"), WORKFLOWS("Workflows"), MAPREDUCE("MapReduce"), SPARK("Spark"),
  SERVICES("Services"), PROCEDURES("Procedures");

  private static CommandCategory[] exposedValues = new CommandCategory[] {
    // NAMESPACES
    GENERAL, APPS, STREAMS, DATASETS, EXPLORE, ADAPTERS, PREFERENCES, PROGRAMS,
    FLOWS, WORKFLOWS, MAPREDUCE, SPARK, SERVICES, PROCEDURES
  };

  final String name;

  CommandCategory(String name) {
    this.name = name;
  }

  public String getName() {
    return name.toUpperCase();
  }

  public static CommandCategory valueOfNameIgnoreCase(String name) {
    for (CommandCategory commandCategory : CommandCategory.values()) {
      if (name.equalsIgnoreCase(commandCategory.getName())) {
        return commandCategory;
      }
    }
    throw new IllegalArgumentException("Invalid command category: " + name);
  }

  public static CommandCategory[] exposedValues() {
    return exposedValues;
  }
}
