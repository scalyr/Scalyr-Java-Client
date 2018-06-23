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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.BiConsumer;

import com.scalyr.api.TuningConstants;
import com.scalyr.api.internal.Logging;

/**
 * A Gauge is an object which can report a value on demand. Gauges are used to periodically
 * sample a value and record it in the Events log. Sample usage:
 * 
 * <pre>
 *   Gauge.register(new Gauge(){
 *     {@literal @}Override public Object sample() {
 *       return someComputation();
 *   }}, new EventAttributes("tag", "foo"));
 * </pre>
 */
public abstract class Gauge {
  /**
   * Report the current value for this gauge.
   * 
   * NOTE: this method should not block, or it will prevent all Gauges from reporting values. 
   */
  public abstract Object sample();
  
  /**
   * Record the current value for this gauge.
   * 
   * Most Gauges will not need to override this method. The default implementation calls sample() and then records the
   * result, adding the specified attributes. A Gauge can override this to record multiple values.
   * 
   * @param attributes Attributes under which this gauge was registered.
   */
  public void recordValue(EventAttributes attributes) {
    Object value = sample();
    if (value != null) {
      EventAttributes attributes_ = new EventAttributes(attributes);
      attributes_.put("value", value);
      Events.info(attributes_);
    }
  }
  
  /**
   * Timer used to sample gauges events. Allocated when the first gauge is registered.
   */
  private static Timer sampleTimer = null;
  private static TimerTask sampleTask = null;
  
  /**
   * Holds an entry for each registered gauge.
   */
  private static Map<Gauge, EventAttributes> registeredGauges = new HashMap<Gauge, EventAttributes>(); 

  /**
   * Run the given action on all registered gauges (and their attributes).  Thread safe, but (like any map iterator)
   * `action` should not deregister any gauges or you risk a `ConcurrentModificationException`.
   */
  public static void forEach(BiConsumer<Gauge, EventAttributes> action) {
    synchronized (registeredGauges) {
      registeredGauges.forEach(action);
    }
  }
  
  /**
   * Register a gauge. We will record the gauge's value once every 30 seconds, associating
   * the given attributes.
   */
  public static Gauge register(Gauge gauge, EventAttributes attributes) {
    boolean firstTime = false;
    synchronized (registeredGauges) {
      registeredGauges.put(gauge, attributes);
      
      // If the timer task hasn't been launched yet, launch it now. Also take this opportunity to
      // register a gauge to report the number of outstanding gauges.
      if (sampleTimer == null) {
        sampleTimer = new Timer("SampleTimer", true);
        firstTime = true;
      }
    }
    
    if (firstTime) {
      sampleTask = new TimerTask(){
        @Override public void run() {
          recordGaugeValues();
        }};
      sampleTimer.schedule(sampleTask, TuningConstants.GAUGE_SAMPLE_INTERVAL_MS,
          TuningConstants.GAUGE_SAMPLE_INTERVAL_MS);
      
      register(new Gauge(){@Override public Object sample() {
        synchronized (registeredGauges) {
          return registeredGauges.size();
        }
      }}, StatReporter.attributesWithTag("scalyr.gaugeCount"));
    }
    
    return gauge;
  }
  
  private static void recordGaugeValues() {
    List<Map.Entry<Gauge, EventAttributes>> entries = new ArrayList<Map.Entry<Gauge, EventAttributes>>();
    synchronized (registeredGauges) {
      for (Map.Entry<Gauge, EventAttributes> entry : registeredGauges.entrySet()) {
        entries.add(entry);
      }
    }
    
    for (Map.Entry<Gauge, EventAttributes> entry : entries) {
      try {
        entry.getKey().recordValue(entry.getValue());
      } catch (Exception ex) {
        Logging.log(Severity.warning, Logging.tagGaugeThrewException,
            "Exception in Gauge [" + entry.getKey() + "] (attributes " + entry.getValue() + ")", ex);
      }
    }
  }
  
  /**
   * Deregister a gauge. If we were recording the gauge's value, we cease doing so.
   */
  public static void deregister(Gauge gauge) {
    synchronized (registeredGauges) {
      registeredGauges.remove(gauge);
    }
  }
}
