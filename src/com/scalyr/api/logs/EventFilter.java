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

/**
 * This class defines an interface through which you can intercept and modify events as
 * they flow into Scalyr Logs. To use this interface, define a subclass of EventFilter,
 * override the filter() method, and pass an instance to Events.setEventFilter().
 */
public class EventFilter {
  public static class FilterInput {
    /**
     * ID of the thread which generated this event.
     */
    public String threadId;

    /**
     * Event timestamp (nanoseconds since Unix epoch).
     *
     * Deprecated -- the value in this field may not be correct.
     */
    @Deprecated
    public long timestampNs;

    /**
     * Indicates whether this is a span start, span end, or regular event. (See the SPAN_TYPE_XXX
     * constants in LogService.)
     */
    public int spanType;

    /**
     * Event severity.
     */
    public Severity severity;

    /**
     * Attributes for this event.
     * <p>
     * NOTE: you should not mutate this object. If you wish to modify the event's attributes,
     * construct a clone of the EventAttributes object, modify the clone, and place it in
     * FilterOutput.attributes.
     */
    public EventAttributes attributes;

    /**
     * True if this is a top-level event (not nested inside any spans).
     */
    public boolean isTopLevel;

    /**
     * True if some span enclosing this event was discarded by a filter.
     */
    public boolean inDiscardedSpan;

    FilterInput() {
    }
  }

  public static class FilterOutput {
    /**
     * Set this to true to discard the event (prevent it from being uploaded to Scalyr Logs).
     */
    public boolean discardEvent = false;

    /**
     * Set this to a non-null value to replace the event's attributes.
     */
    public EventAttributes attributes = null;

    /**
     * Modify this to modify the event's severity as reported to Scalyr Logs. Initially,
     * this will be equal to input.severity.
     */
    public Severity severity;

    FilterOutput(FilterInput input) {
      severity = input.severity;
    }
  }

  /**
   * This method is invoked for each event. It can log the event elsewhere; discard the
   * event (by setting output.discardEvent); or modify the event's attributes (by cloning
   * input.attributes, modifying the clone, and storing it in output.attributes).
   */
  public void filter(FilterInput input, FilterOutput output) {
    // default implementation allows all events to pass unmodified
  }
}
