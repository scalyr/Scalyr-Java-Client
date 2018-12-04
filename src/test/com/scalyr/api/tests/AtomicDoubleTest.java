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
import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

import com.scalyr.api.logs.AtomicDouble;

/**
 * Tests for AtomicDouble.
 */
public class AtomicDoubleTest {
  /**
   * Simple tests of each method.
   */
  @Test public void basicTests() {
    AtomicDouble x = new AtomicDouble();
    AtomicDouble y = new AtomicDouble(12.5);

    assertEquals(0, x.get(), 1E-10);
    assertEquals(12.5, y.get(), 1E-10);

    x.add(2);
    y.add(3.25);

    assertEquals(2, x.get(), 1E-10);
    assertEquals(15.75, y.get(), 1E-10);

    x.set(-111.25);
    assertEquals(-111.25, x.get(), 1E-10);
  }

  /**
   * Test of contended incrementing.
   */
  @Test public void multiThreadedTest() throws InterruptedException {
    AtomicDouble d = new AtomicDouble(0);
    ThreadManager threadManager = new ThreadManager();
    IncrementerThread[] threads = new IncrementerThread[4];
    for (int i = 0; i < threads.length; i++) {
      threads[i] = new IncrementerThread(d, threadManager);
      threads[i].start();
    }

    threadManager.startLatch.countDown();
    Thread.sleep(2000);

    threadManager.stopThreads = true;
    double expectedTotal = 0;
    for (IncrementerThread thread : threads) {
      thread.join();
      assertTrue(thread.success);
      expectedTotal += thread.total;
    }

    assertEquals(expectedTotal, d.get(), expectedTotal * 1E-9);
  }

  private static class ThreadManager {
    /**
     * Latch used to launch all threads simultaneously.
     */
    public final CountDownLatch startLatch = new CountDownLatch(1);

    /**
     * True when the test is ready to finish. Tells the IncrementerThreads to terminate.
     */
    public volatile boolean stopThreads;
  }

  private static class IncrementerThread extends Thread {
    /**
     * The variable we increment.
     */
    private final AtomicDouble d;

    private final ThreadManager threadManager;

    /**
     * Total delta applied by this thread.
     */
    public double total = 0;

    /**
     * True if we have successfully reached the end of the thread.
     */
    public boolean success = false;

    public IncrementerThread(AtomicDouble d, ThreadManager threadManager) {
      this.d = d;
      this.threadManager = threadManager;
    }

    @Override public void run() {
      Random r = new Random();

      try {
        threadManager.startLatch.await();
      } catch (InterruptedException ex) {
        throw new RuntimeException(ex);
      }

      while (!threadManager.stopThreads) {
        double value = r.nextDouble();
        total += value;
        d.add(value);
      }

      success = true;
    }
  }
}
