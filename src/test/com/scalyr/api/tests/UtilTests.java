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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.Test;

import com.scalyr.api.Converter;
import com.scalyr.api.internal.ChunkSizeList;
import com.scalyr.api.internal.CircularByteArray;
import com.scalyr.api.internal.ScalyrUtil;
import com.scalyr.api.internal.Tuple;
import com.scalyr.api.json.JSONObject;
import com.scalyr.api.json.JSONParser;

/**
 * Tests for classes in com.scalyr.util.
 */
public class UtilTests {
  /**
   * Test ScalyrUtil.equals().
   */
  @Test public void testEquals() {
    assertTrue(ScalyrUtil.equals(null, null));
    assertTrue(ScalyrUtil.equals(3, 3));
    assertTrue(ScalyrUtil.equals("foo", "foo"));

    assertFalse(ScalyrUtil.equals(null, "foo"));
    assertFalse(ScalyrUtil.equals("foo", null));
    assertFalse(ScalyrUtil.equals("foo", "bar"));
  }

  /**
   * Test Converter.toLong().
   */
  @Test public void testConvertToLong() {
    JSONObject obj = (JSONObject) JSONParser.parse("{\"a\": 37, \"b\": -199.0, \"c\": 123456789012}");

    assertEquals(37L, (long) Converter.toLong(obj.get("a")));
    assertEquals(-199L, (long) Converter.toLong(obj.get("b")));
    assertEquals(123456789012L, (long) Converter.toLong(obj.get("c")));
  }

  /**
   * Test the Tuple class.
   */
  @Test public void testTuple() {
    Tuple[] tuples = new Tuple[]{
        new Tuple(1, 2, "buckle my shoe"),
        new Tuple(1, 2, "buckle my shoe"),
        new Tuple("foo", null, "bar"),
        new Tuple("foo", null, "bar"),
        new Tuple(null, "foo", "bar"),
        new Tuple("foo", "baz", "bar"),
        new Tuple(),
        new Tuple()
    };

    for (int i = 0; i < tuples.length; i++)
      for (int j = 0; j < tuples.length; j++) {
        if (i == j
            || (i == 0 && j == 1) || (i == 1 && j == 0)
            || (i == 2 && j == 3) || (i == 3 && j == 2)
            || (i == 6 && j == 7) || (i == 7 && j == 6)) {
          assertTrue(tuples[i].equals(tuples[j]));
        } else {
          assertFalse("i = " + i + ", j = " + j, tuples[i].equals(tuples[j]));
          assertFalse(tuples[i].hashCode() == tuples[j].hashCode());
        }
      }
  }

  /**
   * Test the CircularByteArray class.
   */
  @Test public void testCircularByteArray() throws IOException {
    CircularByteArray buffer = new CircularByteArray(10);

    // Add four bytes.
    addSuccess(buffer, 5, 10, 15, 20);

    // Another three bytes.
    addSuccess(buffer, 25, 30, 35);

    // Attempt to add four more bytes; this will fail.
    addFail(buffer, 4);

    // Extract the current buffer contents.
    testBufferContents(buffer, 5, 10, 15, 20, 25, 30, 35);

    // Verify that the buffer is now empty.
    assertEquals(0, buffer.numBufferedBytes());

    // Append eight bytes; it will wrap around after the first three bytes.
    addSuccess(buffer, 100, 101, 102, 103, 104, 105, 106, 107);

    // Append two more bytes, precisely filling the buffer.
    addSuccess(buffer, 108, 109);
    addFail(buffer, 1);

    // Extract the buffer contents one final time.
    testBufferContents(buffer, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109);
  }

  /**
   * Test the reserveLength parameter to CircularByteArray.append().
   */
  @Test public void testCircularByteArrayReservations() throws IOException {
    CircularByteArray buffer = new CircularByteArray(10);

    // Add four bytes.
    addSuccess(buffer, 5, 10, 15, 20);

    // Another three bytes -- fails due to reservation.
    addFailWithReservation(buffer, 5, 3);

    // Without the reservation, we can successfully add three bytes.
    addSuccessWithReservation(buffer, 0, 1, 2, 3);

    // Extract the current buffer contents.
    testBufferContents(buffer, 5, 10, 15, 20, 1, 2, 3);

    // Verify that the buffer is now empty.
    assertEquals(0, buffer.numBufferedBytes());
  }

