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

package com.scalyr.api.internal;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Implements a circular buffer for buffering log data. Designed to minimize blocking.
 */
public class CircularByteArray {
  /**
   * The physical buffer.
   */
  private final byte[] rawBuffer;

  /**
   * Index, in buffer, of the first (oldest) value. Undefined if count is 0. Ranges
   * from 0 to buffer.length - 1.
   */
  private int bufferStart;

  /**
   * Number of buffered bytes.
   */
  private int numBufferedBytes;

  /**
   * Construct a buffer of the given capacity.
   */
  public CircularByteArray(int capacity) {
    rawBuffer = new byte[capacity];
  }

  /**
   * Add the given data to the buffer, and return true. If the data will not fit in
   * its entirely, do nothing (don't add a fragment) and return false.
   */
  public boolean append(byte[] data) {
    return append(data, 0, data.length);
  }

  /**
   * Add length bytes, beginning at data[offset], to the buffer, and return true.
   * If the data will not fit in its entirety, do nothing (don't add a fragment)
   * and return false.
   */
  public boolean append(byte[] newData, int offsetInNewData, int newDataLength) {
    return append(newData, offsetInNewData, newDataLength, 0);
  }

  /**
   * Add length bytes, beginning at data[offset], to the buffer, and return true.
   * If the data will not fit in its entirely, with reserveLength bytes left over,
   * then do nothing (don't add a fragment) and return false.
   */
  public synchronized boolean append(byte[] newData, int offsetInNewData, int newDataLength,
      int reserveLength) {
    int spaceAvailable = rawBuffer.length - numBufferedBytes - reserveLength;
    if (spaceAvailable < newDataLength)
      return false;

    // Add as much data as will fit before hitting the wraparound point.
    if (numBufferedBytes == 0)
      bufferStart = 0;

    int bufferEnd = (bufferStart + numBufferedBytes) % rawBuffer.length;
    int chunk1Length = Math.min(newDataLength, rawBuffer.length - bufferEnd);
    System.arraycopy(newData, offsetInNewData, rawBuffer, bufferEnd, chunk1Length);
    offsetInNewData += chunk1Length;
    newDataLength -= chunk1Length;

    bufferEnd = (bufferEnd + chunk1Length) % rawBuffer.length;
    numBufferedBytes += chunk1Length;

    // If we didn't get everything in the first pass, add the rest now.
    if (newDataLength > 0) {
      ScalyrUtil.Assert(bufferEnd == 0, "CircularByteArray confused about buffer position");
      ScalyrUtil.Assert(newDataLength <= bufferStart, "CircularByteArray confused about buffer capacity");

      System.arraycopy(newData, offsetInNewData, rawBuffer, bufferEnd, newDataLength);
      numBufferedBytes += newDataLength;
    }

    return true;
  }

  /**
   * Return the number of bytes of data currently in the buffer.
   */
  public synchronized int numBufferedBytes() {
    return numBufferedBytes;
  }

  /**
   * Discard (truncate) the specified number of bytes from beginning (oldest portion)
   * of the buffer. If count is larger than the number of bytes in the buffer, throw
   * an exception.
   */
  public synchronized void discardOldestBytes(int count) {
    if (count > numBufferedBytes)
      throw new RuntimeException("Attempting to discard " + count + " bytes from a buffer which contains only " + numBufferedBytes);

    bufferStart = (bufferStart + count) % rawBuffer.length;
    numBufferedBytes -= count;
  }

  /**
   * Copy the specified number of bytes from the beginning (oldest portion) of the buffer
   * to the stream. If count is larger than the number of bytes in the buffer, throw
   * an exception.
   *
   * This operation does not modify or advance the buffer, it merely copies data.
   *
   * writeOldestBytes should not be called concurrently with discardldestBytes.
   */
  public void writeOldestBytes(OutputStream out, int count) throws IOException {
    // Snapshot the current buffer position and length.
    int bytesToWrite;
    int startPos;
    synchronized (this) {
      if (count > numBufferedBytes)
        throw new RuntimeException("Attempting to write " + count + " bytes from a buffer which contains only " + numBufferedBytes);

      bytesToWrite = count;
      startPos = bufferStart;
    }

    // Output the data.
    if (bytesToWrite > 0) {
      int chunk1Length = Math.min(bytesToWrite, rawBuffer.length - startPos);
      out.write(rawBuffer, startPos, chunk1Length);
      if (chunk1Length < bytesToWrite)
        out.write(rawBuffer, 0, bytesToWrite - chunk1Length);
    }
  }
}
