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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.Random;

import com.scalyr.api.Converter;
import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONValue;
import com.scalyr.api.json.RawJson;

/**
 * Utilities used in test code.
 */
public class TestUtils {
  /**
   * Verify that the two JSON objects are equivalent.
   */
  public static void assertEquivalent(Object expected, Object actual) {
    assertEquivalent("/", expected, actual);
  }

  /**
   * Verify that the two JSON objects are equivalent.
   */
  public static void assertEquivalent(String path, Object expected, Object actual) {
    // If "actual" is RawJson, parse it so we can compare properly.
    if (actual instanceof RawJson) {
      String text = JSONValue.toJSONString(actual);
      JsonParserForTests parser = new JsonParserForTests(text);
      actual = parser.parse();
    }

    if (expected == null) {
      assertNull(path, actual);
    } else if ("$ANY$".equals(expected)) {
      return;
    } else if (expected instanceof Integer) {
      assertEquals(path, (long)(Integer)expected, (long) Converter.toLong(actual));
    } else if (expected instanceof Long) {
      assertEquals(path, (long)(Long)expected, (long) Converter.toLong(actual));
    } else if (expected instanceof Double) {
      assertTrue(path, actual instanceof Double);
      assertEquals(path, (double)(Double)expected, (double)(Double)actual, 1E-6);
    } else if (expected instanceof String) {
      assertEquals(path, expected, actual);
    } else if (expected instanceof Boolean) {
      assertTrue(path, actual instanceof Boolean);
      assertEquals(path, (Boolean)expected, (Boolean)actual);
    } else if (expected instanceof JSONArray) {
      assertTrue(path, actual instanceof JSONArray);
      JSONArray a = (JSONArray) expected;
      JSONArray b = (JSONArray) actual;
      if (a.size() != b.size())
        assertEquals(path, a.size(), b.size());
      for (int i = 0; i < a.size(); i++)
        assertEquivalent(path + "[" + i + "]", a.get(i), b.get(i));
    } else if (expected instanceof JSONObject) {
      assertTrue(path, actual instanceof JSONObject);
      JSONObject a = (JSONObject) expected;
      JSONObject b = (JSONObject) actual;
      for (Object key : a.keySet()) {
        assertEquivalent(path + "." + key, a.get(key), b.get(key));
        assertTrue(path + "." + key, b.containsKey(key));
      }
      for (Object key : b.keySet()) {
        assertEquivalent(path + "." + key, a.get(key), b.get(key));
        assertTrue(path + "." + key, a.containsKey(key));
      }
    } else {
      fail("unknown object type @ [" + path + "]: " + expected);
    }
  }

  /**
   * Create a new directory under the system's temporary directory.
   */
  public static File createTemporaryDirectory() {
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    Random r = new Random();
    while (true) {
      File childDir = new File(tempDir, "temp_" + r.nextLong());
      if (childDir.mkdir())
        return childDir;
    }
  }

  /**
   * Delete a file or directory, and (if a directory) all files and subdirectories
   * inside it. Does not follow symbolic links.
   *
   * Failures are not reported.
   */
  public static void recursiveDelete(File file) {
    // Canonical-path check weeds out symbolic links.
    try {
      if (file.isDirectory() && file.getCanonicalPath().equals(file.getAbsolutePath())) {
        File[] children = file.listFiles();
        if (children != null)
          for (File child : children)
            recursiveDelete(child);
      }
    } catch (IOException ex) {
      // Ignore errors.
    }

    file.delete();
  }
}
