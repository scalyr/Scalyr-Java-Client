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

import com.scalyr.api.LogHook;
import com.scalyr.api.internal.Logging;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.internal.SimpleRateLimiter;
import com.scalyr.api.knobs.ConfigurationFile;
import com.scalyr.api.knobs.ConfigurationFileFactory;
import com.scalyr.api.knobs.Knob;
import com.scalyr.api.knobs.LocalConfigurationFile;
import com.scalyr.api.logs.Severity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Tests for Knob.
 */
public class KnobTest extends KnobTestBase {
  private File paramDir;
  private ConfigurationFileFactory paramFactory;

  @Before @Override public void setup() {
    super.setup();

    paramDir = TestUtils.createTemporaryDirectory();
    paramFactory = ConfigurationFile.makeLocalFileFactory(paramDir, 100);
  }

  @After public void teardownKnobTest() {
    LocalConfigurationFile.useLastKnownGoodJson = true;

    ScalyrUtil.recreateAsyncApiExecutor();

    TestUtils.recursiveDelete(paramDir);
  }

  /**
   * Simple test of reading a knob file. Exercises some of our extensions to the JSON format --
   * comments, optional quoting on attributenames, etc.
   */
  @Test public void testJsonExtensions() {
    // Publish, and verify, a simple file.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{foo: \\'abc\\', /*comment*/ \\'bar\\': \\'xyz\\'}'}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    Knob.String valueFoo = new Knob.String("foo", "fooDefault", paramFile);
    Knob.String valueBar = new Knob.String("bar", "barDefault", paramFile);
    Knob.String valueBaz = new Knob.String("baz", "bazDefault", paramFile);

    assertEquals("abc", valueFoo.get());
    assertEquals("xyz", valueBar.get());
    assertEquals("bazDefault", valueBaz.get());

    assertRequestQueueEmpty();
  }

  /**
   * Straightforward test where we read values from a file and update the file.
   */
  @Test public void testUpdates() {
    // Publish, and verify, an initial version of the file.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': \\'abc\\', \\'bar\\': \\'xyz\\'}'}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    Knob.String valueFoo = new Knob.String("foo", "fooDefault", paramFile);
    Knob.String valueBar = new Knob.String("bar", "barDefault", paramFile);
    Knob.String valueBaz = new Knob.String("baz", "bazDefault", paramFile);

    assertEquals("abc", valueFoo.get());
    assertEquals("xyz", valueBar.get());
    assertEquals("bazDefault", valueBaz.get());

    TestListener fooListener = new TestListener();
    valueFoo.addUpdateListener(fooListener);
    assertNull(fooListener.value);

    TestListener barListener = new TestListener();
    valueBar.addUpdateListener(barListener);
    assertNull(barListener.value);

    TestListener bazListener = new TestListener();
    valueBaz.addUpdateListener(bazListener);
    assertNull(bazListener.value);

    // Publish a second version, pause to ensure it's picked up, and then verify it.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt', 'expectedVersion': 1}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 2, 'createDate': 1000, 'modDate': 3000," +
            "'content': '{\\'foo\\': \\'abc2\\', \\'bar\\': \\'xyz\\'}'}");

    try {
      Thread.sleep(1000); // must sleep for longer than HostedParameterFile's minimum inter-request delay
    } catch (InterruptedException ex) {
      ex.printStackTrace();
    }

    assertEquals("abc2", valueFoo.get());
    assertEquals("xyz", valueBar.get());
    assertEquals("bazDefault", valueBaz.get());

    assertEquals("abc2", fooListener.value);
    assertEquals(null, barListener.value);
    assertEquals(null, bazListener.value);
    fooListener.value = null;

    valueFoo.removeUpdateListener(fooListener);

