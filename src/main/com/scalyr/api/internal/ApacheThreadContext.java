package com.scalyr.api.internal;

import org.apache.http.client.protocol.HttpClientContext;

/**
 * Holds an HTTP client context. For best multithreaded performance, each thread should maintain its own instance.
 *
 * > While HttpClient instances are thread safe and can be shared between multiple threads of execution,
 * > it is highly recommended that each thread maintains its own dedicated instance of HttpContext.
 *
 * https://hc.apache.org/httpcomponents-client-ga/tutorial/html/connmgmt.html
 */
public final class ApacheThreadContext {
  public final HttpClientContext context;

  public ApacheThreadContext() {
    context = HttpClientContext.create();
  }

}
