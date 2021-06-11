package com.scalyr.api.logs;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BiConsumer;

public final class ScalyrLog implements KeyValueLog<ScalyrLog.Builder> {

  final BiConsumer<Severity, EventAttributes> sink;

  public ScalyrLog(BiConsumer<Severity, EventAttributes> sink) {
    this.sink = sink;
  }

  /** Begin a log message. */
  @Override
  public Builder ev() {
    return new Builder(sink);
  }

  public static class Builder extends EventLog.TypedBuilder<Builder> {

    public Builder(BiConsumer<Severity, EventAttributes> sink) {
      super(sink);
      this.bld = this;
    }

    public Builder tag(String str) { return add("tag", str); }
    public Builder cmd(String str) { return add("cmd", str); }
    public Builder details(String str) { return add("details", str); }
    public Builder action(String str) { return add("action", str); }
    public Builder method(String str) { return add("method", str); }
    public Builder account(String str) { return add("account", str); }

    public Builder queue(String queueUrl) {
      return add("queue", queueUrl.substring(queueUrl.lastIndexOf("/") + 1));
    }

    public Builder elapsedMs(Instant start) {
      return add("elapsedMs", Long.toString(Duration.between(start, Instant.now()).toMillis()));
    }
  }
}
