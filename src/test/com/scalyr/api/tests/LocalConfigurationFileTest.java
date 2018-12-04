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

import com.scalyr.api.knobs.ConfigurationFile;
import com.scalyr.api.knobs.ConfigurationFileFactory;
import com.scalyr.api.knobs.Knob;
import com.scalyr.api.knobs.LocalConfigurationFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import static org.junit.Assert.assertEquals;

/**
 * Tests for LocalParameterFile.
 */
public class LocalConfigurationFileTest {
  private File paramDir1, paramDir2;

  @Before public void setup() {
    paramDir1 = TestUtils.createTemporaryDirectory();
    paramDir2 = TestUtils.createTemporaryDirectory();
  }

  @After public void teardown() {
    LocalConfigurationFile.useLastKnownGoodJson = true;

    TestUtils.recursiveDelete(paramDir1);
    TestUtils.recursiveDelete(paramDir2);
  }

  @Test public void simpleTest() throws IOException, InterruptedException {
    LocalConfigurationFile.useLastKnownGoodJson = false;

    createOrUpdateFile(paramDir1, "foo", "{\"x\": 37}");
    createOrUpdateFile(paramDir1, "bar", "{}");

    ConfigurationFileFactory factory1 = ConfigurationFile.makeLocalFileFactory(paramDir1, 100);
    ConfigurationFileFactory factory2 = ConfigurationFile.makeLocalFileFactory(paramDir2, 100);

    // Test reading some values from a file that exists, and a file that doesn't.

    assertEquals((Integer)37, Knob.getInteger("x", null, factory1.getFile("/foo")));
    assertEquals((Long)37L, Knob.getLong("x", null, factory1.getFile("/foo")));
    assertEquals((Double)37D, Knob.getDouble("x", null, factory1.getFile("/foo")));

    assertEquals((Integer)15, Knob.getInteger("y", (Integer)15, factory1.getFile("/foo")));
    assertEquals((Integer)15, Knob.getInteger("y", (Integer)15, factory1.getFile("/bar")));
    assertEquals((Integer)15, Knob.getInteger("y", (Integer)15, factory1.getFile("/baz")));
    assertEquals((Integer)15, Knob.getInteger("x", (Integer)15, factory2.getFile("/foo")));

    // Verify that file modification, creation, and deletion are all detected.
    createOrUpdateFile(paramDir2, "foo", "{\"x\": 20}"); // create a file
    createOrUpdateFile(paramDir1, "bar", "{\"y\": 25}"); // modify a file
    createOrUpdateFile(paramDir1, "foo", null         ); // delete a file

    // Pause long enough for the change to be detected
    Thread.sleep(500);

    assertEquals(null, Knob.getInteger("x", null, factory1.getFile("/foo")));
    assertEquals(null, Knob.getLong("x", null, factory1.getFile("/foo")));
    assertEquals(null, Knob.getDouble("x", null, factory1.getFile("/foo")));

    assertEquals((Integer)15, Knob.getInteger("y", (Integer)15, factory1.getFile("/foo")));
    assertEquals((Integer)25, Knob.getInteger("y", (Integer)15, factory1.getFile("/bar")));
    assertEquals((Integer)15, Knob.getInteger("y", (Integer)15, factory1.getFile("/baz")));
    assertEquals((Integer)20, Knob.getInteger("x", (Integer)15, factory2.getFile("/foo")));
  }

  @Test public void testUsingLastKnownGoodState() throws IOException, InterruptedException {
    // Create a simple file.
    createOrUpdateFile(paramDir1, "foo", "{\"x\": 10}");
    ConfigurationFileFactory factory1 = ConfigurationFile.makeLocalFileFactory(paramDir1, 100);
    assertEquals((Integer)10, Knob.getInteger("x", null, factory1.getFile("/foo")));

    // Introduce a syntax error (missing colon). Verify that the previous state is retained.
    createOrUpdateFile(paramDir1, "foo", "{\"x\" 11}");

    // Pause long enough for the change to be detected
    Thread.sleep(500);

    assertEquals((Integer)10, Knob.getInteger("x", null, factory1.getFile("/foo")));

    // Make a valid change to the file, and verify that this is detected.
    createOrUpdateFile(paramDir1, "foo", "{\"x\": 12}");
    Thread.sleep(500);
    assertEquals((Integer)12, Knob.getInteger("x", null, factory1.getFile("/foo")));
  }

  public static void createOrUpdateFile(File dir, String path, String content) throws IOException {
    File file = new File(dir, path);

    if (file.exists())
        file.delete();

    if (content != null) {
      FileOutputStream output = new FileOutputStream(file, false);
      OutputStreamWriter writer = new OutputStreamWriter(output);
      writer.write(content);
      writer.flush();
      writer.close();
    }
  }
}
