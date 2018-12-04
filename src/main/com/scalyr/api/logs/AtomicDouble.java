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

package com.scalyr.api.logs;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * Encapsulates a double and allows atomic incrementing. Similar to AtomicLong, though we don't bother
 * to implement the complete functionality.
 */
public class AtomicDouble {
  private volatile long bits;

  private static final AtomicLongFieldUpdater<AtomicDouble> updater =
      AtomicLongFieldUpdater.newUpdater(AtomicDouble.class, "bits");

  public AtomicDouble() {
    this(0.0);
  }

  public AtomicDouble(double initialValue) {
    bits = Double.doubleToRawLongBits(initialValue);
  }

  public double get() {
    return Double.longBitsToDouble(bits);
  }

  public void set(double newValue) {
    bits = Double.doubleToRawLongBits(newValue);
  }

  public double add(double delta) {
    while (true) {
      long currentBits = bits;
      double currentValue = Double.longBitsToDouble(currentBits);
      double newValue = currentValue + delta;
      if (updater.compareAndSet(this, currentBits, Double.doubleToRawLongBits(newValue))) {
        return newValue;
      }
    }
  }
}
