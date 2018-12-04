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

import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.json.JSONArray;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser;
import com.scalyr.api.json.JSONParser.ByteScanner;
import com.scalyr.api.json.JSONParser.JsonParseException;
import com.scalyr.api.json.JSONValue;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests for classes in com.scalyr.api.json.
 */
public class JsonTest {
  /**
   * Test JSON serialization and parsing.
   */
  @Test public void testParsing() throws IOException {
    JSONObject sub = new JSONObject();
    sub.put("x", 123);
    sub.put("y", "yyy");

    JSONArray ar = new JSONArray();
    ar.add(123);
    ar.add("abc");

    byte[] manyBytes = new byte[12345];
    for (int i = 0; i < manyBytes.length; i++)
      manyBytes[i] = (byte) (i % 999);

    JSONObject obj = new JSONObject();
    obj.put("foo", "hello");
    obj.put("bar", 123);
    obj.put("baz", -456.789);
    obj.put("a", true);
    obj.put("b", false);
    obj.put("c", null);
    obj.put("d", sub);
    obj.put("e", ar);
    obj.put("f", new byte[]{3,1,4,1,5,9});
    obj.put("g", manyBytes);

    verifyRoundtrip(obj);

    obj = new JSONObject();
    obj.put("x", "abc\u0000def");
    verifyRoundtrip(obj);

    obj = new JSONObject();
    obj.put("x", "\u0000");
    verifyRoundtrip(obj);

    obj = new JSONObject();
    obj.put("x", "\u0000\u0001\u3333\u7777\u9999");
    verifyRoundtrip(obj);

    obj = new JSONObject();
    obj.put("x", new JSONArray(null, null));
    verifyRoundtrip(obj);
  }

  /**
   * Test support for trailing commas in objects and arrays.
   */
  @Test public void testTrailingCommas() {
    verifyArray("[]");
    verifyArray("[ ]");
    verifyArray("[\"a\",]", "a");
    verifyArray("[\"a\", \"b\", ]", "a", "b");

    verifyObject("{}");
    verifyObject("{ }");
    verifyObject("{a: 123, }", "a", 123);
    verifyObject("{a:123,b:\"x\",}", "a", 123, "b", "x");
  }

  /**
   * Test support for `s string syntax.
   */
  @Test public void testLengthPrefixedStrings() {
    verifyObject("{a:`s\0\0\0\3abc,}", "a", "abc");
  }

  /**
   * Test support for triple quoted strings syntax.
   */
  @Test public void testTripleQuotedStrings() {
    verifyObject("{a:\"\"\"hi there\nbyte\"\"\",}", "a", "hi there\nbyte");
  }

  /**
   * Test this part of the HOCON spec:
   *
   * In Python, """foo"""" is a syntax error (a triple-quoted string followed by a dangling unbalanced
   * quote). In Scala, it is a four-character string foo". HOCON works like Scala; any sequence of at
   * least three quotes ends the multi-line string, and any "extra" quotes are part of the string.
   */
  @Test
  public void testTripleQuotesWithExtraQuotesAtEnd() {
    String json = "{'x': '''xyz'''''}".replace('\'', '"');
    verifyObject(json, "x", "xyz\"\"");
  }

  /**
   * Test this part of the HOCON spec:
   *
   * If the three-character sequence """ appears, then all Unicode characters until a closing """ sequence
   * are used unmodified to create a string value. Newlines and whitespace receive no special treatment.
   * Unlike Scala, and unlike JSON quoted strings, Unicode escapes are not interpreted in triple-quoted
   * strings.
   */
  @Test
  public void testTripleQuotesWithBackslashes() {
    String json = "{'x': '''\\n'''}".replace('\'', '"');
    verifyObject(json, "x", "\\n");
  }

  /**
   * Tests where the input is missing a comma, but the parser can infer the comma because of a line
   * break.
   */
  @Test public void testCommaInference() {
    testParser(
        new JSONObject().set("x", 123).set("y", 456),
        "{x: 123\ny: 456}");

    testParser(
        new JSONObject().set("x", "abc").set("y", "def"),
        "{x: $abc$\ny: $def$}");

    testParser(
        new JSONObject().set("ar", new JSONArray("x", "y", "z", "w")),
        "{ar: [$x$\n$y$,$z$,\n$w$]}");
  }

