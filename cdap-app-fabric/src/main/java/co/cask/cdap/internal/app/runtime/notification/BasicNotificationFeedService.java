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

package co.cask.cdap.internal.app.runtime.notification;

import co.cask.cdap.app.store.Store;
import co.cask.cdap.app.store.StoreFactory;
import co.cask.cdap.notifications.NotificationFeed;
import co.cask.cdap.notifications.service.NotificationFeedException;
import co.cask.cdap.notifications.service.NotificationFeedNotFoundException;
import co.cask.cdap.notifications.service.NotificationFeedService;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;

import java.util.List;

/**
 * Implementation of {@link NotificationFeedService} that persists {@link NotificationFeed}s in appfabric
 * {@link Store}.
 */
public class BasicNotificationFeedService extends AbstractIdleService implements NotificationFeedService {

  private final Store store;

  @Inject
  public BasicNotificationFeedService(StoreFactory storeFactory) {
    this.store = storeFactory.create();
  }

  @Override
  protected void startUp() throws Exception {
    // No-op
  }

  @Override
  protected void shutDown() throws Exception {
    // No-op
  }

  @Override
  public boolean createFeed(NotificationFeed feed) throws NotificationFeedException {
    if (feed.getNamespace() == null || feed.getNamespace().isEmpty()) {
      throw new NotificationFeedException("Namespace value cannot be null or empty.");
    } else if (feed.getCategory() == null || feed.getCategory().isEmpty()) {
      throw new NotificationFeedException("Category value cannot be null or empty.");
    } else if (feed.getName() == null || feed.getName().isEmpty()) {
      throw new NotificationFeedException("Name value cannot be null or empty.");
    }
    return store.createNotificationFeed(feed) == null;
  }

  @Override
  public void deleteFeed(NotificationFeed feed) throws NotificationFeedException {
    if (store.deleteNotificationFeed(feed.getId()) == null) {
      throw new NotificationFeedNotFoundException("Feed did not exist in metadata store: " + feed);
    }
  }

  @Override
  public NotificationFeed getFeed(NotificationFeed feed) throws NotificationFeedException {
    NotificationFeed f = store.getNotificationFeed(feed.getId());
    if (f == null) {
      throw new NotificationFeedNotFoundException("Feed did not exist in metadata store: " + feed);
    }
    return f;
  }

  @Override
  public List<NotificationFeed> listFeeds() throws NotificationFeedException {
    return store.listNotificationFeeds();
  }

}
