package com.continuuity.gateway.router;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.common.http.core.AbstractHttpHandler;
import com.continuuity.common.http.core.HttpResponder;
import com.continuuity.common.http.core.NettyHttpService;
import com.continuuity.common.utils.Networks;
import com.continuuity.weave.api.WeaveRunner;
import com.continuuity.weave.common.Cancellable;
import com.continuuity.weave.discovery.Discoverable;
import com.continuuity.weave.discovery.DiscoveryService;
import com.continuuity.weave.discovery.DiscoveryServiceClient;
import com.continuuity.weave.discovery.InMemoryDiscoveryService;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.net.InetAddresses;
import com.google.inject.Provider;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests Netty Router.
 */
public class NettyRouterTest {
  private static final Logger LOG = LoggerFactory.getLogger(NettyRouterTest.class);
  private static final String hostname = "127.0.0.1";
  private static final DiscoveryService discoveryService = new InMemoryDiscoveryService();
  private static final String service1 = "test.service1";
  private static final String service2 = "$HOST";

  private static final Supplier<String> service1Supplier = new Supplier<String>() {
    @Override
    public String get() {
      return service1;
    }
  };

  private static final Supplier<String> service2Supplier = new Supplier<String>() {
    @Override
    public String get() {
      try {
        return Networks.normalizeHost(hostname + ":" + router.getServiceMap().get(service2));
      } catch (UnsupportedEncodingException e) {
        LOG.error("Got exception: ", e);
        throw Throwables.propagate(e);
      }
    }
  };

  public static final RouterResource router = new RouterResource(hostname, discoveryService,
                                                                 ImmutableSet.of("0:" + service1, "0:" + service2));

  public static final ServerResource server1 = new ServerResource(hostname, discoveryService, service1Supplier);
  public static final ServerResource server2 = new ServerResource(hostname, discoveryService, service1Supplier);
  public static final ServerResource server3 = new ServerResource(hostname, discoveryService, service2Supplier);

  @SuppressWarnings("UnusedDeclaration")
  @ClassRule
  public static TestRule chain = RuleChain.outerRule(router).around(server1).around(server2).around(server3);

  @Before
  public void clearNumRequests() throws Exception {
    server1.clearNumRequests();
    server2.clearNumRequests();
    server3.clearNumRequests();

    // Wait for both servers of service1 to be registered
    Iterable<Discoverable> discoverables = ((DiscoveryServiceClient) discoveryService).discover(service1Supplier.get());
    for (int i = 0; i < 50 && Iterables.size(discoverables) != 2; ++i) {
      TimeUnit.MILLISECONDS.sleep(50);
    }

    // Wait for server of service2 to be registered
    discoverables = ((DiscoveryServiceClient) discoveryService).discover(service2Supplier.get());
    for (int i = 0; i < 50 && Iterables.size(discoverables) != 1; ++i) {
      TimeUnit.MILLISECONDS.sleep(50);
    }
  }

  @Test
  public void testRouterSync() throws Exception {
    testSync();
    Assert.assertTrue(server1.getNumRequests() > 0);
    Assert.assertTrue(server2.getNumRequests() > 0);
  }

  @Test
  public void testRouterAsync() throws Exception {
    int NUM_ELEMENTS = 123;
    AsyncHttpClientConfig.Builder configBuilder = new AsyncHttpClientConfig.Builder();

    final AsyncHttpClient asyncHttpClient = new AsyncHttpClient(
      new NettyAsyncHttpProvider(configBuilder.build()),
      configBuilder.build());

    final CountDownLatch latch = new CountDownLatch(NUM_ELEMENTS);
    final AtomicInteger numSuccessfulRequests = new AtomicInteger(0);
    for (int i = 0; i < NUM_ELEMENTS; ++i) {
      final int elem = i;
      final Request request = new RequestBuilder("GET")
        .setUrl(String.format("http://%s:%d%s/%s-%d",
                              hostname, router.getServiceMap().get(service1), "/v1/ping", "async", i))
        .build();
      asyncHttpClient.executeRequest(request,
                                     new AsyncCompletionHandler<Void>() {
                                       @Override
                                       public Void onCompleted(Response response) throws Exception {
                                         latch.countDown();
                                         Assert.assertEquals(HttpResponseStatus.OK.getCode(),
                                                             response.getStatusCode());
                                         numSuccessfulRequests.incrementAndGet();
                                         return null;
                                       }

                                       @Override
                                       public void onThrowable(Throwable t) {
                                         LOG.error("Got exception while posting {}", elem, t);
                                         latch.countDown();
                                       }
                                     });

      // Sleep so as not to overrun the server.
      TimeUnit.MILLISECONDS.sleep(1);
    }
    latch.await();
    asyncHttpClient.close();

    Assert.assertEquals(NUM_ELEMENTS, numSuccessfulRequests.get());
    Assert.assertTrue(server1.getNumRequests() > 0);
    Assert.assertTrue(server2.getNumRequests() > 0);
  }

  @Test
  public void testRouterOneServerDown() throws Exception {
    try {
      // Bring down server1
      server1.cancelRegistration();

      testSync();
    } finally {
      Assert.assertEquals(0, server1.getNumRequests());
      Assert.assertTrue(server2.getNumRequests() > 0);

      server1.registerServer();
    }
  }

