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

package co.cask.cdap.app.guice;

import co.cask.cdap.common.runtime.RuntimeModule;
import co.cask.cdap.gateway.handlers.NamespaceHttpHandler;
import co.cask.cdap.internal.app.services.NamespaceHttpService;
import co.cask.http.HttpHandler;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

/**
 * Namespace Runtime Module
 */
public class NamespaceRuntimeModule extends PrivateModule {
//public class NamespaceRuntimeModule extends RuntimeModule {

 /* @Override
  public Module getInMemoryModules() {
    return new InMemoryNamespaceModule();
  }

  @Override
  public Module getStandaloneModules() {
    return new InMemoryNamespaceModule();
  }

  @Override
  public Module getDistributedModules() {
    return new DistributedNamespaceModule();
  }

  private static final class InMemoryNamespaceModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(HttpHandler.class).annotatedWith(Names.named("namespace")).to(NamespaceHttpHandler.class);
    }
  }

  private static final class DistributedNamespaceModule extends AbstractModule {

    @Override
    protected void configure() {
      bind(HttpHandler.class).annotatedWith(Names.named("namespace")).to(NamespaceHttpHandler.class);
    }
  }*/

  @Override
  protected void configure() {
    Multibinder<HttpHandler> handlerBinder = Multibinder.newSetBinder(binder(), HttpHandler.class,
                                                                      Names.named("namespaces"));
    handlerBinder.addBinding().to(NamespaceHttpHandler.class);

    bind(NamespaceHttpService.class).in(Scopes.SINGLETON);
    expose(NamespaceHttpService.class);
  }
}