  /**
   * Verify that the given input gives expected result when parsed using JsonByteParser.
   * $ characters in the input string are interpreted as double-quotes.
   */
  private void testParser(JSONObject expected, String input) {
    input = input.replace("$", "\"");

    byte[] inputBytes = input.getBytes(ScalyrUtil.utf8);
    ByteScanner scanner = new ByteScanner(inputBytes);
    JSONObject byteParserOutput = (JSONObject) new JSONParser(scanner).parseValue();
    assertEquivalent(expected, byteParserOutput);
  }

  private void verifyArray(String input, Object ... expecteds) {
    JSONArray expectedArray = new JSONArray();
    for (Object expected : expecteds)
      expectedArray.add(expected);

    assertEquivalent(expectedArray, new JSONParser(new ByteScanner(input.getBytes())).parseValue());
  }

  private void verifyObject(String input, Object ... expectedKeysAndValues) {
    JSONObject expectedObject = new JSONObject();
    for (int i = 0; i < expectedKeysAndValues.length; i += 2)
      expectedObject.put((String) expectedKeysAndValues[i], expectedKeysAndValues[i+1]);

    assertEquivalent(expectedObject, new JSONParser(new ByteScanner(input.getBytes())).parseValue());
  }

  /**
   * Serialize and deserialize the given object, and verify that the result matches the original.
   */
  private void verifyRoundtrip(JSONObject input) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    JSONValue.writeJSONBytes(input, buffer);
    byte[] serialized = buffer.toByteArray();

    JSONObject output = (JSONObject) new JSONParser(new ByteScanner(serialized)).parseValue();
    assertEquivalent(input, output);
  }

  private void assertEquivalent(Object expected, Object actual) {
    if (expected instanceof JSONObject) {
      JSONObject a = (JSONObject) expected, b = (JSONObject) actual;
      for (Map.Entry<String, Object> entryA : a.entrySet()) {
        String key = entryA.getKey();
        Object valueA = entryA.getValue();
        Object valueB = b.get(key);
        assertEquivalent(valueA, valueB);
      }
      assertEquals(a.size(), b.size());
    } else if (expected instanceof JSONArray) {
      JSONArray a = (JSONArray) expected, b = (JSONArray) actual;
      for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
        Object valueA = a.get(i);
        Object valueB = b.get(i);
        assertEquivalent(valueA, valueB);
      }
      assertEquals(a.size(), b.size());
    } else if (expected instanceof Integer || expected instanceof Long || expected instanceof Double) {
      double a = toDouble(expected), b = toDouble(actual);
      assertEquals(a, b, 1E-10);
    } else if (expected instanceof byte[]) {
      byte[] a = (byte[]) expected, b = (byte[]) actual;
      assertEquals(a.length, b.length);
      for (int i = 0; i < a.length; i++)
        assertEquals(a[i], b[i]);
    } else {
      assertEquals(expected, actual);
    }
  }

  private double toDouble(Object value) {
    if (value instanceof Integer)
      return (int)(Integer)value;
    else if (value instanceof Long)
      return (long)(Long)value;
    else
      return (Double)value;
  }

  @Test public void testLineNumbers() {
    assertEquals(1, JSONParser.lineNumberForBytePos(new byte[0], 0));
    assertEquals(1, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 0));
    assertEquals(1, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 3));
    assertEquals(2, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 4));
    assertEquals(2, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 7));
    assertEquals(3, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 8));
    assertEquals(3, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 11));
    assertEquals(4, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 12));
    assertEquals(4, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 13));
    assertEquals(4, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL"), 16));
    assertEquals(5, JSONParser.lineNumberForBytePos(b("ABC\nDEF\rGHI\r\nJKL\r"), 17));

    try {
      JSONParser.parse(
          "{\n"
        + "  foo: \"abc\",\n"
        + "  bar: abc\n" // missing quote
        + "}"
      );
      fail("JsonParseException expected");
    } catch (JsonParseException ex) {
      assertEquals("Unexpected character 'a' (line 3, byte position 23)", ex.getMessage());
    }
  }

  private static byte[] b(String s) {
    return s.getBytes(ScalyrUtil.utf8);
  }
}
