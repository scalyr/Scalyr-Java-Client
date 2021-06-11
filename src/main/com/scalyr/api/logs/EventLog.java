package com.scalyr.api.logs;

import com.google.common.base.Throwables;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * A {@link KeyValueLog} log that provides a basic event builder. See: {@link KeyValueLog} for example usage.
 *
 * This class is not normally used directly, but is instead used by a `getLogger` factory method.
 */
public final class EventLog implements KeyValueLog<EventLog.Builder> {

  private final BiConsumer<Severity, EventAttributes> sink;

  public EventLog(BiConsumer<Severity, EventAttributes> sink) {
    this.sink = sink;
  }

  /** Begin a log message. */
  @Override
  public Builder ev() {
    return new Builder(sink);
  }

  public static class Builder extends TypedBuilder<Builder> {

    public Builder(BiConsumer<Severity, EventAttributes> sink) {
      super(sink);
      this.bld = this;
    }

  }

  public static class TypedBuilder<T> {

    protected T bld;

    /** Set of attributes accumulated over calls. */
    final EventAttributes attrs = new EventAttributes();

    /** Sink to emit events to */
    final BiConsumer<Severity, EventAttributes> sink;

    public TypedBuilder(BiConsumer<Severity, EventAttributes> sink) {
      this.bld = null;
      this.sink = sink;
    }

    //--------------------------------------------------------------------------------
    // key-value adders
    //--------------------------------------------------------------------------------

    /** Add one key/value pair to the event attributes for the next log event. */
    public T add(String key1, Object val1) {
      attrs.put(key1, val1);
      return bld;
    }

    /** Add two key/value pairs to the event attributes for the next log event. */
    public T add(String key1, Object val1, String key2, Object val2) {
      attrs.put(key1, val1);
      attrs.put(key2, val2);
      return bld;
    }

    /** Add three key/value pairs to the event attributes for the next log event. */
    public T add(String key1, Object val1, String key2, Object val2, String key3, Object val3) {
      attrs.put(key1, val1);
      attrs.put(key2, val2);
      attrs.put(key3, val3);
      return bld;
    }

    /** Add four key/value pairs to the event attributes for the next log event. */
    public T add(String key1, Object val1, String key2, Object val2, String key3, Object val3, String key4, Object val4) {
      attrs.put(key1, val1);
      attrs.put(key2, val2);
      attrs.put(key3, val3);
      attrs.put(key4, val4);
      return bld;
    }

    //--------------------------------------------------------------------------------
    // annotations
    //--------------------------------------------------------------------------------

    /** Add key/value pairs returned by `annotFn` to the event attributes. */
    public T add(AnnotFn annotFn) {
      annotFn.apply(attrs);
      return bld;
    }

    /** Add key/value pairs (preserving existing key/values w/ matching keys) returned by `annotFn` to the event attributes. */
    public T union(AnnotFn annotFn) {
      final EventAttributes ea = annotFn.apply(new EventAttributes());
      attrs.underwriteFrom(ea);
      return bld;
    }

    /** Add key/value pairs returned by `annotFn` and prefix keys w/ `prefix`. */
    public T add(String prefix, AnnotFn annotFn) {
      final EventAttributes ea = annotFn.apply(new EventAttributes());
      for (Map.Entry<String, Object> entry : ea.getEntries()) {
        attrs.put(prefix + entry.getKey(), entry.getValue());
      }
      return bld;
    }

    /** Add key/value pairs (preserving existing key/values w/ matching keys) returned by `annotFn` and prefix keys w/ `prefix`. */
    public T union(String prefix, AnnotFn annotFn) {
      final EventAttributes ea = annotFn.apply(new EventAttributes());
      for (Map.Entry<String, Object> entry : ea.getEntries()) {
        attrs.putIfAbsent(prefix + entry.getKey(), entry.getValue());
      }
      return bld;
    }

    /** Add key/value pairs returned by `annot` implementation. */
    public T incl(Annot annot) {
      annot.annotFn().apply(attrs);
      return bld;
    }

    /** Add exception information and stack trace key/value pairs to the event attributes. */
    public T err(Throwable e) {
      annot(attrs, e);
      return bld;
    }

    //--------------------------------------------------------------------------------
    // emitters
    //--------------------------------------------------------------------------------

    /** Write a log event at `error` severity using the collected event attributes. */
    public void emit(Severity sev) {
      sink.accept(sev, attrs);
    }

    /** Write a log event at `debug` severity using the collected event attributes. */
    public void debug() {
      emit(Severity.fine);
    }

    /** Write a log event at `info` severity using the collected event attributes. */
    public void info() {
      emit(Severity.info);
    }

    /** Write a log event at `warn` severity using the collected event attributes. */
    public void warn() {
      emit(Severity.warning);
    }

    /** Write a log event at `error` severity using the collected event attributes. */
    public void error() {
      emit(Severity.error);
    }

    /** Call `warn` if limit function allows, otherwise call `error`. */
    public void carp(RateLimiter limiter) {
      if (limiter.tryAcquire()) {
        warn();
      } else {
        error();
      }
    }
  }

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
