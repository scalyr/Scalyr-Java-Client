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

import com.scalyr.api.ScalyrDeadlineException;
import com.scalyr.api.internal.Sleeper;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.knobs.BadConfigurationFileException;
import com.scalyr.api.knobs.ConfigurationFile;
import com.scalyr.api.knobs.ConfigurationFile.FileState;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * Tests for ParameterFile and HostedParameterFile.
 */
public class ConfigurationFileTest extends KnobTestBase {
  private File persistentCacheDir;

  /**
   * All files created in this test.
   */
  private List<ConfigurationFile> files = new ArrayList<ConfigurationFile>();

  @After public void teardownConfigurationFileTest() {
    for (ConfigurationFile file : files)
      file.close();

    if (persistentCacheDir != null)
      TestUtils.recursiveDelete(persistentCacheDir);
  }

  /**
   * Test simple operations on a file that is fetched once and not updated.
   */
  @Test public void simpleTest() {
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': 3, \\'bar\\': \\'xyz\\'}'}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);
    assertEquals("/foo.txt", paramFile.getPathname());

    FileState fileState = paramFile.get();

    assertEquals("{'foo': 3, 'bar': 'xyz'}".replace('\'', '"'), fileState.content);
    assertEquals(new Date(1000), fileState.creationDate);
    assertEquals(new Date(2000), fileState.modificationDate);

    JSONObject json = paramFile.getAsJsonForTesting();
    assertEquals("xyz", json.get("bar"));

    assertRequestQueueEmpty();

    paramFile.close();
  }

  /**
   * Test attempting to retrieve a non-existent file.
   */
  @Test public void missingFileTest() {
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success/noSuchFile', 'message': 'foo'}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);
    FileState fileState = paramFile.get();

    assertNull(fileState.content);
    assertNull(fileState.creationDate);
    assertNull(fileState.modificationDate);

    assertNull(paramFile.getAsJsonForTesting());

    assertRequestQueueEmpty();

    paramFile.close();
  }

  /**
   * Test fetching a file that does not become available until after it is first requested.
   */
  @Test public void testBlockingRequest() {
    // Spawn a thread for the server to supply the file after a 1-second delay. This
    // gives the client time to request the file before it becomes available.
    expectRequestAfterDelay(1000,
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': 3, \\'bar\\': \\'xyz\\'}'}");

    // Request the file.
    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);
    assertFalse(paramFile.hasState());
    FileState fileState = paramFile.get();
    assertTrue(paramFile.hasState());

    // Verify the file's state.
    assertEquals("{'foo': 3, 'bar': 'xyz'}".replace('\'', '"'), fileState.content);
    assertEquals(new Date(1000), fileState.creationDate);
    assertEquals(new Date(2000), fileState.modificationDate);

    assertRequestQueueEmpty();

    paramFile.close();
  }

  /**
   * Test fetching a file that does not become available until after it is first requested.
   * We request with a timeout, which is not exceeded.
   */
  @Test public void testBoundedBlockingRequest() {
    // Spawn a thread for the server to supply the file after a 1-second delay. This
    // gives the client time to request the file before it becomes available.
    expectRequestAfterDelay(1000,
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': 3, \\'bar\\': \\'xyz\\'}'}");

    // Request the file.
    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);
    assertFalse(paramFile.hasState());
    FileState fileState = paramFile.getWithTimeout(5000L, 5000L);
    assertTrue(paramFile.hasState());

    // Verify the file's state.
    assertEquals("{'foo': 3, 'bar': 'xyz'}".replace('\'', '"'), fileState.content);
    assertEquals(new Date(1000), fileState.creationDate);
    assertEquals(new Date(2000), fileState.modificationDate);

    assertRequestQueueEmpty();
  }

  /**
   * Test timing out while waiting to fetching a file.
   */
  @Test public void testTimeout() {
    // Spawn a thread for the server to supply the file after a 1-second delay. This
    // gives the client time to request the file before it becomes available.
    expectRequestAfterDelay(1000,
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': 3, \\'bar\\': \\'xyz\\'}'}");

    // Request the file.
    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);
    assertFalse(paramFile.hasState());
    try {
      paramFile.getWithTimeout(100L, 100L);
      fail("Operation should have timed out");
    } catch (ScalyrDeadlineException ex) {
      // This is good.
    }
    assertFalse(paramFile.hasState());

    assertRequestQueueEmpty();
  }

  /**
   * Test concurrent requests for a file that is not yet available.
   */
  @Test public void testConcurrentBlockingRequests() throws InterruptedException {
    // Spawn a thread for the server to supply the file after a 1-second delay. This
    // gives the client time to request the file before it becomes available.
    expectRequestAfterDelay(1000,
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': 3, \\'bar\\': \\'xyz\\'}'}");

    final ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);
    final Semaphore semaphore = new Semaphore(0);

    // Spawn a thread to request the file with a long timeout.
    asyncExecutor.execute(new Runnable(){
      @Override public void run() {
        // Request the file.
        assertFalse(paramFile.hasState());
        FileState fileState = paramFile.getWithTimeout(5000L, 5000L);
        assertTrue(paramFile.hasState());

        // Verify the file's state.
        assertEquals("{'foo': 3, 'bar': 'xyz'}".replace('\'', '"'), fileState.content);
        assertEquals(new Date(1000), fileState.creationDate);
        assertEquals(new Date(2000), fileState.modificationDate);

        semaphore.release();
      }});

    // In parallel, request the file with a short timeout, and verify that we don't get it.
    try {
      paramFile.getWithTimeout(100L, 100L);
      fail("Operation should have timed out");
    } catch (ScalyrDeadlineException ex) {
      // This is good.
    }

    semaphore.acquire();

    assertRequestQueueEmpty();
  }

  /**
   * Test invalid responses from the server.
   */
  @Test public void testInvalidResponses() {
    // Queue up an invalid response, an exception throw, and finally a valid response.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'error', 'message': 'darn it!'}");

    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        null /*error*/);

    expectRequest("getFile",
                  "{'token': 'dummyToken', 'path': '/foo.txt'}",
                  "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
                      "'content': '{\\'foo\\': 3, \\'bar\\': \\'xyz\\'}'}");

    ConfigurationFile file = factory.getFile("/foo.txt");
    files.add(file);
    assertEquals("{'foo': 3, 'bar': 'xyz'}".replace('\'', '"'), file.get().content);

    assertRequestQueueEmpty();
  }

  /**
   * Test a parameter file that is not in valid JSON format.
   */
  @Test public void testBadJson() {
    expectRequest(
                     "getFile",
                     "{'token': 'dummyToken', 'path': '/foo.txt'}",
                     "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
                         "'content': '{\\'foo\\': 3'}"); // note, content is missing the terminating brace

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);

    try {
      paramFile.getAsJsonForTesting();
      fail("Operation should have failed");
    } catch (BadConfigurationFileException ex) {
      // This is good.
    }

    // Try again, verify that cached exceptions work properly.
    try {
      paramFile.getAsJsonForTesting();
      fail("Operation should have failed");
    } catch (BadConfigurationFileException ex) {
      // This is good.
    }

    assertRequestQueueEmpty();
  }

  /**
   * Test a parameter file that is in JSON format, but is not an object.
   */
  @Test public void testBadJson2() {
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '123'}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);

    try {
      paramFile.getAsJsonForTesting();
      fail("Operation should have failed");
    } catch (BadConfigurationFileException ex) {
      // This is good.
    }

    assertRequestQueueEmpty();
  }

  /**
   * Test an empty parameter file -- should be treated as an empty JSON object.
   */
  @Test public void testEmptyFile() {
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': ''}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);
    assertEquals("", paramFile.get().content);
    TestUtils.assertEquivalent(new JSONObject(), paramFile.getAsJsonForTesting());

    assertRequestQueueEmpty();
  }

  /**
   * Test updates to a file.
   */
  @Test public void testUpdates() throws InterruptedException {
    // Publish, and verify, an initial version of the file.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': 3, \\'bar\\': \\'xyz\\'}'}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    files.add(paramFile);

    assertEquals(new Date(2000), paramFile.get().modificationDate);
    assertEquals("xyz", paramFile.getAsJsonForTesting().get("bar"));

    TestListener listener = new TestListener();
    paramFile.addUpdateListener(listener);
    assertNull(listener.value);

    // Publish a second version, pause to ensure it's picked up, and then verify it.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt', 'expectedVersion': 1}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 2, 'createDate': 1000, 'modDate': 3000," +
            "'content': '{\\'bar\\': \\'xyzw\\'}'}");

    Thread.sleep(1000); // must sleep for longer than HostedParameterFile's minimum inter-request delay

    assertEquals(new Date(3000), paramFile.get().modificationDate);
    assertEquals("xyzw", paramFile.getAsJsonForTesting().get("bar"));

    assertEquals(paramFile, listener.value);

    assertRequestQueueEmpty();
  }

  /**
   * Sleeper implementation which tracks sleep calls, but does not actually sleep.
   */
  public static class MockSleeper extends Sleeper {
    /**
     * Values passed to our sleep method since the last call to checkSleepIntervals.
     */
    private List<Integer> intervals = new ArrayList<>();

    @Override public synchronized void sleep(int intervalInMs) {
      intervals.add(intervalInMs);
    }

    /**
     * Verify that the calls to sleep() since the last call to checkSleepIntervals,
     * had the specified intervals.
     */
    public synchronized void checkSleepIntervals(Integer ... expectedIntervals) {
      assertArrayEquals(expectedIntervals, intervals.toArray(new Integer[0]));

      intervals.clear();
    }
  };

  /**
   * Verify that multiple requests for the same file return the same object.
   */
  @Test public void testFileMap() {
    ConfigurationFile paramFile1 = factory.getFile("/foo.txt");
    ConfigurationFile paramFile2 = factory.getFile("/foo.txt");
    ConfigurationFile paramFile3 = factory.getFile("/baz.txt");
    ConfigurationFile paramFile4 = new MockKnobsServer().createFactory(null).getFile("/foo.txt");

    files.add(paramFile1);
    files.add(paramFile2);
    files.add(paramFile3);

    assertTrue (paramFile1 == paramFile2);
    assertFalse(paramFile1 == paramFile3);
    assertFalse(paramFile1 == paramFile4);

    paramFile1.close();
    paramFile2.close();
    paramFile3.close();
    paramFile4.close();
  }

  @Test public void testPersistentCache() throws InterruptedException {
    // Set up a test directory.
    persistentCacheDir = TestUtils.createTemporaryDirectory();
    factory = server.createFactory(persistentCacheDir);

    // Publish, and fetch, initial versions of two files -- one that exists, one that doesn't.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'x\\': 3, \\'y\\': \\'xyz\\'}'}");
    ConfigurationFile fileFoo = factory.getFile("/foo.txt");
    files.add(fileFoo);

    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/bar.txt'}",
        "{'status': 'success/noSuchFile', 'message': 'whatever'}");
    ConfigurationFile fileBar = factory.getFile("/bar.txt");
    files.add(fileBar);

    assertEquals(new Date(1000), fileFoo.get().creationDate);
    assertEquals(new Date(2000), fileFoo.get().modificationDate);
    assertEquals("xyz", fileFoo.getAsJsonForTesting().get("y"));

    assertEquals(null, fileBar.get().creationDate);
    assertEquals(null, fileBar.get().modificationDate);
    assertEquals(null, fileBar.get().content);

    // Set up a new factory, and fetch the two files again, without publishing them. Verify
    // that we use the cached copies.
    factory = server.createFactory(persistentCacheDir);
    fileFoo = factory.getFile("/foo.txt");
    files.add(fileFoo);
    Thread.sleep(100); // ensure the request for foo goes through before the request for bar
    fileBar = factory.getFile("/bar.txt");
    files.add(fileBar);

    assertEquals(new Date(1000), fileFoo.get().creationDate);
    assertEquals(new Date(2000), fileFoo.get().modificationDate);
    assertEquals("xyz", fileFoo.getAsJsonForTesting().get("y"));

    assertEquals(null, fileBar.get().creationDate);
    assertEquals(null, fileBar.get().modificationDate);
    assertEquals(null, fileBar.get().content);

    // Verify that a request for an uncached file times out.
    ConfigurationFile fileBaz = factory.getFile("/baz.txt");
    files.add(fileBaz);
    assertFalse(fileBaz.hasState());
    try {
      fileBaz.getWithTimeout(100L, 100L);
      fail("Operation should have timed out");
    } catch (ScalyrDeadlineException ex) {
      // This is good.
    }
    assertFalse(fileBaz.hasState());

    // Publish an update to the first file, verify that it is recognized.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 2, 'createDate': 1000, 'modDate': 3000," +
            "'content': '{\\'x\\': 4, \\'y\\': \\'xyzw\\'}'}");

    Thread.sleep(1000); // must sleep for longer than HostedParameterFile's minimum inter-request delay

    assertEquals(new Date(1000), fileFoo.get().creationDate);
    assertEquals(new Date(3000), fileFoo.get().modificationDate);
    assertEquals("xyzw", fileFoo.getAsJsonForTesting().get("y"));

    // Set up a third factory, and verify that the update was persisted to the cache.
    factory = server.createFactory(persistentCacheDir);
    fileFoo = factory.getFile("/foo.txt");
    files.add(fileFoo);

    assertEquals(new Date(1000), fileFoo.get().creationDate);
    assertEquals(new Date(3000), fileFoo.get().modificationDate);
    assertEquals("xyzw", fileFoo.getAsJsonForTesting().get("y"));

    assertRequestQueueEmpty();
  }

  /**
   * Listener implementation used in tests.
   */
  private static class TestListener implements Consumer<ConfigurationFile> {
    public ConfigurationFile value;

    @Override public void accept(ConfigurationFile newValue) {
      value = newValue;
    }
  }
}
