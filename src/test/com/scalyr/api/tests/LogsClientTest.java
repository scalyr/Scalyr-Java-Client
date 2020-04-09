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

import com.google.common.base.Strings;
import com.scalyr.api.TuningConstants;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.logs.EventAttributes;
import com.scalyr.api.logs.EventFilter;
import com.scalyr.api.logs.EventUploader;
import com.scalyr.api.logs.Events;
import com.scalyr.api.logs.Severity;
import com.scalyr.api.logs.Span;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;


/**
 * Tests for the Logs client library (Events, EventUploader, etc.).
 */
public class LogsClientTest extends LogsTestBase {
  static final String SUCCESS  = "success";
  static final String BACKOFF  = "error/server/backoff";
  static final String DISABLED = "error/client/noPermission/accountDisabled";

  @Override @After public void teardown() {
    ScalyrUtil.removeCustomTime();

    EventUploader._maxEventUploadBytes = TuningConstants.MAX_EVENT_UPLOAD_BYTES;
    EventUploader._eventUploadByteThreshold = TuningConstants.EVENT_UPLOAD_BYTE_THRESHOLD;
    Events.setEventFilter(null);
    EventUploader._disableUploadTimer = false;
    Events._reset("testSession", server, 200, false, true);

    super.teardown();
  }

  /**
   * Test a minimal case of recording a single log event.
   */
  @Test public void simpleTest() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'test'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    Events._reset("testSession", server, 999999, false, true);