  @Test(expected = Exception.class)
  public void testRouterAllServersDown() throws Exception {
    try {
      // Bring down all servers
      server1.cancelRegistration();
      server2.cancelRegistration();

      testSync();
    } finally {
      Assert.assertEquals(0, server1.getNumRequests());
      Assert.assertEquals(0, server2.getNumRequests());

      server1.registerServer();
      server2.registerServer();
    }
  }

  @Test
  public void testTwoServices() throws Exception {
    // Test service1
    HttpResponse response = get(String.format("http://%s:%d%s/%s",
                                              hostname, router.getServiceMap().get(service1), "/v1/ping", "sync"));
    Assert.assertEquals(HttpResponseStatus.OK.getCode(), response.getStatusLine().getStatusCode());

    // Test service2
    response = get(String.format("http://%s:%d%s/%s",
                                 hostname, router.getServiceMap().get(service2), "/v1/ping", "sync"));
    Assert.assertEquals(HttpResponseStatus.NO_CONTENT.getCode(), response.getStatusLine().getStatusCode());
  }

  private void testSync() throws Exception {
    for (int i = 0; i < 25; ++i) {
      LOG.trace("Sending request " + i);
      HttpResponse response = get(String.format("http://%s:%d%s/%s-%d",
                                                hostname, router.getServiceMap().get(service1), "/v1/ping", "sync", i));
      Assert.assertEquals(HttpResponseStatus.OK.getCode(), response.getStatusLine().getStatusCode());
    }
  }

  private HttpResponse get(String url) throws Exception {
    DefaultHttpClient client = new DefaultHttpClient();
    HttpGet get = new HttpGet(url);
    return client.execute(get);
  }

  private static class RouterResource extends ExternalResource {
    private final String hostname;
    private final DiscoveryService discoveryService;
    private final Set<String> forwards;
    private final Map<String, Integer> serviceMap = Maps.newHashMap();

    private NettyRouter router;

    private RouterResource(String hostname, DiscoveryService discoveryService, Set<String> forwards) {
      this.hostname = hostname;
      this.discoveryService = discoveryService;
      this.forwards = forwards;
    }

    @Override
    protected void before() throws Throwable {
      CConfiguration cConf = CConfiguration.create();
      cConf.set(Constants.Router.ADDRESS, hostname);
      cConf.setStrings(Constants.Router.FORWARD, forwards.toArray(new String[forwards.size()]));
      router = new NettyRouter(cConf, InetAddresses.forString(hostname),
                               new RouterServiceLookup((DiscoveryServiceClient) discoveryService,
                                                       new Provider<Iterable<WeaveRunner.LiveInfo>>() {
                                                         @Override
                                                         public Iterable<WeaveRunner.LiveInfo> get() {
                                                           return ImmutableSet.of();
                                                         }
                                                       }
                               ));
      router.startAndWait();

      for (Map.Entry<Integer, String> entry : router.getServiceLookup().getServiceMap().entrySet()) {
        serviceMap.put(entry.getValue(), entry.getKey());
      }
    }

    @Override
    protected void after() {
      router.stopAndWait();
    }

    public Map<String, Integer> getServiceMap() {
      return serviceMap;
    }
  }

  /**
   * A generic server for testing router.
   */
  public static class ServerResource extends ExternalResource {
    private static final Logger LOG = LoggerFactory.getLogger(ServerResource.class);

    private final String hostname;
    private final DiscoveryService discoveryService;
    private final Supplier<String> serviceNameSupplier;
    private final AtomicInteger numRequests = new AtomicInteger(0);

    private NettyHttpService httpService;
    private Cancellable cancelDiscovery;

    private ServerResource(String hostname, DiscoveryService discoveryService, Supplier<String> serviceNameSupplier) {
      this.hostname = hostname;
      this.discoveryService = discoveryService;
      this.serviceNameSupplier = serviceNameSupplier;
    }

    @Override
    protected void before() throws Throwable {
      NettyHttpService.Builder builder = NettyHttpService.builder();
      builder.addHttpHandlers(ImmutableSet.of(new ServerHandler()));
      builder.setHost(hostname);
      builder.setPort(0);
      httpService = builder.build();
      httpService.startAndWait();

      registerServer();

      LOG.info("Started test server on {}", httpService.getBindAddress());
    }

    @Override
    protected void after() {
      httpService.stopAndWait();
    }

    public int getNumRequests() {
      return numRequests.get();
    }

    public void clearNumRequests() {
      numRequests.set(0);
    }

    public void registerServer() {
      // Register services of test server
      LOG.info("Registering service {}", serviceNameSupplier.get());
      cancelDiscovery = discoveryService.register(new Discoverable() {
        @Override
        public String getName() {
          return serviceNameSupplier.get();
        }

        @Override
        public InetSocketAddress getSocketAddress() {
          return httpService.getBindAddress();
        }
      });
    }

    public void cancelRegistration() {
      cancelDiscovery.cancel();
    }

    /**
     * Simple handler for server.
     */
    public class ServerHandler extends AbstractHttpHandler {
      private final Logger LOG = LoggerFactory.getLogger(ServerHandler.class);
      @GET
      @Path("/v1/ping/{text}")
      public void ping(@SuppressWarnings("UnusedParameters") HttpRequest request, final HttpResponder responder,
                       @PathParam("text") String text) {
        numRequests.incrementAndGet();
        LOG.trace("Got text {}", text);

        if (serviceNameSupplier.get().equals(service1)) {
          responder.sendStatus(HttpResponseStatus.OK);
        } else {
          responder.sendStatus(HttpResponseStatus.NO_CONTENT);
        }
      }
    }
  }
}