    // Publish a third version.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt', 'expectedVersion': 2}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 3, 'createDate': 1000, 'modDate': 3000," +
            "'content': '{\\'foo\\': \\'abc3\\', \\'baz\\': \\'pdq\\'}'}");

    try {
      Thread.sleep(1000); // must sleep for longer than HostedParameterFile's minimum inter-request delay
    } catch (InterruptedException ex) {
      ex.printStackTrace();
    }

    assertEquals("abc3", valueFoo.get());
    assertEquals("barDefault", valueBar.get());
    assertEquals("pdq", valueBaz.get());

    assertEquals(null, fooListener.value);
    assertEquals("barDefault", barListener.value);
    assertEquals("pdq", bazListener.value);
    barListener.value = null;
    bazListener.value = null;

    // Publish a fourth, non-parseable version.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt', 'expectedVersion': 3}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 4, 'createDate': 1000, 'modDate': 3000," +
            "'content': 'blah'}");

    try {
      Thread.sleep(1000); // must sleep for longer than HostedParameterFile's minimum inter-request delay
    } catch (InterruptedException ex) {
      ex.printStackTrace();
    }

    assertEquals("fooDefault", valueFoo.get());
    assertEquals("barDefault", valueBar.get());
    assertEquals("bazDefault", valueBaz.get());

    assertEquals(null, fooListener.value);
    assertEquals(null, barListener.value);
    assertEquals("bazDefault", bazListener.value);
    bazListener.value = null;

    assertRequestQueueEmpty();
  }

  /**
   * Test of layering multiple files.
   */
  @Test public void testLayering() throws IOException, InterruptedException {
    LocalConfigurationFile.useLastKnownGoodJson = false;

    Knob.setDefaultFiles(new ConfigurationFile[0]);

    // Set up a pair of parameter files.
    LocalConfigurationFileTest.createOrUpdateFile(paramDir, "foo", "{\"x\": 10}");
    LocalConfigurationFileTest.createOrUpdateFile(paramDir, "bar", "{\"x\": 20, \"y\": 100}");

    ConfigurationFile fileFoo = paramFactory.getFile("/foo");
    ConfigurationFile fileBar = paramFactory.getFile("/bar");
    ConfigurationFile fileBaz = paramFactory.getFile("/baz");

    Knob.Integer value1 = new Knob.Integer("x", 1);
    Knob.Integer value2 = new Knob.Integer("x", 2, fileFoo, fileBar, fileBaz);
    Knob.Integer value3 = new Knob.Integer("x", 3, fileBaz, fileBar, fileFoo);

    Knob.Integer value4 = new Knob.Integer("y", 5, fileFoo, fileBar, fileBaz);
    Knob.Integer value5 = new Knob.Integer("y", 6, fileBaz, fileBar, fileFoo);

    assertEquals((Integer)1,   value1.get());
    assertEquals((Integer)10,  value2.get());
    assertEquals((Integer)20,  value3.get());
    assertEquals((Integer)100, value4.get());
    assertEquals((Integer)100, value5.get());

    // Create one file, update another, and delete a third.
    LocalConfigurationFileTest.createOrUpdateFile(paramDir, "foo", null);
    LocalConfigurationFileTest.createOrUpdateFile(paramDir, "bar", "{\"x\": 21, \"y\": 101}");
    LocalConfigurationFileTest.createOrUpdateFile(paramDir, "baz", "{\"x\": 30, \"y\": 110}");

    // Pause long enough for the changes to be detected, and verify the effects.
    Thread.sleep(500);

    assertEquals((Integer)1,   value1.get());
    assertEquals((Integer)21,  value2.get());
    assertEquals((Integer)30,  value3.get());
    assertEquals((Integer)101, value4.get());
    assertEquals((Integer)110, value5.get());
  }

  /**
   * Test null knob names.
   */
  @Test public void testNullNames() {
    Knob.setDefaultFiles(new ConfigurationFile[0]);

    Knob.Integer x = new Knob.Integer(null, 123);
    Knob.String y = new Knob.String(null, "foo");

    assertEquals((Integer)123, x.get());
    assertEquals("foo", y.get());
  }

  /**
   * Test of a bad JSON file.
   */
  @Test public void testBadJson() {
    // Publish, and verify, an initial version of the file with a missing close brace.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{\\'foo\\': \\'abc\\', \\'bar\\': \\'xyz\\''}");

    ConfigurationFile paramFile = factory.getFile("/foo.txt");
    Knob.String valueFoo = new Knob.String("foo", "fooDefault", paramFile);
    Knob.String valueBar = new Knob.String("bar", "barDefault", paramFile);
    Knob.String valueBaz = new Knob.String("baz", "bazDefault", paramFile);

    assertEquals("fooDefault", valueFoo.get());
    assertEquals("barDefault", valueBar.get());
    assertEquals("bazDefault", valueBaz.get());

    // Publish a second version, pause to ensure it's picked up, and then verify it.
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt', 'expectedVersion': 1}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 2, 'createDate': 1000, 'modDate': 3000," +
            "'content': '{\\'foo\\': \\'abc2\\', \\'bar\\': \\'xyz\\'}'}");

    try {
      Thread.sleep(1000); // must sleep for longer than HostedParameterFile's minimum inter-request delay
    } catch (InterruptedException ex) {
      ex.printStackTrace();
    }

    assertEquals("abc2", valueFoo.get());
    assertEquals("xyz", valueBar.get());
    assertEquals("bazDefault", valueBaz.get());
  }

  /**
   * Generate log output, with a patched ThresholdLogger that limits output to a few lines per second.
   * The output from this test must be inspected by hand to verify that throttling is done correctly.
   */
  @Test public void testLogThrottling() {
    ScalyrUtil.setCustomTimeNs(0);

    // Install a ThresholdLogger that limits to 1.5 messages per second, with a burst of 3.
    Logging.setHook(new LogHook.ThresholdLogger(Severity.fine, new SimpleRateLimiter(1.5, 3), new SimpleRateLimiter(1, 3)));

    // Spit out a series of log messages, advancing the simulated clock each time.
    for (int i = 0; i < 200; i++) {
      Logging.log(Severity.info, Logging.tagInternalError, "test message " + i);
      Logging.log(Severity.info, Logging.tagKnobFileInvalid, "test message " + i);
      ScalyrUtil.advanceCustomTimeMs(300);
    }
  }

  /**
   * Test of Knob.Size
   */
  @Test public void testKnobSize() {
    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/foo.txt'}",
        "{'status': 'success', 'path': '/foo.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{ invalid1: \\' \\', \\'invalid2\\': \\'12a\\', invalid3: \\'32M\\', invalid4: \\'32iB\\', invalid5: \\'32KBs\\', invalid6: \\'10kb\\'}'}");
    ConfigurationFile foo = factory.getFile("/foo.txt");

    verifyExceptionMessageContains(new Knob.Integer("invalid1", -1, foo)::get, "Can't convert [");
    verifyExceptionMessageContains(new Knob.Integer("invalid2", -1, foo)::get, "Can't convert [");
    verifyExceptionMessageContains(new Knob.Integer("invalid3", -1, foo)::get, "Can't convert [");
    verifyExceptionMessageContains(new Knob.Integer("invalid4", -1, foo)::get, "Can't convert [");
    verifyExceptionMessageContains(new Knob.Integer("invalid5", -1, foo)::get, "Can't convert [");

    expectRequest(
        "getFile",
        "{'token': 'dummyToken', 'path': '/bar.txt'}",
        "{'status': 'success', 'path': '/bar.txt', 'version': 1, 'createDate': 1000, 'modDate': 2000," +
            "'content': '{ number: \\'  123  \\', b: \\'23 B\\', \\'kb\\': \\'45KB\\', mib: \\'  67MiB\\', gb: \\' 89 GB\\', tb: \\'34 TiB \\'}'}");
    ConfigurationFile bar = factory.getFile("/bar.txt");

    assertEquals(123, (long)(new Knob.Long("number", -1L, bar).get()));
    assertEquals(23, (long)(new Knob.Long("b", -1L, bar).get()));
    assertEquals(45 * 1000, (long)(new Knob.Long("kb", -1L, bar).get()));
    assertEquals(67 * 1024 * 1024, (long)(new Knob.Long("mib", -1L, bar).get()));
    assertEquals(89L * 1000 * 1000 * 1000, (long)(new Knob.Long("gb", -1L, bar).get()));
    assertEquals(34L * 1024 * 1024 * 1024 * 1024, (long)(new Knob.Long("tb", -1L, bar).get()));
  }

  private void verifyExceptionMessageContains(Supplier supplier, String message) {
    try {
      supplier.get();
    } catch (Exception e) {
      assertTrue(e.getMessage().contains(message));
    }
  }

  /**
   * Listener implementation used in tests.
   */
  private static class TestListener implements Consumer<Knob> {
    public String value;

    @Override public void accept(Knob newValue) {
      value = ((Knob.String)newValue).get();
    }
  }
}
