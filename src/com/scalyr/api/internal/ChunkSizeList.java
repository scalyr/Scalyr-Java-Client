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

import java.util.ArrayList;
import java.util.List;

/**
 * This class tracks the size of a series of data chunks. It is used to
 * organize a circular buffer into chunks of bounded size.
 */
public class ChunkSizeList {
  /**
   * Defines a series of chunks in pendingEventBuffer.
   */
  private final List<MutableInt> chunkSizes = new ArrayList<MutableInt>();

  /**
   * True if the first entry in chunkSizes can no longer be incremented,
   * due to a call to closeFirst().
   */
  private boolean firstIsClosed;

  /**
   * Return the size of the first (oldest) chunk. If there are no chunks, return 0.
   */
  public int getFirst() {
    if (chunkSizes.size() == 0)
      return 0;

    return chunkSizes.get(0).value;
  }

  /**
   * Prevent the first (oldest) chunk from being further incremented. The next
   * append() call will start a new chunk.
   */
  public void closeFirst() {
    firstIsClosed = true;
  }

  /**
   * Remove the first (oldest) chunk.
   */
  public void removeFirst() {
    chunkSizes.remove(0);
    firstIsClosed = false;
  }

  /**
   * Add size to the last (newest) chunk. If there are no chunks, or the chunk would
   * exceed maxChunkSize, then start a new chunk of the given size.
   */
  public void append(int size, int maxChunkSize) {
    ScalyrUtil.Assert(size > 0, "event upload chunk has zero or negative size (" + size + ")");

    int chunkCount = chunkSizes.size();
    if (chunkCount == 0 || (chunkCount == 1 && firstIsClosed)) {
      chunkSizes.add(new MutableInt(size));
    } else {
      MutableInt last = chunkSizes.get(chunkSizes.size() - 1);
      if (last.value + size > maxChunkSize) {
        chunkSizes.add(new MutableInt(size));
      } else {
        last.value += size;
      }
    }
  }

  private static class MutableInt {
    public int value;

    public MutableInt(int value) {
      this.value = value;
    }
  }
}
