/*
 * Copyright © 2014 Cask Data, Inc.
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
package co.cask.cdap.data2.transaction.stream;

import co.cask.cdap.api.data.format.FormatSpecification;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.common.utils.OSDetector;
import co.cask.cdap.data.stream.CoordinatorStreamProperties;
import co.cask.cdap.data.stream.StreamCoordinatorClient;
import co.cask.cdap.data.stream.StreamFileOffset;
import co.cask.cdap.data.stream.StreamUtils;
import co.cask.cdap.explore.client.ExploreFacade;
import co.cask.cdap.internal.io.SchemaTypeAdapter;
import co.cask.cdap.notifications.feeds.NotificationFeedException;
import co.cask.cdap.notifications.feeds.NotificationFeedManager;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.StreamProperties;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * An abstract base {@link StreamAdmin} for File based stream.
 */
public class FileStreamAdmin implements StreamAdmin {

  private static final String CONFIG_FILE_NAME = "config.json";

  private static final Logger LOG = LoggerFactory.getLogger(FileStreamAdmin.class);
  private static final Gson GSON = new GsonBuilder()
    .registerTypeAdapter(Schema.class, new SchemaTypeAdapter())
    .create();

  private final LocationFactory locationFactory;
  private final StreamCoordinatorClient streamCoordinatorClient;
  private final CConfiguration cConf;
  private final StreamConsumerStateStoreFactory stateStoreFactory;
  private final NotificationFeedManager notificationFeedManager;
  private final String streamBaseDirPath;
  private ExploreFacade exploreFacade;

  @Inject
  public FileStreamAdmin(LocationFactory locationFactory, CConfiguration cConf,
                         StreamCoordinatorClient streamCoordinatorClient,
                         StreamConsumerStateStoreFactory stateStoreFactory,
                         NotificationFeedManager notificationFeedManager) {
    this.cConf = cConf;
    this.notificationFeedManager = notificationFeedManager;
    this.locationFactory = locationFactory;
    this.streamBaseDirPath = cConf.get(Constants.Stream.BASE_DIR);
    this.streamCoordinatorClient = streamCoordinatorClient;
    this.stateStoreFactory = stateStoreFactory;
  }

  @SuppressWarnings("unused")
  @Inject(optional = true)
  public void setExploreFacade(ExploreFacade exploreFacade) {
    // Optional injection is used to simplify Guice injection since ExploreFacade is only need when explore is enabled
    this.exploreFacade = exploreFacade;
  }

  @Override
  public void dropAllInNamespace(Id.Namespace namespace) throws Exception {
    // Simply increment the generation of all streams. The actual deletion of file, just like truncate case,
    // is done external to this class.
    List<Location> locations;
    try {
      locations = getStreamsHomeLocation(namespace).list();
    } catch (FileNotFoundException e) {
      // If the stream base doesn't exists, nothing need to be deleted
      locations = ImmutableList.of();
    }

    for (final Location streamLocation : locations) {
      doTruncate(streamLocation);
    }

    // Also drop the state table
    stateStoreFactory.dropAllInNamespace(namespace);
  }

  @Override
  public void configureInstances(Id.Stream streamId, long groupId, int instances) throws Exception {
    Preconditions.checkArgument(instances > 0, "Number of consumer instances must be > 0.");

    LOG.info("Configure instances: {} {}", groupId, instances);

    StreamConfig config = StreamUtils.ensureExists(this, streamId);
    StreamConsumerStateStore stateStore = stateStoreFactory.create(config);
    try {
      Set<StreamConsumerState> states = Sets.newHashSet();
      stateStore.getByGroup(groupId, states);

      Set<StreamConsumerState> newStates = Sets.newHashSet();
      Set<StreamConsumerState> removeStates = Sets.newHashSet();
      mutateStates(groupId, instances, states, newStates, removeStates);

      // Save the states back
      if (!newStates.isEmpty()) {
        stateStore.save(newStates);
        LOG.info("Configure instances new states: {} {} {}", groupId, instances, newStates);
      }
      if (!removeStates.isEmpty()) {
        stateStore.remove(removeStates);
        LOG.info("Configure instances remove states: {} {} {}", groupId, instances, removeStates);
      }

    } finally {
      stateStore.close();
    }
  }

