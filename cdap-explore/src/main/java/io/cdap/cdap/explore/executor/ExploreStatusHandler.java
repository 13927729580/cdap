/*
 * Copyright © 2015-2019 Cask Data, Inc.
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

package io.cdap.cdap.explore.executor;

import com.google.gson.JsonObject;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.http.AbstractHttpHandler;
import io.cdap.http.HttpResponder;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

/**
 * Explore status handler - reachable outside of CDAP.
 */
@Path(Constants.Gateway.API_VERSION_3)
public class ExploreStatusHandler extends AbstractHttpHandler {

  @Path("explore/status")
  @GET
  public void status(HttpRequest request, HttpResponder responder) {
    JsonObject json = new JsonObject();
    json.addProperty("status", "OK");
    responder.sendJson(HttpResponseStatus.OK, json.toString());
  }
}
