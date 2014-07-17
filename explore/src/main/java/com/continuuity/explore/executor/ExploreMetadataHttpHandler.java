/*
 * Copyright 2014 Continuuity, Inc.
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

package com.continuuity.explore.executor;

import com.continuuity.common.conf.Constants;
import com.continuuity.explore.client.ExploreClientUtil;
import com.continuuity.explore.service.ExploreException;
import com.continuuity.explore.service.ExploreService;
import com.continuuity.explore.service.Handle;
import com.continuuity.explore.service.MetaDataInfo;
import com.continuuity.http.AbstractHttpHandler;
import com.continuuity.http.HttpResponder;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.SQLException;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * Handler that implements explore metadata APIs.
 */
@Path(Constants.Gateway.GATEWAY_VERSION)
public class ExploreMetadataHttpHandler extends AbstractHttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ExploreMetadataHttpHandler.class);

  private static final Gson GSON = new Gson();

  private static final String PATH = "data/metadata/";

  private final ExploreService exploreService;

  @Inject
  public ExploreMetadataHttpHandler(ExploreService exploreService) {
    this.exploreService = exploreService;
  }

  @POST
  @Path(PATH + "tables")
  public void getTables(HttpRequest request, HttpResponder responder) {
    // document that we need to pass a json. Returns a handle
    // By default asks for everything

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    handleResponseEndpointExecution(request, responder, new EndpointCoreExecution<Handle>() {
      @Override
      public Handle execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        ExploreClientUtil.TablesArgs args = decodeArguments(request, ExploreClientUtil.TablesArgs.class,
                                                            new ExploreClientUtil.TablesArgs(null, null, "%", null));
        LOG.trace("Received get tables with params: {}", args.toString());
        return exploreService.getTables(args.getCatalog(), args.getSchemaPattern(),
                                        args.getTableNamePattern(), args.getTableTypes());
      }
    });
  }

  @POST
  @Path(PATH + "columns")
  public void getColumns(HttpRequest request, HttpResponder responder) {
    // document that we need to pass a json. Returns a handle
    // By default asks for everything

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    handleResponseEndpointExecution(request, responder, new EndpointCoreExecution<Handle>() {
      @Override
      public Handle execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        ExploreClientUtil.ColumnsArgs args = decodeArguments(request, ExploreClientUtil.ColumnsArgs.class,
                                                             new ExploreClientUtil.ColumnsArgs(null, null, "%", "%"));
        LOG.trace("Received get columns with params: {}", args.toString());
        return exploreService.getColumns(args.getCatalog(), args.getSchemaPattern(),
                                         args.getTableNamePattern(), args.getColumnNamePattern());
      }
    });
  }

  @POST
  @Path(PATH + "catalogs")
  public void getCatalogs(HttpRequest request, HttpResponder responder) {
    // document that we need to pass a json. Returns a handle

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    handleResponseEndpointExecution(request, responder, new EndpointCoreExecution<Handle>() {
      @Override
      public Handle execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        LOG.trace("Received get catalogs query.");
        return exploreService.getCatalogs();
      }
    });
  }

  @POST
  @Path(PATH + "schemas")
  public void getSchemas(HttpRequest request, HttpResponder responder) {
    // document that we need to pass a json. Returns a handle
    // By default asks for everything

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    handleResponseEndpointExecution(request, responder, new EndpointCoreExecution<Handle>() {
      @Override
      public Handle execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        ExploreClientUtil.SchemaArgs args = decodeArguments(request, ExploreClientUtil.SchemaArgs.class,
                                                            new ExploreClientUtil.SchemaArgs(null, null));
        LOG.trace("Received get schemas with params: {}", args.toString());
        return exploreService.getSchemas(args.getCatalog(), args.getSchemaPattern());
      }
    });
  }

  @POST
  @Path(PATH + "functions")
  public void getFunctions(HttpRequest request, HttpResponder responder) {
    // document that we need to pass a json. Returns a handle
    // By default asks for everything

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    handleResponseEndpointExecution(request, responder, new EndpointCoreExecution<Handle>() {
      @Override
      public Handle execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        ExploreClientUtil.FunctionsArgs args = decodeArguments(request, ExploreClientUtil.FunctionsArgs.class,
                                                               new ExploreClientUtil.FunctionsArgs(null, null, "%"));
        LOG.trace("Received get functions with params: {}", args.toString());
        return exploreService.getFunctions(args.getCatalog(), args.getSchemaPattern(),
                                           args.getFunctionNamePattern());
      }
    });
  }

  @POST
  @Path(PATH + "tableTypes")
  public void getTableTypes(HttpRequest request, HttpResponder responder) {
    // document that we need to pass a json. Returns a handle

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    handleResponseEndpointExecution(request, responder, new EndpointCoreExecution<Handle>() {
      @Override
      public Handle execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        LOG.trace("Received get table types query.");
        return exploreService.getTableTypes();
      }
    });
  }

  @POST
  @Path(PATH + "types")
  public void getTypes(HttpRequest request, HttpResponder responder) {
    // document that we need to pass a json. Returns a handle

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    handleResponseEndpointExecution(request, responder, new EndpointCoreExecution<Handle>() {
      @Override
      public Handle execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        LOG.trace("Received get type info query.");
        return exploreService.getTypeInfo();
      }
    });
  }

  @GET
  @Path(PATH + "info/{type}")
  public void getInfo(HttpRequest request, HttpResponder responder, @PathParam("type") final String type) {
    // document that we need to pass a json. Returns a handle

    // NOTE: this call is a POST because we need to pass json, and it actually
    // executes a query.
    genericEndpointExecution(request, responder, new EndpointCoreExecution<Void>() {
      @Override
      public Void execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        LOG.trace("Received get info for {}", type);
        MetaDataInfo.InfoType infoType = MetaDataInfo.InfoType.fromString(type);
        MetaDataInfo metadataInfo = exploreService.getInfo(infoType);
        responder.sendJson(HttpResponseStatus.OK, metadataInfo);
        return null;
      }
    });
  }

  private void handleResponseEndpointExecution(HttpRequest request, HttpResponder responder,
                                               final EndpointCoreExecution<Handle> execution) {
    genericEndpointExecution(request, responder, new EndpointCoreExecution<Void>() {
      @Override
      public Void execute(HttpRequest request, HttpResponder responder)
        throws IllegalArgumentException, SQLException, ExploreException, IOException {
        Handle handle = execution.execute(request, responder);
        JsonObject json = new JsonObject();
        json.addProperty("handle", handle.getHandle());
        responder.sendJson(HttpResponseStatus.OK, json);
        return null;
      }
    });
  }

  private void genericEndpointExecution(HttpRequest request, HttpResponder responder,
                                        EndpointCoreExecution<Void> execution) {
    try {
      execution.execute(request, responder);
    } catch (IllegalArgumentException e) {
      LOG.debug("Got exception:", e);
      responder.sendError(HttpResponseStatus.BAD_REQUEST, e.getMessage());
    } catch (SQLException e) {
      LOG.debug("Got exception:", e);
      responder.sendError(HttpResponseStatus.BAD_REQUEST,
                          String.format("[SQLState %s] %s", e.getSQLState(), e.getMessage()));
    } catch (Throwable e) {
      LOG.error("Got exception:", e);
      responder.sendStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private <T> T decodeArguments(HttpRequest request, Class<T> argsType, T defaultValue) throws IOException {
    ChannelBuffer content = request.getContent();
    if (!content.readable()) {
      return defaultValue;
    }
    Reader reader = new InputStreamReader(new ChannelBufferInputStream(content), Charsets.UTF_8);
    try {
      T args = GSON.fromJson(reader, argsType);
      return (args == null) ? defaultValue : args;
    } catch (JsonSyntaxException e) {
      LOG.info("Failed to parse runtime arguments on {}", request.getUri(), e);
      throw e;
    } finally {
      reader.close();
    }
  }

  /**
   * Represents the core execution of an endpoint.
   */
  private static interface EndpointCoreExecution<T> {
    T execute(HttpRequest request, HttpResponder responder)
      throws IllegalArgumentException, SQLException, ExploreException, IOException;
  }
}
