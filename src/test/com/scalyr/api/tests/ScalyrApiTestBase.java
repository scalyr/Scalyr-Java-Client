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

import static org.junit.Assert.assertEquals;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Before;

import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.internal.Sleeper;
import com.scalyr.api.tests.MockServer.ExpectedRequest;

public class ScalyrApiTestBase {
  /**
   * Some of these tests involve multiple racing threads of execution. For now, we're relying on
   * simple clock delays (e.g. Thread.sleep) to line up the execution. Here are notes on how to
   * remove the time dependency and make the tests more robust -- it's a lot of work, so I'm not
   * doing it at this point.
   *
   * - Mock out the system clock. Review all callers to System, Thread.sleep in the codebase,
   *   have them use the mocked clock instead.
   * - Review all uses of wait() with a timeout, replace them with the mocked clock. (This seems
   *   tricky.)
   * - Mocked Thread.sleep needs to block until all other threads run as far as they ought. Could
   *   use semaphores for this.
   */

  protected Executor asyncExecutor;

  public MockServer mockServer;

  private Sleeper savedSleeper;

  @Before public void setup() {
    mockServer = new MockServer();

    asyncExecutor = Executors.newCachedThreadPool();

    savedSleeper = Sleeper.instance;
  }

  @After public void teardown() {
    Sleeper.instance = savedSleeper;
    ScalyrUtil.removeCustomTime();
  }

  protected void expectRequest(String expectedMethodName, String expectedParameters, String response) {
    mockServer.expectedRequests.add(new ExpectedRequest(expectedMethodName, expectedParameters, response));
  }

  protected void expectRequestAfterDelay(final int delayMs, final String expectedMethodName,
      final String expectedParameters, final String response) {
    asyncExecutor.execute(new Runnable(){
      @Override public void run() {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
          ex.printStackTrace();
        }
        expectRequest(expectedMethodName, expectedParameters, response);
      }});
  }

  /**
   * Verify that all expected requests have been issued / consumed.
   */
  protected void assertRequestQueueEmpty() {
    assertEquals(0, mockServer.expectedRequests.size());
  }
}
