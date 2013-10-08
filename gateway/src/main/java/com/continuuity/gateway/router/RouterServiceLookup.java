package com.continuuity.gateway.router;

import com.continuuity.app.program.Type;
import com.continuuity.common.discovery.EndpointStrategy;
import com.continuuity.common.discovery.RandomEndpointStrategy;
import com.continuuity.common.utils.Networks;
import com.continuuity.gateway.router.discovery.DiscoveryNameFinder;
import com.continuuity.weave.discovery.Discoverable;
import com.continuuity.weave.discovery.DiscoveryServiceClient;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Port -> service lookup.
 */
public class RouterServiceLookup {
  private static final Logger LOG = LoggerFactory.getLogger(RouterServiceLookup.class);

  private final AtomicReference<Map<Integer, String>> serviceMapRef =
    new AtomicReference<Map<Integer, String>>(ImmutableMap.<Integer, String>of());

  private final LoadingCache<String, EndpointStrategy> discoverableCache;

  @Inject
  public RouterServiceLookup(final DiscoveryServiceClient discoveryServiceClient,
                             final DiscoveryNameFinder discoveryNameFinder) {

    this.discoverableCache = CacheBuilder.newBuilder()
      .expireAfterAccess(1, TimeUnit.HOURS)
      .build(new CacheLoader<String, EndpointStrategy>() {
        @Override
        public EndpointStrategy load(String serviceName) throws Exception {
          serviceName = discoveryNameFinder.findDiscoveryServiceName(Type.WEBAPP, serviceName);
          LOG.debug("Looking up service name {}", serviceName);
          return new RandomEndpointStrategy(discoveryServiceClient.discover(serviceName));
        }
      });
  }

  /**
     * Lookup service name given port.
     *
     * @param port port to lookup.
     * @return service name based on port.
     */
  public String getService(int port) {
    return serviceMapRef.get().get(port);
  }

  /**
     * @return the port to service name map for all services.
     */
  public Map<Integer, String> getServiceMap() {
    return ImmutableMap.copyOf(serviceMapRef.get());
  }

  /**
     * Returns the discoverable mapped to the given port.
     *
     * @param port port to lookup.
     * @param hostHeaderSupplier supplies the Host header for the lookup.
     * @return discoverable based on port and host header.
     */
  public Discoverable getDiscoverable(int port, Supplier<String> hostHeaderSupplier) throws Exception {
    String service = serviceMapRef.get().get(port);
    if (service == null) {
      LOG.debug("No service found for port {}", port);
      return null;
    }

    String host;
    if (service.contains("$HOST")) {
      host = hostHeaderSupplier.get();
      if (host == null) {
        LOG.debug("Cannot find host header for service {} on port {}", service, port);
        return null;
      }
      host = Networks.normalizeHost(host);
      service = service.replace("$HOST", host);
    }

    Discoverable discoverable = discoverableCache.get(service).pick();
    if (discoverable == null) {
      // Another app may have replaced as the server to serve $HOST
      LOG.debug("Refreshing cache for service {}", service);
      discoverableCache.refresh(service);

      // Try again
      discoverable = discoverableCache.get(service).pick();
      if (discoverable == null) {
        LOG.warn("No discoverable endpoints found for service {}", service);
      }
    }

    return discoverable;
  }

  public void updateServiceMap(Map<Integer, String> serviceMap) {
    serviceMapRef.set(serviceMap);
  }
}
