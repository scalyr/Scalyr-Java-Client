package com.scalyr.api.logs;

import java.util.function.*;

/**
 * An interface for key-value logging using builder call chaining. Example calls:
 *
 * <pre>
 *   log.add("cmd", "send", "details", "Sent batch", "elapsedMs", elapsedMs).add(batch::annot).info();
 *   log.add("cmd", "send", "details", "Call failed").err(e).error();
 *   log.add("cmd", "send", "details", "Network error").err(e).carp(warnLimit);
 *   log.limit(statsLimit, () -> log.add("cmd", "send", "details", "Stats").add(stats::annot).info());
 * </pre>
 */
public interface KeyValueLog {

  //--------------------------------------------------------------------------------
  // key-value adders
  //--------------------------------------------------------------------------------

  /** Add one key/value pair to the event attributes for the next log event. */
  KeyValueLog add(String key1, Object val1);

  /** Add two key/value pairs to the event attributes for the next log event. */
  KeyValueLog add(String key1, Object val1, String key2, Object val2);

  /** Add three key/value pairs to the event attributes for the next log event. */
  KeyValueLog add(String key1, Object val1, String key2, Object val2, String key3, Object val3);

  /** Add four key/value pairs to the event attributes for the next log event. */
  KeyValueLog add(String key1, Object val1, String key2, Object val2, String key3, Object val3, String key4, Object val4);

  //--------------------------------------------------------------------------------
  // annotations
  //--------------------------------------------------------------------------------

  /** Add key/value pairs returned by `annotFn` to the event attributes. */
  KeyValueLog add(AnnotFn annotFn);

  /** Add key/value pairs (preserving existing key/values w/ matching keys) returned by `annotFn` to the event attributes. */
  KeyValueLog union(AnnotFn annotFn);

  /** Add key/value pairs returned by `annotFn` and prefix keys w/ `prefix`. */
  KeyValueLog add(String prefix, AnnotFn annotFn);

  /** Add key/value pairs (preserving existing key/values w/ matching keys) returned by `annotFn` and prefix keys w/ `prefix`. */
  KeyValueLog union(String prefix, AnnotFn annotFn);

  /** Add key/value pairs returned by `annot` implementation. */
  KeyValueLog incl(Annot annot);

  /** Add exception information and stack trace key/value pairs to the event attributes. */
  KeyValueLog err(Throwable e);

  //--------------------------------------------------------------------------------
  // emitters
  //--------------------------------------------------------------------------------

  /** Write a log event at `error` severity using the collected event attributes. */
  void emit(Severity sev);

  /** Write a log event at `debug` severity using the collected event attributes. */
  default void debug() { emit(Severity.fine); };

  /** Write a log event at `info` severity using the collected event attributes. */
  default void info() { emit(Severity.info); };

  /** Write a log event at `warn` severity using the collected event attributes. */
  default void warn() { emit(Severity.warning); };

  /** Write a log event at `error` severity using the collected event attributes. */
  default void error() { emit(Severity.error); };

  /** Call `warn` if limit function allows, otherwise call `error`. */
  default void carp(RateLimiter limiter) {
    if (limiter.tryAcquire()) {
      warn();
    } else {
      error();
    }
  }

  /** Evaluate rate-limiter and call log function if rate limiter returns `true`. */
  default void limit(RateLimiter limiter, Runnable logFn) {
    if (limiter.tryAcquire()) {
      logFn.run();
    }
  }

  //--------------------------------------------------------------------------------
  // interfaces
  //--------------------------------------------------------------------------------

  /**
   * Used for generalized logging by {@link #incl}, this does not allow called-by tracking.
   * Prefer functional interface via {@link #add(AnnotFn)}, e.g. `.add(stats::annot)`, to this where possible.
   */
  interface Annot {
    AnnotFn annotFn();
  }

  /**
   * A simple method contract that supports called-by tracking and requires no explicit `implements`
   * on class def.
   */
  interface AnnotFn extends Function<EventAttributes, EventAttributes> {}

  interface RateLimiter {
    boolean tryAcquire();
  }
}
