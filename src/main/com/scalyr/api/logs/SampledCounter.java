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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Subclass of Gauge which maintains a running total. Sample usage:
 *
 * <pre>
 *   SampledCounter counter = new SampledCounter(new EventAttributes("tag", "foo"));
 *
 *   ...
 *
 *   counter.increment();
 * </pre>
 *
 * @deprecated all of the functionality of this class is available in CounterGauge.
 */
@Deprecated
public class SampledCounter extends Gauge {
  /**
   * Holds the current counter value. The value is actually a double; it is stored here in
   * Double.doubleToLongBits format. (We make do with this clunky approach because Java does not
   * provide an AtomicDouble class.)
   */
  private AtomicLong value;

  /**
   * Create a SampledCounter, and register it with the given attributes.
   */
  public SampledCounter(EventAttributes attributes) {
    this(attributes, 0);
  }

  /**
   * Create a SampledCounter, and register it with the given attributes.
   */
  public SampledCounter(EventAttributes attributes, double initialValue) {
    value = new AtomicLong(Double.doubleToLongBits(initialValue));

    Gauge.register(this, attributes);
  }

  /**
   * Return the current counter value.
   */
  public double getValue() {
    return Double.longBitsToDouble(value.get());
  }

  /**
   * Set the current counter value. Note that is not an increment operation; it overwrites
   * any current value.
   */
  public void setValue(double newValue) {
    value.set(Double.doubleToLongBits(newValue));
  }

  /**
   * Increment the counter value by 1. Return the new value.
   */
  public double increment() {
    return increment(1.0);
  }

  /**
   * Increment the counter value by the specified delta. Return the new value.
   */
  public double increment(double delta) {
    while (true) {
      long currentLong = value.get();
      double newValue = Double.longBitsToDouble(currentLong) + delta;
      if (value.compareAndSet(currentLong, Double.doubleToLongBits(newValue)))
        return newValue;
    }
  }

  @Override public Object sample() {
    return getValue();
  }

}
