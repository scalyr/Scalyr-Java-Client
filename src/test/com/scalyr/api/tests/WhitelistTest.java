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

import com.scalyr.api.json.JSONArray;
import com.scalyr.api.knobs.util.MockConfigurationFile;
import com.scalyr.api.knobs.util.Whitelist;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.Assert.*;

/**
 * Tests for Whitelist.
 */
public class WhitelistTest extends KnobTestBase {
  /**
   * Basic test of parsing and update handling.
   */
  @Test public void test() {
    // Begin with an empty file.
    MockConfigurationFile configFile = new MockConfigurationFile("/foo");
    configFile.setContent("");
    Whitelist whitelist = new Whitelist("list", null, configFile);

    assertFalse(whitelist.isInWhitelist("one"));
    assertFalse(whitelist.isInWhitelist("two"));

    // Now specify a value.
    configFile.setContent("{list: [\"one\", \"three\", \"five\"]}");
    assertTrue (whitelist.isInWhitelist("one"));
    assertFalse(whitelist.isInWhitelist("two"));
    assertTrue (whitelist.isInWhitelist("three"));
    assertFalse(whitelist.isInWhitelist("four"));
    assertTrue (whitelist.isInWhitelist("five"));

    // Now specify a value.
    configFile.setContent("{list: \" one, three ,five \"}");
    assertTrue (whitelist.isInWhitelist("one"));
    assertFalse(whitelist.isInWhitelist("two"));
    assertTrue (whitelist.isInWhitelist("three"));
    assertFalse(whitelist.isInWhitelist("four"));
    assertTrue (whitelist.isInWhitelist("five"));

    // Update with a different value.
    configFile.setContent("{list: \"two, three\"}");
    assertFalse(whitelist.isInWhitelist("one"));
    assertTrue (whitelist.isInWhitelist("two"));
    assertTrue (whitelist.isInWhitelist("three"));
    assertFalse(whitelist.isInWhitelist("four"));
    assertFalse(whitelist.isInWhitelist("five"));

    // Accept non-string values as well
    configFile.setContent("{list: 222}");
    assertTrue(whitelist.isInWhitelist("222"));
    assertFalse(whitelist.isInWhitelist("333"));

    // Accept non-string values as well
    configFile.setContent("{list: true}");
    assertTrue(whitelist.isInWhitelist("true"));
    assertFalse(whitelist.isInWhitelist("false"));
  }

  @Test public void testJsonObjectInArray() {
    // Begin with an empty file.
    MockConfigurationFile configFile = new MockConfigurationFile("/foo");
    configFile.setContent("");
    Whitelist whitelist = new Whitelist("list", null, configFile);

    configFile.setContent("{list: [\"one\", {\"value\":\"three\", \"comment\":\"hi\"}, \"five\"]}");
    assertTrue (whitelist.isInWhitelist("one"));
    assertFalse(whitelist.isInWhitelist("two"));
    assertTrue(whitelist.isInWhitelist("three"));
    assertFalse(whitelist.isInWhitelist("four"));
    assertTrue(whitelist.isInWhitelist("five"));
  }

  @Test public void testJsonObjectInArrayWithObjectAsFirstElement() {
    // Begin with an empty file.
    MockConfigurationFile configFile = new MockConfigurationFile("/foo");
    configFile.setContent("");
    Whitelist whitelist = new Whitelist("list", null, configFile);

    configFile.setContent("{list: [{\"value\":\"three\", \"comment\":\"hi\"}, \"one\", \"five\"]}");
    assertTrue (whitelist.isInWhitelist("one"));
    assertFalse(whitelist.isInWhitelist("two"));
    assertTrue(whitelist.isInWhitelist("three"));
    assertFalse(whitelist.isInWhitelist("four"));
    assertTrue(whitelist.isInWhitelist("five"));
  }

  /**
   * Test wildcards and negation syntax (separately and together).
   */
  @Test public void testWildcardsAndNegationOnStrings() {
    // A wildcard matches any value
    test(true, "*", "");
    test(true, "*", "a");
    test(true, "*", "fee fie foe fum");

    // A negated wildcard does not match any value
    test(false, "^*", "");
    test(false, "^*", "a");
    test(false, "^*", "fee fie foe fum");

    // Whitespace around these symbols is ignored
    test(true, " *", "");
    test(true, "  *  ", "a");
    test(true, "*  ", "fee fie foe fum");
    test(false, " ^ * ", "");
    test(false, "  ^*   ", "a");
    test(false, "^ *  ", "fee fie foe fum");

    // Test negation with an explicit list of values
    test(true,  " ^  red, blue,green ", "yellow");
    test(false, " ^  red, blue,green ", "red");
    test(false, " ^  red, blue,green ", "blue");
    test(false, " ^  red, blue,green ", "green");
    test(true,  " ^  red, blue,green ", "");

    testAcceptsAll(true, "*");
    testAcceptsAll(true, "   *  ");
    testAcceptsAll(false, "^*");
    testAcceptsAll(false, " ^ * ");
    testAcceptsAll(false, "abc,def");

    testAcceptsNone(false, "*");
    testAcceptsNone(false, "   *  ");
    testAcceptsNone(true, "^*");
    testAcceptsNone(true, " ^ * ");
    testAcceptsNone(false, "abc,def");
  }


