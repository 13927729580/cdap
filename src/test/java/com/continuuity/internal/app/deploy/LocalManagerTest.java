/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */

package com.continuuity.internal.app.deploy;

import com.continuuity.TestHelper;
import com.continuuity.ToyApp;
import com.continuuity.WebCrawlApp;
import com.continuuity.app.DefaultId;
import com.continuuity.app.program.Type;
import com.continuuity.archive.JarFinder;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.continuuity.internal.app.deploy.pipeline.ApplicationWithPrograms;
import com.continuuity.weave.filesystem.LocalLocationFactory;
import com.continuuity.weave.filesystem.Location;
import com.continuuity.weave.filesystem.LocationFactory;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.jar.Manifest;

/**
 * Tests the functionality of Deploy Manager.
 */
public class LocalManagerTest {
  private static LocationFactory lf;
  private static CConfiguration configuration;

  @BeforeClass
  public static void before() throws Exception {
    lf = new LocalLocationFactory();
    configuration = CConfiguration.create();
    configuration.set(Constants.CFG_APP_FABRIC_TEMP_DIR, System.getProperty("java.io.tmpdir"));
    configuration.set(Constants.CFG_APP_FABRIC_OUTPUT_DIR, System.getProperty("java.io.tmpdir")
                                                                      + "/" + UUID.randomUUID());
  }

  /**
   * Improper Manifest file should throw an exception.
   */
  @Test(expected = ExecutionException.class)
  public void testImproperOrNoManifestFile() throws Exception {
    String jar = JarFinder.getJar(WebCrawlApp.class, new Manifest());
    Location deployedJar = lf.create(jar);
    deployedJar.deleteOnExit();
    TestHelper.getLocalManager(configuration).deploy(DefaultId.ACCOUNT, deployedJar);
  }

  /**
   * Good pipeline with good tests.
   */
  @Test
  public void testGoodPipeline() throws Exception {
    Location deployedJar = lf.create(
      JarFinder.getJar(ToyApp.class, TestHelper.getManifestWithMainClass(ToyApp.class))
    );

    ListenableFuture<?> p = TestHelper.getLocalManager(configuration).deploy(DefaultId.ACCOUNT, deployedJar);
    ApplicationWithPrograms input = (ApplicationWithPrograms)p.get();

    Assert.assertEquals(input.getAppSpecLoc().getArchive(), deployedJar);
    Assert.assertEquals(input.getPrograms().iterator().next().getProcessorType(), Type.FLOW);
    Assert.assertEquals(input.getPrograms().iterator().next().getProgramName(), "ToyFlow");
  }

}
