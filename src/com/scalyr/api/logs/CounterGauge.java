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

import java.util.HashMap;
import java.util.Map;

import com.scalyr.api.internal.ScalyrUtil;


/**
 * A counter which records its value to Scalyr Logs at regular intervals.
 */
public class CounterGauge extends Gauge {
  /**
   * The current value of the counter.
   */
  private final AtomicDouble value = new AtomicDouble();
  
  private final DeltaMode deltaMode;
  
  private final String deltaAttributeName;
  private final Object deltaAttributeValue;
  
  /**
   * Value of the counter as of the last call to recordValue(), or undefined if recordValue() has never
   * been called.
   */
  private double lastRecordedValue;
  
  /**
   * Nanosecond clock as of the last call to recordValue(), or -1 if recordValue() has never been called.
   */
  private long lastRecordTimestamp = -1;
  
  /**
   * Create a CounterGauge, and register it under the specified attributes.
   */
  public CounterGauge(EventAttributes attributes) {
    this(attributes, 0);
  }
  
  /**
   * Create a CounterGauge with the specified value, and register it under the specified attributes.
   */
  public CounterGauge(EventAttributes attributes, double initialValue) {
    this(attributes, initialValue, DeltaMode.valueOnly, null, null);
  }
  
  /**
   * Create a CounterGauge with the specified value, and register it under the specified attributes.
   * 
   * @param attributes Attributes to attach to the counter value in the log.
   * @param initialValue Initial value for the counter.
   * @param deltaMode Whether to record the absolute counter value, the rate-per-second at which the
   *     counter has increased, or both.
   * @param deltaAttributeName Used only for DeltaMode.valueAndDeltaRate. Specifies the name of an extra
   *     log attribute to add to the EventAttributes when logging the rate-per-second. Allows the rate-per-second
   *     to be distinguished from the raw counter value in the log.
   * @param deltaAttributeValue Used only for DeltaMode.valueAndDeltaRate. Specifies the value to use
   *     for deltaAttributeName when logging the rate-per-second.
   */
  public CounterGauge(EventAttributes attributes, double initialValue, DeltaMode deltaMode,
      String deltaAttributeName, Object deltaAttributeValue) {
    value.set(initialValue);
    Gauge.register(this, attributes);
    
    this.deltaMode = deltaMode;
    this.deltaAttributeName = deltaAttributeName;
    this.deltaAttributeValue = deltaAttributeValue;
  }

  /** Flyweight convenience class to manage inc/dec via try-with-resources. */
  public static class IncDec implements AutoCloseable {
    private final EventAttributes attributes;
    private final DeltaMode deltaMode;
    public IncDec(EventAttributes attributes, DeltaMode deltaMode) {
      this.attributes = attributes;
      this.deltaMode  = deltaMode;
      CounterGauge.increment(attributes, +1, deltaMode);
    }
    public void close() {
      CounterGauge.increment(attributes, -1, deltaMode);
    }
  }

  /**
   * This enum is used to specify whether a CounterGauge should record its value, the rate-per-second at
   * which the value has increased, or both.
   */
  public static enum DeltaMode {
    valueOnly,
    deltaRateOnly,
    valueAndDeltaRate
  }
  
  /**
   * Return the current counter value.
   */
  public double getValue() {
    return value.get();
  }
  
  /**
   * Set the current counter value, overwriting the previous value. To increment the value,
   * call increment().
   */
  public void setValue(double x) {
    value.set(x);
  }
  
  /**
   * Increment the counter by one, and return the new counter value.
   */
  public double increment() {
    return increment(1.0);
  }
  
  /**
   * Increment the counter by the specified delta, and return the new counter value.
   */
  public double increment(double delta) {
    return value.add(delta);
  }

  /**
   * Time the amount of of nano seconds spent by the incoming Runnable, store the nano seconds to counter value, then return the new counter value.
   */
  public double recordNanos(Runnable runnable) {
    long startNano = ScalyrUtil.nanoTime();
    runnable.run();
    return value.add(ScalyrUtil.nanoTime() - startNano);
  }

  /**
   * Implementation of Gauge.sample().
   */
  @Override public Object sample() {
    return value.get();
  }