  /**
   * Test the same wildcards and negation syntax (separately and together), but
   * using the array syntax instead of comma-delimited strings.
   */
  @Test public void testWildcardsAndNegationOnArrays() {
    // A wildcard matches any value
    test(true, jsonStringArray("*"), "");
    test(true, jsonStringArray("*"), "a");
    test(true, jsonStringArray("*"), "fee fie foe fum");

    // A negated wildcard does not match any value
    test(false, jsonStringArray("^*"), "");
    test(false, jsonStringArray("^*"), "a");
    test(false, jsonStringArray("^*"), "fee fie foe fum");

    // Whitespace around these symbols is ignored
    test(true, jsonStringArray(" *"), "");
    test(true, jsonStringArray("  *  "), "a");
    test(true, jsonStringArray("*  "), "fee fie foe fum");
    test(false, jsonStringArray(" ^ * "), "");
    test(false, jsonStringArray("  ^*   "), "a");
    test(false, jsonStringArray("^ *  "), "fee fie foe fum");

    // Test negation with an explicit list of values
    test(true, jsonStringArray(" ^ ", " red", "blue", "green "), "yellow");
    test(false, jsonStringArray(" ^  red", "blue", "green "), "red");
    test(true, jsonStringArray(" ^  red", "blue", "green "), "blue");
    test(true, jsonStringArray(" ^  red", "blue", "green "), "^  red");
    test(false, jsonStringArray("^", "red", "blue", "green "), "green");
    test(false, jsonStringArray(" ^  red", "blue", "green "), "");

    testAcceptsAll(true, jsonStringArray("*"));
    testAcceptsAll(true, jsonStringArray("   *  "));
    testAcceptsAll(false, jsonStringArray("^*"));
    testAcceptsAll(false, jsonStringArray(" ^ * "));
    testAcceptsAll(false, jsonStringArray("abc", "def"));

    testAcceptsNone(false, jsonStringArray("*"));
    testAcceptsNone(false, jsonStringArray("   *  "));
    testAcceptsNone(true, jsonStringArray("^*"));
    testAcceptsNone(true, jsonStringArray(" ^ * "));
    testAcceptsNone(false, jsonStringArray("abc", "def"));
  }

  /**
   * Test wildcard and negation syntax on some arrays that include objects. What I wouldn't give for """ in Java.
   */
  @Test public void testWildcardsAndNegationOnArraysWithObjects() {
    test(false, "[' ^ ', {'value': 'three', 'comment': 'hi'}, ' red', 'blue', 'green ']".replace('\'', '"'), "three");
    test(true, "[' ^ ', {'value': 'three', 'comment': 'hi'}, ' red', 'blue', 'green ']".replace('\'', '"'), "four");
    test(false, ("[{'value': ' ^ ', 'comment': '# excluding all primary colors of light'}," +
            " {'value': 'red ', 'comment': 'hi'}," +
            " 'blue', 'green ']")
            .replace('\'', '"'), "blue");
    test(true, ("[{'value': ' ^ ', 'comment': '# excluding all primary colors of light'}," +
            " {'value': 'red ', 'comment': 'hi'}," +
            " 'blue', 'green ']")
            .replace('\'', '"'), "chartreuse");

    test(true, ("[{'value': ' * ', 'comment': '# everyone is welcome'}]").replace('\'', '"'), "chartreuse");
  }

  @Test
  public void testJsonStringArray() {
    String s = jsonStringArray("^", "red", "blue", "green");
    assertEquals(s, "[\"^\",\"red\",\"blue\",\"green\"]");
  }

  @Test
  public void testListToString() {
    String[] values = new String[] {"^", "red", "blue", "green"};
    String s = listToString(values);
    assertEquals(s, "[\"^\",\"red\",\"blue\",\"green\"]");
  }

  private String jsonStringArray(String... items) {
    return listToString(items);
  }

  private String listToString(String[] values) {
    JSONArray array = new JSONArray((Object[]) values);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      array.writeJSONBytes(bos);
      return bos.toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void test(boolean expected, String list, String value) {
    if (!list.startsWith("[")) list = '"' + list + '"';

    MockConfigurationFile configFile = new MockConfigurationFile("/foo");
    configFile.setContent("{list: " + list + "}");

    Whitelist whitelist = new Whitelist("list", null, configFile);
    assertEquals(expected, whitelist.isInWhitelist(value));
  }

  private void testAcceptsAll(boolean expected, String list) {
    if (!list.startsWith("[")) list = '"' + list + '"';

    MockConfigurationFile configFile = new MockConfigurationFile("/foo");
    configFile.setContent("{list: " + list + "}");

    Whitelist whitelist = new Whitelist("list", null, configFile);
    assertEquals(expected, whitelist.acceptsAll());
  }

  private void testAcceptsNone(boolean expected, String list) {
    if (!list.startsWith("[")) list = '"' + list + '"';

    MockConfigurationFile configFile = new MockConfigurationFile("/foo");
    configFile.setContent("{list: " + list + "}");

    Whitelist whitelist = new Whitelist("list", null, configFile);
    assertEquals(expected, whitelist.acceptsNone());
  }
}