    Events.info(new EventAttributes("tag", "test"));

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Test of reportThreadNames = false.
   */
  @Test public void testDisabledThreadNames() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();
    assertFalse(threadName.equals(""));

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'test'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': ''}]" +
        "}",
        "{'status': 'success'}"
        );

    Events._reset("testSession", server, 999999, false, false);

    Events.info(new EventAttributes("tag", "test"));

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Test explicitly supplied timestamps.
   */
  @Test public void testCustomTimestamps() {
    ScalyrUtil.setCustomTimeNs(1000000);

    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '1000000000'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'test'}" +
        "  }," +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '1000000001'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'test2'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    Events._reset("testSession", server, 999999, false, true);

    Events.event(Severity.info, new EventAttributes("tag", "test"), 1000000000);
    Events.event(Severity.info, new EventAttributes("tag", "test2"), 1000000001);

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Test custom thread IDs.
   */
  @Test public void testCustomThreadIds() {
    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': 'thread1'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'test'}" +
        "  }," +
        "  {" +
        "  'thread': 'thread2'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'test2'}" +
        "  }," +
        "  {" +
        "  'thread': 'thread1'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'test3'}" +
        "  }" +
        "]," +
        "'threads': [" +
        "{'id': 'thread2', 'name': 'green'}," +
        "{'id': 'thread1', 'name': 'red'}" +
        "]" +
        "}",
        "{'status': 'success'}"
        );

    Events._reset("testSession", server, 999999, false, true);

    Events.event(Severity.info, new EventAttributes("tag", "test"), "thread1", "red");
    Events.event(Severity.info, new EventAttributes("tag", "test2"), "thread2", "green");
    Events.event(Severity.info, new EventAttributes("tag", "test3"), "thread1", "red2" /* note, red2 will be ignored since this is not a new thread */);

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Test trimming of excessively large values.
   */
  @Test public void testValueSizeLimit() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    Events._reset("testSession", server, 99999, false, true);

    String s = Strings.repeat("x", TuningConstants.MAXIMUM_EVENT_ATTRIBUTE_LENGTH);

    String largeString = "abc" + s + "def";
    String trimmed = "abc" + s.substring(0, TuningConstants.MAXIMUM_EVENT_ATTRIBUTE_LENGTH - 6) + "...";

    Events.info(new EventAttributes("foo", 10, "bar", largeString));

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'foo': 10, 'bar': '" + trimmed + "'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * A fairly simple test that exercises each parameter and value in the Events class.
   */
  @Test public void testAllParameters() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 0," +
        "  'attrs': {'tag': 'test1'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 1," +
        "  'attrs': {'tag': 2}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 2," +
        "  'attrs': {'tag': true}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 2," +
        "  'attrs': {'tag': false, 'latency': '$ANY$'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 3.5}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 4," +
        "  'attrs': {'tag': 'test4'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 5," +
        "  'attrs': {'tag': 5}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 0," +
        "  'attrs': {'tag': 'test1', 'latency': '$ANY$'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 6," +
        "  'attrs': {'tag': 'test6', 'foo': 'bar'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    Events._reset("testSession", server, 99999, false, true);

    Span span1 = Events.startFinest(new EventAttributes("tag", "test1"));
    Events.finer(new EventAttributes("tag", 2));
    Span span2 = Events.startFine(new EventAttributes("tag", true));
    Events.end(span2, new EventAttributes("tag", false));
    Events.info(new EventAttributes("tag", 3.5));
    Events.warning(new EventAttributes("tag", "test4"));
    Events.error(new EventAttributes("tag", 5L));
    Events.end(span1);
    Events.fatal(new EventAttributes("tag", "test6", "foo", "bar"));

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Test exercising two threads.
   */
  @Test public void multiThreadTest() {
    Events._reset("testSession", server, 99999, false, true);

    // Create semaphores to regulate execution of our two sub-threads and the main thread.
    final Semaphore semaphoreMain = new Semaphore(0, true);
    final Semaphore semaphore1    = new Semaphore(0, true);
    final Semaphore semaphore2    = new Semaphore(0, true);

    final long[] threadIds = new long[2];

    // Span a pair of threads that issue events.
    new Thread("thread1"){
      @Override public void run() {
        threadIds[0] = Thread.currentThread().getId();
        semaphoreMain.release();

        acquire(semaphore1);
        Span span1 = Events.startInfo(new EventAttributes("tag", "thread1.1"));
        semaphoreMain.release();

        acquire(semaphore1);
        Events.info(new EventAttributes("tag", "thread1.2"));
        semaphoreMain.release();

        acquire(semaphore1);
        Events.end(span1, new EventAttributes("tag", "thread1.3"));
        semaphoreMain.release();
      }
    }.start();

    new Thread("thread2"){
      @Override public void run() {
        threadIds[1] = Thread.currentThread().getId();
        semaphoreMain.release();

        acquire(semaphore2);
        Span span1 = Events.startInfo(new EventAttributes("tag", "thread2.1"));
        semaphoreMain.release();

        acquire(semaphore2);
        Events.info(new EventAttributes("tag", "thread2.2"));
        semaphoreMain.release();

        acquire(semaphore2);
        Events.end(span1, new EventAttributes("tag", "thread2.3"));
        semaphoreMain.release();
      }
    }.start();

    // Allow each thread to execute in carefully scripted order:

    acquire(semaphoreMain, 2); // wait until threadIds have been published

    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.1
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.1
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.2
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.3
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.2
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.3

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.1'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.1'}" +
        "  }, {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.2'}" +
        "  }, {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.3', 'latency': '$ANY$'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.2'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.3', 'latency': '$ANY$'}" +
        "  }" +
        "]," +
        "'threads': [" +
        "  {" +
        "  'id': '" + threadIds[0] + "'," +
        "  'name': 'thread1'" +
        "  }, {" +
        "  'id': '" + threadIds[1] + "'," +
        "  'name': 'thread2'" +
        "  }" +
        "]" +
        "}",
        "{'status': 'success'}"
        );

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Test verifying that the list of thread metadata in an upload request reflects only threads that have
   * had a message in the last hour.
   */
  @Test public void oldThreadTest() {
    ScalyrUtil.setCustomTimeNs(1000000000L);
    Events._reset("testSession", server, 99999, false, true);

    // Create semaphores to regulate execution of our two sub-threads and the main thread.
    final Semaphore semaphoreMain = new Semaphore(0, true);
    final Semaphore semaphore1    = new Semaphore(0, true);
    final Semaphore semaphore2    = new Semaphore(0, true);

    final long[] threadIds = new long[2];

    // Span a pair of threads that issue events.
    new Thread("thread1"){
      @Override public void run() {
        threadIds[0] = Thread.currentThread().getId();
        semaphoreMain.release();

        acquire(semaphore1);
        Span span1 = Events.startInfo(new EventAttributes("tag", "thread1.1"));
        semaphoreMain.release();

        acquire(semaphore1);
        Events.info(new EventAttributes("tag", "thread1.2"));
        semaphoreMain.release();

        acquire(semaphore1);
        Events.end(span1, new EventAttributes("tag", "thread1.3"));
        semaphoreMain.release();
      }
    }.start();

    new Thread("thread2"){
      @Override public void run() {
        threadIds[1] = Thread.currentThread().getId();
        semaphoreMain.release();

        acquire(semaphore2);
        Span span1 = Events.startInfo(new EventAttributes("tag", "thread2.1"));
        semaphoreMain.release();

        acquire(semaphore2);
        Events.info(new EventAttributes("tag", "thread2.2"));
        semaphoreMain.release();

        acquire(semaphore2);
        Events.end(span1, new EventAttributes("tag", "thread2.3"));
        semaphoreMain.release();
      }
    }.start();

    // We allow each thread to execute in carefully scripted order. First, we let each thread write a single log message.
    acquire(semaphoreMain, 2); // wait until threadIds have been published
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.1
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.1

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.1'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.1'}" +
        "  }" +
        "]," +
        "'threads': [" +
        "  {" +
        "  'id': '" + threadIds[0] + "'," +
        "  'name': 'thread1'" +
        "  }, {" +
        "  'id': '" + threadIds[1] + "'," +
        "  'name': 'thread2'" +
        "  }" +
        "]" +
        "}",
        "{'status': 'success'}"
        );
    Events.flush();

    // Wait half an hour, and write another message from thread 1. Each thread is less than an hour old, so the upload
    // request will mention both threads.
    ScalyrUtil.setCustomTimeNs(1800 * 1000000000L);
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.2

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.2'}" +
        "  }" +
        "]," +
        "'threads': [" +
        "  {" +
        "  'id': '" + threadIds[0] + "'," +
        "  'name': 'thread1'" +
        "  }, {" +
        "  'id': '" + threadIds[1] + "'," +
        "  'name': 'thread2'" +
        "  }" +
        "]" +
        "}",
        "{'status': 'success'}"
        );
    Events.flush();

    // Wait another half an hour (plus a bit), and write another message from thread 1. Thread 2 is now more than
    // an hour old, so its metadata won't be mentioned.
    ScalyrUtil.setCustomTimeNs(3610 * 1000000000L);
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.3

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.3', 'latency': '$ANY$'}" +
        "  }" +
        "]," +
        "'threads': [" +
        "  {" +
        "  'id': '" + threadIds[0] + "'," +
        "  'name': 'thread1'" +
        "  }" +
        "]" +
        "}",
        "{'status': 'success'}"
        );
    Events.flush();

    // Now write a couple of message from thread 2. Both threads will show up in the upload request.
    ScalyrUtil.setCustomTimeNs(3611 * 1000000000L);
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.2
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.3

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.2'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.3', 'latency': '$ANY$'}" +
        "  }" +
        "]," +
        "'threads': [" +
        "  {" +
        "  'id': '" + threadIds[0] + "'," +
        "  'name': 'thread1'" +
        "  }, {" +
        "  'id': '" + threadIds[1] + "'," +
        "  'name': 'thread2'" +
        "  }" +
        "]" +
        "}",
        "{'status': 'success'}"
        );
    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Test of low-memory operation.
   */
  @Test public void lowMemoryTest() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    // Initialize with a buffer that can hold two small events.
    Events._reset("testSession", server, 200, false, true);

    // Issue four events; verify that the first two are uploaded.
    Events.info(new EventAttributes("tag", "one"));
    Events.info(new EventAttributes("tag", "two"));
    Events.info(new EventAttributes("tag", "three"));
    Events.info(new EventAttributes("tag", "four"));

    expectSimpleUpload(threadId, threadName, SUCCESS, "one", "two");

    Events.flush();

    // Issue a few more events, the second of which is quite large. Verify that only the first one is
    // uploaded.

    StringBuilder manySixes = new StringBuilder();
    for (int i = 0; i < 200; i++)
      manySixes.append("six ");

    Events.info(new EventAttributes("tag", "five"));
    Events.info(new EventAttributes("tag", manySixes.toString()));
    Events.info(new EventAttributes("tag", "seven"));
    Events.info(new EventAttributes("tag", "eight"));

    expectSimpleUpload(threadId, threadName, SUCCESS, "five");

    Events.flush();

    assertRequestQueueEmpty();
  }

  /**
   * Verify that when events are discarded due to buffer overflow, we make a note in the log.
   */
  @Test public void testLowMemoryLogging() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    Events._reset("testSession", server, 1000, false, true);

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; i++)
      sb.append("x");
    String largeString = "abc" + sb.toString() + "def";

    // Log an event that won't fit, then one that could. Neither will show up in the
    // log, but the buffer-overflow message should. Despite discarding two events,
    // we'll only record one overflow message.
    Events.info(new EventAttributes("tag", "one", "foo", largeString));
    Events.info(new EventAttributes("tag", "two"));

    String overflowMessageJson = buildOverflowMessageJson(threadId);

    expectSimpleUpload(threadId, threadName, SUCCESS, overflowMessageJson);
    Events.flush();
    assertRequestQueueEmpty();

    // The next time we hit the memory limit, we'll get another message.
    Events.info(new EventAttributes("tag", "three"));
    Events.info(new EventAttributes("tag", "four", "foo", largeString));
    Events.info(new EventAttributes("tag", "five"));

    expectSimpleUpload(threadId, threadName, SUCCESS, "three", overflowMessageJson);
    Events.flush();
    assertRequestQueueEmpty();
  }

  private String buildOverflowMessageJson(long threadId) {
    String overflowMessageJson = "{" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 4," +
        "  'attrs': {'tag': 'eventBufferOverflow', 'message': 'Discarding log records due to buffer overflow'}" +
        "}";
    return overflowMessageJson;
  }

  /**
   * A test of nested event handling in low-memory circumstances.
   */
  @Test public void testLowMemoryNesting() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    Events._reset("testSession", server, 500, false, true);

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 2000; i++)
      sb.append("x");
    String largeString = "abc" + sb.toString() + "def";

    // Log a small event, then an event that won't fit, and a small start event.
    // Flush. Only the first event will go up; the second didn't fit, and the
    // third will be caught by the no-stuttering rule.
    Events.info(new EventAttributes("tag", "one"));
    Events.info(new EventAttributes("tag", "two", "foo", largeString));
    Span span = Events.startInfo(new EventAttributes("tag", "three"));

    expectSimpleUpload(threadId, threadName, SUCCESS, "one", buildOverflowMessageJson(threadId));
    Events.flush();
    assertRequestQueueEmpty();

    // Now issue a series of small events: leaf, end, leaf, leaf. Essentially they will be
    // converted to four leaf events, and non will be dropped
    Events.info(new EventAttributes("tag", "four"));
    Events.end(span, new EventAttributes("tag", "five"));
    Events.info(new EventAttributes("tag", "six"));
    Events.info(new EventAttributes("tag", "seven"));

    expectSimpleUpload(threadId, threadName, SUCCESS, "four", "end:five", "six", "seven");
    Events.flush();
    assertRequestQueueEmpty();
  }

  /**
   * Verify that end events get priority when the buffer is almost full.
   */
  @Test public void testLowMemoryEndEvents() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    // Initialize with a 1210-byte buffer. 48 bytes of this will be reserved
    // for end events (the 5% end-and-overflow reservation, minus the 1%
    // overflow-only reservation).
    Events._reset("testSession", server, 1210, false, true);

    // Log a start event with a 1000 byte tag. Simple tag-only events use about
    // 80 bytes; this one will use about 1080. With 12 bytes reserved for overflow
    // messages, there should be just enough room for an end event.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; i++)
      sb.append("x");
    String largeString = sb.toString();

    Span span = Events.startInfo(new EventAttributes("tag", largeString));

    // Log a leaf event and an end event. Both should be dropped since they are
    // converted to leaf events.
    Events.info(new EventAttributes("tag", "two"));
    Events.end(span, new EventAttributes("tag", "three"));

    expectSimpleUpload(threadId, threadName, SUCCESS, largeString);
    Events.flush();
    assertRequestQueueEmpty();
  }

  /**
   * Test of a series of events which have to be split into multiple chunks for upload.
   */
  @Test public void testChunkedUploads() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    Events._reset("testSession", server, 99999, false, true);

    // Specify a small size for upload chunks. Note that each individual
    // event occupies 80 bytes or so.
    EventUploader._maxEventUploadBytes = 200;

    // Issue a series of events. Verify that they are uploaded in chunks.
    Events.info(new EventAttributes("tag", "one"));
    Events.info(new EventAttributes("tag", "two"));
    Events.info(new EventAttributes("tag", "three"));
    Events.info(new EventAttributes("tag", "four"));
    Events.info(new EventAttributes("tag", "five"));

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'one'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'two'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'three'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'four'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'five'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    Events.flush();
    assertRequestQueueEmpty();
  }

  /**
   * A simple test of upload error handling.
   */
  @Test public void testErrorHandling() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    Events._reset("testSession", server, 99999, false, true);

    // Issue a single event.
    Events.info(new EventAttributes("tag", "one"));

    // Have the server issue a failure response, and then a success response.
    expectSimpleUpload(threadId, threadName, BACKOFF, "one");
    expectSimpleUpload(threadId, threadName, SUCCESS, "one");

    Events.flush();
    assertRequestQueueEmpty();
  }

  /**
   * Verify that an event batch will be cleanly discarded after 20 minutes of failed upload attempts.
   */
  @Test public void testPersistentErrorHandling() {
    testPersistentErrorHandling(false);
    testPersistentErrorHandling(true);
  }

  private void testPersistentErrorHandling(boolean disabled) {
    // this test heisenbugs on jenkins; rather than chase it down we just skip it there
    Assume.assumeTrue(System.getenv("JENKINS_HOME") == null);

    ScalyrUtil.setCustomTimeNs(10 * 1000000000L);
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    Events._reset("testSession", server, 99999, false, true);
    EventUploader._disableUploadTimer = true;

    // Issue a couple of events.
    Events.info(new EventAttributes("tag", "one"));
    Events.info(new EventAttributes("tag", "two"));

    // Let the client make an upload attempt; have it fail.
    ScalyrUtil.setCustomTimeNs(20 * 1000000000L);
    expectSimpleUpload(threadId, threadName, BACKOFF, "one", "two");
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    // Issue a couple more events. They should go into a new batch.
    Events.info(new EventAttributes("tag", "three"));
    Events.info(new EventAttributes("tag", "four"));

    // Let the client make several more upload attempts, all failing, over the space of 20 minutes.
    ScalyrUtil.setCustomTimeNs(350 * 1000000000L);
    expectSimpleUpload(threadId, threadName, BACKOFF, "one", "two");
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    ScalyrUtil.setCustomTimeNs(650 * 1000000000L);
    expectSimpleUpload(threadId, threadName, BACKOFF, "one", "two");
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    ScalyrUtil.setCustomTimeNs(950 * 1000000000L);
    expectSimpleUpload(threadId, threadName, BACKOFF, "one", "two");
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    ScalyrUtil.setCustomTimeNs(1250 * 1000000000L);
    expectSimpleUpload(threadId, threadName, disabled ? DISABLED : BACKOFF, "one", "two");
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    // That last failed attempt should have caused the client to discard the event batch. The next
    // upload attempt will involve the next batch; we'll allow it to succeed.
    ScalyrUtil.setCustomTimeNs(1260 * 1000000000L);
    expectSimpleUpload(threadId, threadName, SUCCESS, "three", "four");
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    Events.flush();
    assertRequestQueueEmpty();
  }

  /**
   * Test EventUploader's decisions of when to upload a batch of events.
   */
  @Test public void testUploadTiming() {
    ScalyrUtil.setCustomTimeNs(10 * 1000000000L);
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    Events._reset("testSession", server, 99999, false, true);
    EventUploader._disableUploadTimer = true;
    EventUploader._discardBatchesAfterPersistentFailures = false;

    // Specify a small size for the upload threshold. Note that each individual
    // event occupies 80 bytes or so.
    EventUploader._eventUploadByteThreshold = 120;

    // Issue a single event. Verify that it is not uploaded for 4.5 seconds (EVENT_UPLOAD_TIME_THRESHOLD_MS).
    Events.info(new EventAttributes("tag", "one"));

    // Immediate upload does nothing.
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    // 4.0 seconds later, still nothing.
    ScalyrUtil.advanceCustomTimeMs(4000);
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    // 6.0 seconds later, the upload occurs. (Maybe sooner, depending on uploadSpacingFuzzFactor.)
    expectSimpleUpload(threadId, threadName, SUCCESS, "one");
    ScalyrUtil.advanceCustomTimeMs(2000);
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    // Now we issue a pair of events. This is larger than _eventUploadByteThreshold, so
    // they'll upload after one second -- but not sooner, due to MIN_EVENT_UPLOAD_SPACING_MS.
    Events.info(new EventAttributes("tag", "two"));
    Events.info(new EventAttributes("tag", "three"));

    ScalyrUtil.advanceCustomTimeMs(500);
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    expectSimpleUpload(threadId, threadName, SUCCESS, "two", "three");
    ScalyrUtil.advanceCustomTimeMs(500);
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    // Next, we force a series of failed uploads, and verify that backoff occurs properly.
    assertEquals(900, Events._getMinUploadIntervalMs(), 1E-9);

    Events.info(new EventAttributes("tag", "four"));

    double interval = 900;
    for (int i = 0; i < 20; i++) {
      expectSimpleUpload(threadId, threadName, BACKOFF, "four");
      ScalyrUtil.advanceCustomTimeMs(100000);
      Events._uploadTimerTick(false);
      assertRequestQueueEmpty();

      interval = Math.min(interval * TuningConstants.UPLOAD_SPACING_FACTOR_ON_BACKOFF, TuningConstants.MAX_EVENT_UPLOAD_SPACING_MS);
      assertEquals(Events._getMinUploadIntervalMs(), interval, 1E-9);
    }

    // The interval is now at 30 seconds. Verify that upload attempts indeed won't occur more
    // frequently than this.
    assertEquals(TuningConstants.MAX_EVENT_UPLOAD_SPACING_MS, Events._getMinUploadIntervalMs(), 1E-9);

    ScalyrUtil.advanceCustomTimeMs(29000);
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    expectSimpleUpload(threadId, threadName, SUCCESS, "four");
    ScalyrUtil.advanceCustomTimeMs(2000);
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    // The last upload was successful, so the interval decreases.
    interval = interval * TuningConstants.UPLOAD_SPACING_FACTOR_ON_SUCCESS;
    assertEquals(interval, Events._getMinUploadIntervalMs(), 1E-9);

    // Even after a long passage of time, no further uploads occur, since there are no more
    // events to upload.
    ScalyrUtil.advanceCustomTimeMs(100 * 1000);
    Events._uploadTimerTick(false);
    assertRequestQueueEmpty();

    Events.flush();
    assertRequestQueueEmpty();
  }

  /**
   * Exercise use of EventFilters.
   */
  @Test public void testEventFilter() {
    long threadId = Thread.currentThread().getId();
    String threadName = Thread.currentThread().getName();

    // Filter which modifies the sequence of events as follows:
    // - Modify the attributes and severity of event "one"
    // - Discard event "two", and everything inside it
    // - Discard event "five"
    // - Discard event "eight", and everything inside it except for event "ten"
    TestFilter filter = new TestFilter() {
      @Override public void filter(FilterInput input, FilterOutput output) {
        super.filter(input, output);

        String tag = (String) input.attributes.get("tag");
        if (tag == null)
          tag = "";

        if (input.inDiscardedSpan && !tag.equals("ten"))
          output.discardEvent = true;

        if (tag.equals("one")) {
          output.severity = Severity.finest;
          output.attributes = new EventAttributes(input.attributes);
          output.attributes.put("foo", "bar");
        } else if (tag.equals("two")) {
          output.discardEvent = true;
        } else if (tag.equals("five")) {
          output.discardEvent = true;
        } else if (tag.equals("eight")) {
          output.discardEvent = true;
        }
      }
    };
    Events.setEventFilter(filter);
    Events._reset("testSession", server, 99999, false, true);

    // Issue a series of events.
    Events.info(new EventAttributes("tag", "one"));

    Span span1 = Events.startFine(new EventAttributes("tag", "two"));
     Span span2 = Events.startWarning(new EventAttributes("tag", "three"));
      Events.warning(null);
     Events.end(span2, new EventAttributes("tag", "four"));
    Events.end(span1);

    Events.info(new EventAttributes("tag", "five"));
    Events.info(new EventAttributes("tag", "six"));

    Span span3 = Events.startFine(new EventAttributes("tag", "seven"));
     Span span4 = Events.startWarning(new EventAttributes("tag", "eight"));
      Events.warning(new EventAttributes("tag", "nine"));
      Events.info(new EventAttributes("tag", "ten"));
     Events.end(span4, new EventAttributes("tag", "eleven"));
     Events.info(new EventAttributes("tag", "twelve"));
    Events.end(span3, new EventAttributes("tag", "thirteen"));

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 0," +
        "  'attrs': {'tag': 'one', 'foo': 'bar'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 4," +
        "  'attrs': {'tag': 'three'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 4," +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 4," +
        "  'attrs': {'tag': 'four', 'latency': '$ANY$'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'six'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 2," +
        "  'attrs': {'tag': 'seven'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 4," +
        "  'attrs': {'tag': 'nine'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'ten'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 4," +
        "  'startTS': '$ANY$'," +
        "  'attrs': {'tag': 'eleven', 'latency': '$ANY$'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'twelve'}" +
        "  }, {" +
        "  'thread': '" + threadId + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 2," +
        "  'attrs': {'tag': 'thirteen', 'latency': '$ANY$'}" +
        "  }" +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': 'success'}"
        );

    Events.flush();
    assertRequestQueueEmpty();

    assertEquals(15, filter.inputs.size());
    filter.verifyInput(0,  threadId, 0, Severity.info,    new EventAttributes("tag", "one"),      true, false);
    filter.verifyInput(1,  threadId, 0, Severity.fine,    new EventAttributes("tag", "two"),      true, false);
    filter.verifyInput(2,  threadId, 0, Severity.warning, new EventAttributes("tag", "three"),    true, false);
    filter.verifyInput(3,  threadId, 0, Severity.warning, new EventAttributes(),                  true, false);
    filter.verifyInput(4,  threadId, 0, Severity.warning, new EventAttributes("tag", "four", "latency", EventAttributes.MATCH_ANY), true, false);
    filter.verifyInput(5,  threadId, 0, Severity.fine,    new EventAttributes("tag", "two",  "latency", EventAttributes.MATCH_ANY), true, false);
    filter.verifyInput(6,  threadId, 0, Severity.info,    new EventAttributes("tag", "five"),     true, false);
    filter.verifyInput(7,  threadId, 0, Severity.info,    new EventAttributes("tag", "six"),      true, false);
    filter.verifyInput(8,  threadId, 0, Severity.fine,    new EventAttributes("tag", "seven"),    true, false);
    filter.verifyInput(9,  threadId, 0, Severity.warning, new EventAttributes("tag", "eight"),    true, false);
    filter.verifyInput(10, threadId, 0, Severity.warning, new EventAttributes("tag", "nine"),     true, false);
    filter.verifyInput(11, threadId, 0, Severity.info,    new EventAttributes("tag", "ten"),      true, false);
    filter.verifyInput(12, threadId, 0, Severity.warning, new EventAttributes("tag", "eleven", "latency", EventAttributes.MATCH_ANY),   true, false);
    filter.verifyInput(13, threadId, 0, Severity.info,    new EventAttributes("tag", "twelve"),   true, false);
    filter.verifyInput(14, threadId, 0, Severity.fine,    new EventAttributes("tag", "thirteen", "latency", EventAttributes.MATCH_ANY), true, false);
  }

  /**
   * Exercise use of EventFilters on multiple threads -- verify that we track state
   * for each thread separately.
   */
  @Test public void testMultiThreadEventFilter() {
    String threadName = Thread.currentThread().getName();

    // Filter which discards event "thread1.2", and everything inside it.
    TestFilter filter = new TestFilter() {
      @Override public void filter(FilterInput input, FilterOutput output) {
        super.filter(input, output);

        String tag = (String) input.attributes.get("tag");
        if (tag == null)
          tag = "";

        if (input.inDiscardedSpan)
          output.discardEvent = true;

        if (tag.equals("thread1.2")) {
          output.discardEvent = true;
        }
      }
    };
    Events.setEventFilter(filter);
    Events._reset("testSession", server, 99999, false, true);

    // Create semaphores to regulate execution of our two sub-threads and the main thread.
    final Semaphore semaphoreMain = new Semaphore(0, true);
    final Semaphore semaphore1    = new Semaphore(0, true);
    final Semaphore semaphore2    = new Semaphore(0, true);

    final long[] threadIds = new long[2];

    // Span a pair of threads that issue events.
    new Thread("thread1"){
      @Override public void run() {
        threadIds[0] = Thread.currentThread().getId();
        semaphoreMain.release();

        acquire(semaphore1);
        Events.info(new EventAttributes("tag", "thread1.1"));
        semaphoreMain.release();

        acquire(semaphore1);
        Span span1 = Events.startInfo(new EventAttributes("tag", "thread1.2"));
        semaphoreMain.release();

        acquire(semaphore1);
        Events.info(new EventAttributes("tag", "thread1.3"));
        semaphoreMain.release();

        acquire(semaphore1);
        Events.end(span1, new EventAttributes("tag", "thread1.4"));
        semaphoreMain.release();

        acquire(semaphore1);
        Events.info(new EventAttributes("tag", "thread1.5"));
        semaphoreMain.release();
      }
    }.start();

    new Thread("thread2"){
      @Override public void run() {
        threadIds[1] = Thread.currentThread().getId();
        semaphoreMain.release();

        acquire(semaphore2);
        Events.info(new EventAttributes("tag", "thread2.1"));
        semaphoreMain.release();

        acquire(semaphore2);
        Span span1 = Events.startInfo(new EventAttributes("tag", "thread2.2"));
        semaphoreMain.release();

        acquire(semaphore2);
        Events.info(new EventAttributes("tag", "thread2.3"));
        semaphoreMain.release();

        acquire(semaphore2);
        Events.end(span1, new EventAttributes("tag", "thread2.4"));
        semaphoreMain.release();

        acquire(semaphore2);
        Events.info(new EventAttributes("tag", "thread2.5"));
        semaphoreMain.release();
      }
    }.start();

    // Allow each thread to execute in carefully scripted order:

    acquire(semaphoreMain, 2); // wait until threadIds have been published

    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.1
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.1
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.2
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.3
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.2
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.3
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.4
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.4
    releaseAndAcquire(semaphore2, semaphoreMain); // thread2.5
    releaseAndAcquire(semaphore1, semaphoreMain); // thread1.5

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        "  {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.1'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.1'}" +
        "  }, {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.3'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.2'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.3'}" +
        "  }, {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'startTS': '$ANY$'," +
        "  'attrs': {'tag': 'thread1.4', 'latency': '$ANY$'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'startTS': '$ANY$'," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.4', 'latency': '$ANY$'}" +
        "  }, {" +
        "  'thread': '" + threadIds[1] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread2.5'}" +
        "  }, {" +
        "  'thread': '" + threadIds[0] + "'," +
        "  'ts': '$ANY$'," +
        "  'type': 0," +
        "  'sev': 3," +
        "  'attrs': {'tag': 'thread1.5'}" +
        "  }" +
        "]," +
        "'threads': [" +
        "  {" +
        "  'id': '" + threadIds[0] + "'," +
        "  'name': 'thread1'" +
        "  }, {" +
        "  'id': '" + threadIds[1] + "'," +
        "  'name': 'thread2'" +
        "  }" +
        "]" +
        "}",
        "{'status': 'success'}"
        );

    Events.flush();
    assertRequestQueueEmpty();

    assertEquals(10, filter.inputs.size());
    filter.verifyInput(0, threadIds[0], 0, Severity.info, new EventAttributes("tag", "thread1.1"), true, false);
    filter.verifyInput(1, threadIds[1], 0, Severity.info, new EventAttributes("tag", "thread2.1"), true, false);
    filter.verifyInput(2, threadIds[0], 0, Severity.info, new EventAttributes("tag", "thread1.2"), true, false);
    filter.verifyInput(3, threadIds[0], 0, Severity.info, new EventAttributes("tag", "thread1.3"), true, false);
    filter.verifyInput(4, threadIds[1], 0, Severity.info, new EventAttributes("tag", "thread2.2"), true, false);
    filter.verifyInput(5, threadIds[1], 0, Severity.info, new EventAttributes("tag", "thread2.3"), true, false);
    filter.verifyInput(6, threadIds[0], 0, Severity.info, new EventAttributes("tag", "thread1.4", "latency", EventAttributes.MATCH_ANY), true, false);
    filter.verifyInput(7, threadIds[1], 0, Severity.info, new EventAttributes("tag", "thread2.4", "latency", EventAttributes.MATCH_ANY), true, false);
    filter.verifyInput(8, threadIds[1], 0, Severity.info, new EventAttributes("tag", "thread2.5"), true, false);
    filter.verifyInput(9, threadIds[0], 0, Severity.info, new EventAttributes("tag", "thread1.5"), true, false);
  }

  private static class TestFilter extends EventFilter {
    public List<FilterInput> inputs = new ArrayList<FilterInput>();

    @Override public void filter(FilterInput input, FilterOutput output) {
      inputs.add(input);
    }

    /**
     * Verify that entry index in our inputs list matches the specified values.
     */
    public void verifyInput(int index, long threadId, int spanType,
        Severity severity, EventAttributes attributes, boolean isTopLevel, boolean inDiscardedSpan) {
      FilterInput input = inputs.get(index);

      // Note that we don't check input.timestampNs, as we don't have any easy way of determining
      // the correct value.

      assertEquals(Long.toString(threadId), input.threadId);
      assertEquals(spanType, input.spanType);
      assertEquals(severity, input.severity);
      assertEquals(attributes, input.attributes);
      assertEquals(isTopLevel, input.isTopLevel);
      assertEquals(inDiscardedSpan, input.inDiscardedSpan);
    }
  }

  private static void acquire(Semaphore semaphore) {
    acquire(semaphore, 1);
  }

  private static void acquire(Semaphore semaphore, int count) {
    try {
      semaphore.acquire(count);
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static void releaseAndAcquire(Semaphore toRelease, Semaphore toAcquire) {
    toRelease.release();
    acquire(toAcquire, 1);
  }

  /**
   * Invoke expectedRequest, expecting an event upload containing a sequence of INFO events
   * with the specified tags. Each tag can optionally be prefixed with "start:" or "end:" to
   * indicate a start or end event. Alternately, a "tag" that begins with an open-brace is
   * treated as a complete serialized event.
   */
  private void expectSimpleUpload(long threadId, String threadName, String status, String ... tags) {
    StringBuilder events = new StringBuilder();
    for (String tag : tags) {
      if (events.length() > 0)
        events.append(", ");

      if (tag.startsWith("{")) {
        events.append(tag);
        continue;
      }

      events.append("  {");
      events.append("  'thread': '" + threadId + "',");
      events.append("  'ts': '$ANY$',");

      int eventType = 0;
      String strippedTag = tag;
      if (tag.startsWith("start:")) {
        strippedTag = strippedTag.substring(6);
      } else if (tag.startsWith("end:")) {
        strippedTag = strippedTag.substring(4);
        events.append("  'startTS': '$ANY$',");
        strippedTag = strippedTag + "', 'latency': '$ANY$";
      }

      events.append("  'type': " + eventType + ",");
      events.append("  'sev': 3,");
      events.append("  'attrs': {'tag': '" + strippedTag + "'}");
      events.append("  }");
    }

    expectRequest(
        "addEvents",
        "{'token': 'dummyToken'," +
        "'clientVersion': 1," +
        "'session': 'testSession'," +
        "'sessionInfo': {" +
        "  'session': 'testSession'," +
        "  'launchTime': '$ANY$'," +
        "  'serverHost': '$ANY$'" +
        "}," +
        "'events': [" +
        events.toString() +
        "]," +
        "'threads': [{" +
        "  'id': '" + threadId + "'," +
        "  'name': '" + threadName + "'}]" +
        "}",
        "{'status': '" + status + "'}"
        );
  }
}