  /**
   * We override this method to log delta values.
   */
  @Override public void recordValue(EventAttributes attributes) {
    double currentValue = value.get();
    
    if (deltaMode == DeltaMode.valueOnly || deltaMode == DeltaMode.valueAndDeltaRate) {
      // Record the current counter value
      EventAttributes attributes_ = new EventAttributes(attributes);
      attributes_.put("value", currentValue);
      Events.info(attributes_);
    }
    
    if (deltaMode == DeltaMode.deltaRateOnly || deltaMode == DeltaMode.valueAndDeltaRate) {
      // Record the rate-per-second of counter growth since the previous call to recordValue().
      long nowNs = ScalyrUtil.nanoTime();
      
      if (lastRecordTimestamp >= 0 && lastRecordTimestamp < nowNs) {
        double timeSpanSecs = (nowNs - lastRecordTimestamp) / (double) ScalyrUtil.NANOS_PER_SECOND;
        double ratePerSecond = (currentValue - lastRecordedValue) / timeSpanSecs;
        
        EventAttributes attributes_ = new EventAttributes(attributes);
        if (deltaMode == DeltaMode.valueAndDeltaRate && deltaAttributeName != null && deltaAttributeName.length() > 0) {
          attributes_.put(deltaAttributeName, deltaAttributeValue);
        }
        
        attributes_.put("value", ratePerSecond);
        Events.info(attributes_);
      }
      
      lastRecordedValue = currentValue;
      lastRecordTimestamp = nowNs;
    }
  }
  
  /**
   * All counters created by increment(EventAttributes, double).
   */
  private static Map<EventAttributes, CounterGauge> counterTable = new HashMap<EventAttributes, CounterGauge>();
  
  /**
   * Create a CounterGauge with the specified attributes. If this method (or setValue) has been called
   * previously with the same attributes, we re-use the same CounterGauge. Either way, we then increment the
   * counter by the specified delta.
   * <p>
   * This method allows you to record counters associated with variable attributes, without having
   * to explicitly maintain a set of CounterGauges. You should be careful to limit the number of
   * distinct attribute sets used, as each unique CounterGauge imposes a certain resource cost
   * (primarily the CPU and bandwidth cost of periodically logging its value). Beyond a few hundred
   * Gauges, you should start to keep an eye on the cost.
   */
  public static void increment(EventAttributes attributes, double delta) {
    increment(attributes, delta, DeltaMode.valueOnly);
  }
  
  /**
   * Create a CounterGauge with the specified attributes. If this method (or setValue) has been called
   * previously with the same attributes, we re-use the same CounterGauge. Either way, we then increment the
   * counter by the specified delta.
   * 
   * This method allows you to record counters associated with variable attributes, without having
   * to explicitly maintain a set of CounterGauges. You should be careful to limit the number of
   * distinct attribute sets used, as each unique CounterGauge imposes a certain resource cost
   * (primarily the CPU and bandwidth cost of periodically logging its value). Beyond a few hundred
   * Gauges, you should start to keep an eye on the cost.
   *
   * @param deltaMode if `valueOnly` or `deltaRateOnly`, then `attributes` is used as-is.  If `valueAndDeltaRate`,
   *   then we use "tag" and `attributes.get("tag") + "_rate"` as the `deltaAttribute{Name,Value}` pair.  (this means
   *   that, if `attributes` does not have a `"tag"` key, the delta will be logged with `"tag='_rate'"`).
   */
  public static void increment(EventAttributes attributes, double delta, DeltaMode deltaMode) {
    CounterGauge counter;
    
    synchronized (counterTable) {
      counter = counterTable.get(attributes);
      if (counter == null) {
        counter = new CounterGauge(attributes, 0, deltaMode, "tag", attributes.getOr("tag", "") + "_rate");
        counterTable.put(attributes, counter);
      }
    }
    
    counter.increment(delta);
  }
  
  /**
   * Create a CounterGauge with the specified attributes. If this method (or increment) has been called
   * previously with the same attributes, we re-use the same CounterGauge. Either way, we then set the
   * counter to the specified value.
   * <p>
   * This method allows you to record values associated with variable attributes, without having
   * to explicitly maintain a set of CounterGauges. You should be careful to limit the number of
   * distinct attribute sets used, as each unique CounterGauge imposes a certain resource cost
   * (primarily the CPU and bandwidth cost of periodically logging its value). Beyond a few hundred
   * Gauges, you should start to keep an eye on the cost.
   */
  public static void setStaticValue(EventAttributes attributes, double value) {
    CounterGauge counter;
    
    synchronized (counterTable) {
      counter = counterTable.get(attributes);
      if (counter == null) {
        counter = new CounterGauge(attributes);
        counterTable.put(attributes, counter);
      }
    }
    
    counter.setValue(value);
  }
}
