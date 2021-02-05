package com.scalyr.api.internal;

import com.scalyr.api.knobs.Knob;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Holds a connection manager and an HTTP client. Both can and should be shared by clients w/in the process.
 * Full discussion here:
 * https://hc.apache.org/httpcomponents-client-ga/tutorial/html/connmgmt.html
 */
public final class ApacheProcessContext implements AutoCloseable {
  public final PoolingHttpClientConnectionManager cm;
  public final CloseableHttpClient client;

  public ApacheProcessContext() {
    cm = new PoolingHttpClientConnectionManager();
    cm.setMaxTotal(Knob.getInteger("scalyrClientMaxConnections", 20));
    cm.setDefaultMaxPerRoute(Knob.getInteger("scalyrClientMaxConnectionsPreRoute", 15));
    HttpClientBuilder builder = HttpClients.custom()
        .setConnectionManager(cm);
    client = builder.build();
  }

  public void closeStaleConnections(long idleTimeout, TimeUnit unit) {
    cm.closeExpiredConnections();
    cm.closeIdleConnections(idleTimeout, unit);
  }

  @Override public void close() throws IOException {
    client.close();
    cm.close();
  }
}