  /**
   * Multithreaded tests of CircularByteArray.
   */
  @Test public void multithreadedCircularByteArrayTest() {
    Random rand = new Random(123);

    // Run for one second with two threads writing to the buffer, and one thread
    // reading, all wth randomized parameters.
    CircularByteArray buffer = new CircularByteArray(20);
    WriterThread writer1 = new WriterThread(buffer, 456);
    writer1.start();

    WriterThread writer2 = new WriterThread(buffer, 789);
    writer2.start();

    long startTimeMs = System.currentTimeMillis();
    while (System.currentTimeMillis() < startTimeMs + 1000) {
      for (int iterations = 0; iterations < 1000; iterations++) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
          int count = buffer.numBufferedBytes();
          buffer.writeOldestBytes(stream, count);
          buffer.discardOldestBytes(count);
        } catch (IOException ex) {
          ex.printStackTrace();
        }

        verifyBatch(stream.toByteArray());
      }
    }
  }

  private void verifyBatch(byte[] bytes) {
    for (int pos = 0; pos < bytes.length; ) {
      int n = bytes[pos] & 255;
      for (int i = 0; i < n - 100; i++) {
        assertEquals(n, bytes[pos + i] & 255);
      }

      pos += n - 100;
    }
  }

  private static class WriterThread extends Thread {
    private final CircularByteArray circularBuffer;
    private final Random rand;
    public volatile boolean halt = false;

    public WriterThread(CircularByteArray circularBuffer, int seed) {
      this.circularBuffer = circularBuffer;
      this.rand = new Random(seed);
    }

    @Override public void run() {
      byte[] buffer = new byte[20];

      while (!halt) {
        int length = rand.nextInt(10) + 1;
        int padding = rand.nextInt(5);
        for (int i = 0; i < buffer.length; i++)
          buffer[i] = (byte) 255;

        for (int i = 0; i < length; i++)
          buffer[i + padding] = (byte) (length + 100);

        circularBuffer.append(buffer, padding, length);
      }
    }
  }

  /**
   * Append the given bytes, and assert that the operation succeeds.
   */
  private void addSuccess(CircularByteArray buffer, int ... bytes) {
    addSuccessWithReservation(buffer, 0, bytes);
  }

  private void addSuccessWithReservation(CircularByteArray buffer, int reserveLength, int ... bytes) {
    int N = bytes.length;

    // Copy the bytes into an array. Include a pad byte at the front and a couple at the end,
    // to test offset handling.
    byte[] temp = new byte[N + 3];
    temp[0] = 123;
    for (int i = 0; i < N; i++)
      temp[i + 1] = (byte) bytes[i];
    temp[N + 1] = 124;
    temp[N + 2] = 125;

    assertTrue(buffer.append(temp, 1, N, reserveLength));
  }

  /**
   * Attempt to append the given bytes, and assert that the operation fails.
   */
  private void addFail(CircularByteArray buffer, int count) {
    addFailWithReservation(buffer, 0, count);
  }

  private void addFailWithReservation(CircularByteArray buffer, int reserveLength, int count) {
    assertFalse(buffer.append(new byte[count], 0, count, reserveLength));
  }

  /**
   * Verify that the buffer contains the specified bytes, and remove them.
   */
  private void testBufferContents(CircularByteArray buffer, int ... expected) throws IOException {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    assertEquals(expected.length, buffer.numBufferedBytes());

    buffer.writeOldestBytes(stream, expected.length);
    buffer.discardOldestBytes(expected.length);

    byte[] actual = stream.toByteArray();
    assertEquals(expected.length, actual.length);

    for (int i = 0; i < actual.length; i++)
      assertEquals(expected[i] & 255, actual[i] & 255);
  }

  /**
   * Test the ChunkSizeList class.
   */
  @Test public void testChunkSizeList() {
    ChunkSizeList list = new ChunkSizeList();

    // Check an empty list.
    assertEquals(0, list.getFirst());

    // Add a couple of chunks. Outgoing state: [15]
    list.append(10, 20);
    list.append(5, 20);
    assertEquals(15, list.getFirst());

    // Add a third chunk that won't fit with the first two. Outgoing state: [15, 10]
    list.append(10, 20);
    assertEquals(15, list.getFirst());

    // Remove the first chunk. Outgoing state: [10]
    list.removeFirst();
    assertEquals(10, list.getFirst());

    // Remove the remaining chunk. Outgoing state: []
    list.removeFirst();
    assertEquals(0, list.getFirst());
  }

  /**
   * Test ChunkSizeList.closeFirst().
   */
  @Test public void testCloseChunk() {
    ChunkSizeList list = new ChunkSizeList();

    list.append(3, 10);
    list.closeFirst();
    list.append(3, 10);
    list.append(3, 10);
    list.append(3, 10);
    list.append(3, 10);
    list.append(3, 10);

    // We should now have chunks of size 3, 9, and 6.
    assertEquals(3, list.getFirst());
    list.removeFirst();

    assertEquals(9, list.getFirst());
    list.removeFirst();

    assertEquals(6, list.getFirst());
    list.removeFirst();

    assertEquals(0, list.getFirst());
  }
}