  @Override
  public void configureGroups(Id.Stream streamId, Map<Long, Integer> groupInfo) throws Exception {
    Preconditions.checkArgument(!groupInfo.isEmpty(), "Consumer group information must not be empty.");

    LOG.info("Configure groups for {}: {}", streamId, groupInfo);

    StreamConfig config = StreamUtils.ensureExists(this, streamId);
    StreamConsumerStateStore stateStore = stateStoreFactory.create(config);
    try {
      Set<StreamConsumerState> states = Sets.newHashSet();
      stateStore.getAll(states);

      // Remove all groups that are no longer exists. The offset information in that group can be discarded.
      Set<StreamConsumerState> removeStates = Sets.newHashSet();
      for (StreamConsumerState state : states) {
        if (!groupInfo.containsKey(state.getGroupId())) {
          removeStates.add(state);
        }
      }

      // For each groups, compute the new file offsets if needed
      Set<StreamConsumerState> newStates = Sets.newHashSet();
      for (Map.Entry<Long, Integer> entry : groupInfo.entrySet()) {
        final long groupId = entry.getKey();

        // Create a view of old states which match with the current groupId only.
        mutateStates(groupId, entry.getValue(), Sets.filter(states, new Predicate<StreamConsumerState>() {
          @Override
          public boolean apply(StreamConsumerState state) {
            return state.getGroupId() == groupId;
          }
        }), newStates, removeStates);
      }

      // Save the states back
      if (!newStates.isEmpty()) {
        stateStore.save(newStates);
        LOG.info("Configure groups new states: {} {}", groupInfo, newStates);
      }
      if (!removeStates.isEmpty()) {
        stateStore.remove(removeStates);
        LOG.info("Configure groups remove states: {} {}", groupInfo, removeStates);
      }

    } finally {
      stateStore.close();
    }
  }

  @Override
  public void upgrade() throws Exception {
    // No-op
  }

  @Override
  public StreamConfig getConfig(Id.Stream streamId) throws IOException {
    Location streamLocation = getStreamBaseLocation(streamId);
    Preconditions.checkArgument(streamLocation.isDirectory(), "Stream '%s' does not exist.", streamId);
    return loadConfig(streamLocation);
  }

  @Override
  public void updateConfig(final Id.Stream streamId, final StreamProperties properties) throws IOException {
    Location streamLocation = getStreamBaseLocation(streamId);
    Preconditions.checkArgument(streamLocation.isDirectory(), "Stream '%s' does not exist.", streamId);

    try {
      streamCoordinatorClient.updateProperties(
        streamId, new Callable<CoordinatorStreamProperties>() {
          @Override
          public CoordinatorStreamProperties call() throws Exception {
            StreamProperties oldProperties = updateProperties(streamId, properties);

            FormatSpecification format = properties.getFormat();
            if (format != null) {
              // if the schema has changed, we need to recreate the hive table.
              // Changes in format and settings don't require
              // a hive change, as they are just properties used by the stream storage handler.
              Schema currSchema = oldProperties.getFormat().getSchema();
              Schema newSchema = format.getSchema();
              if (!currSchema.equals(newSchema)) {
                alterExploreStream(streamId, false);
                alterExploreStream(streamId, true);
              }
            }

            return new CoordinatorStreamProperties(properties.getTTL(), properties.getFormat(),
                                                   properties.getNotificationThresholdMB(), null);
          }
        });
    } catch (Exception e) {
      Throwables.propagateIfInstanceOf(e, IOException.class);
      throw new IOException(e);
    }
  }

  @Override
  public long fetchStreamSize(StreamConfig streamConfig) throws IOException {
    return StreamUtils.fetchStreamFilesSize(streamConfig);
  }

  @Override
  public boolean exists(Id.Stream streamId) throws Exception {
    try {
      return getStreamConfigLocation(streamId).exists();
    } catch (IOException e) {
      LOG.error("Exception when check for stream exist.", e);
      return false;
    }
  }

  @Override
  public void create(Id.Stream streamId) throws Exception {
    create(streamId, null);
  }

