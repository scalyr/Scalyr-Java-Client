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

import com.google.common.base.Throwables;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Contains implementations of {@link KeyValueLog} which allow key-value logging using builder call chaining.
 *
 * These classes are not normally used directly. Users will likely prefer using a factory method e.g. `getLogger`
 * which returns instances of {@link EventStart}.
 *
 * <pre>
 *   static KeyValueLog getLogger() {
 *     return new EventStart(sink); // in most cases `sink` is a bi-consumer that is long lived in order to reduce object creation
 *   }
 * </pre>
 *
 * For example usage, see: {@link KeyValueLog}.
 */
public final class EventBuilder {

  /** Class to start the builder call chaining. */
  public static final class EventStart implements KeyValueLog {

    /** Sink to emit events to */
    final BiConsumer<Severity, EventAttributes> eventSink;

    //--------------------------------------------------------------------------------
    // ctors
    //--------------------------------------------------------------------------------

    public EventStart(BiConsumer<Severity, EventAttributes> eventSink) {
      this.eventSink  = eventSink;
    }

    //--------------------------------------------------------------------------------
    // overrides
    //--------------------------------------------------------------------------------

    @Override public KeyValueLog add(String key1, Object val1) {
      return new Builder(eventSink).add(key1, val1);
    }

    @Override public KeyValueLog add(String key1, Object val1, String key2, Object val2) {
      return new Builder(eventSink).add(key1, val1, key2, val2);
    }

    @Override public KeyValueLog add(String key1, Object val1, String key2, Object val2, String key3, Object val3) {
      return new Builder(eventSink).add(key1, val1, key2, val2, key3, val3);
    }

    @Override public KeyValueLog add(String key1, Object val1, String key2, Object val2, String key3, Object val3, String key4, Object val4) {
      return new Builder(eventSink).add(key1, val1, key2, val2, key3, val3, key4, val4);
    }

    @Override public KeyValueLog add  (AnnotFn annotFn) { return new Builder(eventSink).add  (annotFn); }
    @Override public KeyValueLog union(AnnotFn annotFn) { return new Builder(eventSink).union(annotFn); }

    @Override public KeyValueLog add  (String prefix, AnnotFn annotFn) { return new Builder(eventSink).add  (prefix, annotFn); }
    @Override public KeyValueLog union(String prefix, AnnotFn annotFn) { return new Builder(eventSink).union(prefix, annotFn); }

    @Override public KeyValueLog incl(Annot annot) { return new Builder(eventSink).incl(annot); }
    @Override public KeyValueLog err (Throwable e) { return new Builder(eventSink).err (e)    ; }

    @Override public void emit(Severity sev) { new Builder(eventSink).emit(sev); }
  } // EventStart

  /** The normal builder. It accumulates attributes and emits an event to the sink instance. */
  public static final class Builder implements KeyValueLog {
    final EventAttributes attrs = new EventAttributes();

    final BiConsumer<Severity, EventAttributes> eventSink;

    //--------------------------------------------------------------------------------
    // ctors
    //--------------------------------------------------------------------------------

    public Builder(BiConsumer<Severity, EventAttributes> eventSink) {
      this.eventSink = eventSink;
    }

    //--------------------------------------------------------------------------------
    // overrides
    //--------------------------------------------------------------------------------

    @Override public KeyValueLog add(String key1, Object val1) {
      attrs.put(key1, val1);
      return this;
    }

    @Override public KeyValueLog add(String key1, Object val1, String key2, Object val2) {
      attrs.put(key1, val1);
      attrs.put(key2, val2);
      return this;
    }

    @Override public KeyValueLog add(String key1, Object val1, String key2, Object val2, String key3, Object val3) {
      attrs.put(key1, val1);
      attrs.put(key2, val2);
      attrs.put(key3, val3);
      return this;
    }

    @Override public KeyValueLog add(String key1, Object val1, String key2, Object val2, String key3, Object val3, String key4, Object val4) {
      attrs.put(key1, val1);
      attrs.put(key2, val2);
      attrs.put(key3, val3);
      attrs.put(key4, val4);
      return this;
    }

    @Override public KeyValueLog add(AnnotFn annotFn) {
      annotFn.apply(attrs);
      return this;
    }

    @Override public KeyValueLog union(AnnotFn annotFn) {
      final EventAttributes ea = annotFn.apply(new EventAttributes());
      attrs.underwriteFrom(ea);
      return this;
    }

    @Override public KeyValueLog add(String prefix, AnnotFn annotFn) {
      final EventAttributes ea = annotFn.apply(new EventAttributes());
      for (Map.Entry<String, Object> entry : ea.getEntries()) {
        attrs.put(prefix + entry.getKey(), entry.getValue());
      }
      return this;
    }

    @Override public KeyValueLog union(String prefix, AnnotFn annotFn) {
      final EventAttributes ea = annotFn.apply(new EventAttributes());
      for (Map.Entry<String, Object> entry : ea.getEntries()) {
        attrs.putIfAbsent(prefix + entry.getKey(), entry.getValue());
      }
      return this;
    }

    @Override public KeyValueLog incl(Annot annot) {
      annot.annotFn().apply(attrs);
      return this;
    }

    @Override public KeyValueLog err(Throwable e) {
      annot(attrs, e);
      return this;
    }

    /** Write attribute pairs to logger and monitor sink. */
    @Override public void emit(Severity sev) {
      eventSink.accept(sev, attrs);
    }
  } // EventBuilder

  //--------------------------------------------------------------------------------
  // public statics
  //--------------------------------------------------------------------------------

  /**
   * @param attrs the attributes to flatten
   * @return construct a space-separated string of `key=value` from the key-value pair(s) in `attrs`
   */
  public static String flatten(EventAttributes attrs) {
    return attrs.getEntries().stream()
      .map(e -> String.join("=", e.getKey(), "" + e.getValue()))  // a=1
      .collect(Collectors.joining(" "));  // a=1 b=2 ...
  }

  /**
   * @param ea the event attributes to annotate
   * @param e the throwable to annotate with
   * @return `ea` annotated with the `errorMessage`, `errorClass`, and `stackTrace` of `e`
   */
  public static EventAttributes annot(EventAttributes ea, Throwable e) {
    return ea
      .put("errorMessage", e.getMessage())
      .put("errorClass", e.getClass().getSimpleName())
      .put("stackTrace", Throwables.getStackTraceAsString(e));
  }
}
