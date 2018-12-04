/*
 * Scalyr client library
 * Copyright 2012 Scalyr, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scalyr.api.tests;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;

import com.scalyr.api.knobs.Knob;
import com.scalyr.api.knobs.ConfigurationFile;

/**
 * Sample code.
 */
public class Samples extends KnobTestBase {
  public static ConfigurationFile sampleParams;


  private File configDir;

  @Before @Override public void setup() {
    super.setup();

    configDir = TestUtils.createTemporaryDirectory();
  }

  @Override @After public void teardown() {
    TestUtils.recursiveDelete(configDir);

    super.teardown();
  }


  ///////// WHITELIST SAMPLE /////////

  // Comma-delimited list of internal IP addresses.
  private static final Knob.String internalIps =
      new Knob.String("internalIps", "", sampleParams);

  private static Set<String> internalIpSet = null;

  // Return true if candidateIp is in the internalIps list.
  public static synchronized boolean isInternalIp(String candidateIp) {
    if (internalIpSet == null) {
      internalIpSet = new HashSet<>();
      internalIps.addUpdateListener(value -> rebuildStringSet(internalIpSet, internalIps.get()));
      rebuildStringSet(internalIpSet, internalIps.get());
    }

    return internalIpSet.contains(candidateIp);
  }

  // Parse commaList at comma boundaries, and overwrite the
  // set with the resulting strings.
  private static synchronized void rebuildStringSet(Set<String> set,
      String commaList) {
    set.clear();
    for (String value : commaList.split(","))
      set.add(value);
  }




  ///////// THREADPOOL SAMPLE /////////

  private static final Knob.Integer threadpoolSize =
      new Knob.Integer("threadpoolSize", 1, sampleParams);

  private static ThreadPoolExecutor threadpool = null;

  public static synchronized Executor getThreadpool() {
    if (threadpool == null) {
      threadpoolSize.addUpdateListener(value -> {
        int threadCount = threadpoolSize.get();
        threadpool.setCorePoolSize(threadCount);
        threadpool.setMaximumPoolSize(threadCount);
      });

      int threadCount = threadpoolSize.get();
      threadpool = new ThreadPoolExecutor(threadCount, threadCount,
          1, TimeUnit.MINUTES,
          new LinkedBlockingQueue<Runnable>());
    }

    return threadpool;
  }
}