  @Override
  public void create(final Id.Stream streamId, @Nullable final Properties props) throws Exception {
    final Location streamLocation = getStreamBaseLocation(streamId);
    Locations.mkdirsIfNotExists(streamLocation);

    streamCoordinatorClient.createStream(streamId, new Callable<StreamConfig>() {
      @Override
      public StreamConfig call() throws Exception {
        Location configLocation = getStreamConfigLocation(streamId);
        if (configLocation.exists()) {
          return null;
        }

        Properties properties = (props == null) ? new Properties() : props;
        long partitionDuration = Long.parseLong(properties.getProperty(Constants.Stream.PARTITION_DURATION,
                                                                       cConf.get(Constants.Stream.PARTITION_DURATION)));
        long indexInterval = Long.parseLong(properties.getProperty(Constants.Stream.INDEX_INTERVAL,
                                                                   cConf.get(Constants.Stream.INDEX_INTERVAL)));
        long ttl = Long.parseLong(properties.getProperty(Constants.Stream.TTL, cConf.get(Constants.Stream.TTL)));
        int threshold = Integer.parseInt(properties.getProperty(Constants.Stream.NOTIFICATION_THRESHOLD,
                                                                cConf.get(Constants.Stream.NOTIFICATION_THRESHOLD)));

        StreamConfig config = new StreamConfig(streamId, partitionDuration, indexInterval, ttl, streamLocation,
                                               null, threshold);
        writeConfig(config);
        createStreamFeeds(config);
        alterExploreStream(streamId, true);
        return config;
      }
    });
  }

  /**
   * Create the public {@link Id.NotificationFeed}s that concerns the stream with configuration {@code config}.
   *
   * @param config config of the stream to create feeds for
   */
  private void createStreamFeeds(StreamConfig config) {
    try {
      Id.NotificationFeed streamFeed = new Id.NotificationFeed.Builder()
        .setNamespaceId(config.getStreamId().getNamespaceId())
        .setCategory(Constants.Notification.Stream.STREAM_FEED_CATEGORY)
        .setName(String.format("%sSize", config.getStreamId().getName()))
        .setDescription(String.format("Size updates feed for Stream %s every %dMB",
                                      config.getStreamId(), config.getNotificationThresholdMB()))
        .build();
      notificationFeedManager.createFeed(streamFeed);
    } catch (NotificationFeedException e) {
      LOG.error("Cannot create feed for Stream {}", config.getStreamId(), e);
    }
  }

  @Override
  public void truncate(Id.Stream streamId) throws Exception {
    doTruncate(getStreamBaseLocation(streamId));
  }

  @Override
  public void drop(Id.Stream streamId) throws Exception {
    // Same as truncate
    truncate(streamId);
  }

  private Location getStreamConfigLocation(Id.Stream streamId) throws IOException {
    return getStreamBaseLocation(streamId).append(CONFIG_FILE_NAME);
  }

  // Constructs path: /.../<namespace>/streams/<streamName>, as expected by StreamUtils#getStreamIdFromLocation
  private Location getStreamBaseLocation(Id.Stream streamId) throws IOException {
    return getStreamsHomeLocation(streamId.getNamespace()).append(streamId.getName());
  }

  private Location getStreamsHomeLocation(Id.Namespace namespace) throws IOException {
    return locationFactory.create(namespace.getId()).append(streamBaseDirPath);
  }

  private void doTruncate(final Location streamLocation) {
    final Id.Stream streamId = StreamUtils.getStreamIdFromLocation(streamLocation);
    try {
      streamCoordinatorClient.updateProperties(streamId, new Callable<CoordinatorStreamProperties>() {
        @Override
        public CoordinatorStreamProperties call() throws Exception {
          int newGeneration = StreamUtils.getGeneration(streamLocation) + 1;
          Locations.mkdirsIfNotExists(StreamUtils.createGenerationLocation(streamLocation, newGeneration));
          return new CoordinatorStreamProperties(null, null, null, newGeneration);
        }
      });
    } catch (Exception e) {
      LOG.error("Failed to truncate stream {}", streamId.getName(), e);
    }
  }

  private StreamProperties updateProperties(Id.Stream streamId, StreamProperties properties) throws IOException {
    StreamConfig config = getConfig(streamId);

    StreamConfig.Builder builder = StreamConfig.builder(config);
    if (properties.getTTL() != null) {
      builder.setTTL(properties.getTTL());
    }
    if (properties.getFormat() != null) {
      builder.setFormatSpec(properties.getFormat());
    }
    if (properties.getNotificationThresholdMB() != null) {
      builder.setNotificationThreshold(properties.getNotificationThresholdMB());
    }

    writeConfig(builder.build());
    return new StreamProperties(config.getTTL(), config.getFormat(), config.getNotificationThresholdMB());
  }

  private StreamConfig loadConfig(Location streamLocation) throws IOException {
    Location configLocation = streamLocation.append(CONFIG_FILE_NAME);

    StreamConfig config = GSON.fromJson(
      CharStreams.toString(CharStreams.newReaderSupplier(Locations.newInputSupplier(configLocation), Charsets.UTF_8)),
      StreamConfig.class);

    int threshold = config.getNotificationThresholdMB();
    if (threshold <= 0) {
      // Need to default it for existing configs that were created before notification threshold was added.
      threshold = cConf.getInt(Constants.Stream.NOTIFICATION_THRESHOLD);
    }

    Id.Stream streamId = StreamUtils.getStreamIdFromLocation(streamLocation);
    return new StreamConfig(streamId, config.getPartitionDuration(), config.getIndexInterval(),
                            config.getTTL(), streamLocation, config.getFormat(), threshold);
  }

  private void writeConfig(StreamConfig config) throws IOException {
    Location configLocation = config.getLocation().append(CONFIG_FILE_NAME);
    Location tmpConfigLocation = configLocation.getTempFile(null);

    CharStreams.write(GSON.toJson(config), CharStreams.newWriterSupplier(
      Locations.newOutputSupplier(tmpConfigLocation), Charsets.UTF_8));

    try {
      // Windows does not allow renaming if the destination file exists so we must delete the configLocation
      if (OSDetector.isWindows()) {
        configLocation.delete();
      }
      tmpConfigLocation.renameTo(getStreamConfigLocation(config.getStreamId()));
    } finally {
      Locations.deleteQuietly(tmpConfigLocation);
    }
  }

  private void mutateStates(long groupId, int instances, Set<StreamConsumerState> states,
                            Set<StreamConsumerState> newStates, Set<StreamConsumerState> removeStates) {
    int oldInstances = states.size();
    if (oldInstances == instances) {
      // If number of instances doesn't changed, no need to mutate any states
      return;
    }

    // Collects smallest offsets across all existing consumers
    // Map from event file location to file offset.
    // Use tree map to maintain ordering consistency in the offsets.
    // Not required by any logic, just easier to look at when logged.
    Map<Location, StreamFileOffset> fileOffsets = Maps.newTreeMap(Locations.LOCATION_COMPARATOR);

    for (StreamConsumerState state : states) {
      for (StreamFileOffset fileOffset : state.getState()) {
        StreamFileOffset smallestOffset = fileOffsets.get(fileOffset.getEventLocation());
        if (smallestOffset == null || fileOffset.getOffset() < smallestOffset.getOffset()) {
          fileOffsets.put(fileOffset.getEventLocation(), new StreamFileOffset(fileOffset));
        }
      }
    }

    // Constructs smallest offsets
    Collection<StreamFileOffset> smallestOffsets = fileOffsets.values();

    // When group size changed, reset all existing instances states to have smallest files offsets constructed above.
    for (StreamConsumerState state : states) {
      if (state.getInstanceId() < instances) {
        // Only keep valid instances
        newStates.add(new StreamConsumerState(groupId, state.getInstanceId(), smallestOffsets));
      } else {
        removeStates.add(state);
      }
    }

    // For all new instances, set files offsets to smallest one constructed above.
    for (int i = oldInstances; i < instances; i++) {
      newStates.add(new StreamConsumerState(groupId, i, smallestOffsets));
    }
  }

  private void alterExploreStream(Id.Stream stream, boolean enable) {
    if (cConf.getBoolean(Constants.Explore.EXPLORE_ENABLED)) {
      // It shouldn't happen.
      Preconditions.checkNotNull(exploreFacade, "Explore enabled but no ExploreFacade instance is available");
      try {
        if (enable) {
          exploreFacade.enableExploreStream(stream);
        } else {
          exploreFacade.disableExploreStream(stream);
        }
      } catch (Exception e) {
        // at this time we want to still allow using stream even if it cannot be used for exploration
        String msg = String.format("Cannot enable exploration of stream %s: %s", stream, e.getMessage());
        LOG.error(msg, e);
      }
    }
  }
}
