package com.scalyr.api.internal;

import com.scalyr.api.internal.ScalyrService.RpcOptions;
import com.scalyr.api.knobs.Knob;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.GzipCompressingEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * AbstractHttpClient implementation based on the Apache HTTP client library.
 * Has Gzip compression capability.
 */
public class ApacheHttpClient extends AbstractHttpClient {
  /**
   * Connection manager used to issue requests to the Scalyr server.
   */
  private static volatile PoolingHttpClientConnectionManager connectionManager;

  private CloseableHttpResponse response;
  private InputStream responseStream;
  private String responseContentType;
  private String responseEncoding;

  /**
   * Version of constructor with desired Content-Encoding passed in.
   */
  public ApacheHttpClient(URL url, int requestLength, boolean closeConnections, RpcOptions options,
                          byte[] requestBody, int requestBodyLength, String contentType, String contentEncoding) throws IOException {
    if (connectionManager == null) {
      synchronized (ApacheHttpClient.class) {
        if (connectionManager == null) {
          createConnectionManager();
        }
      }
    }

    HttpClientBuilder clientBuilder = HttpClients.custom()
        .setConnectionManager(connectionManager)
        ;

    CloseableHttpClient httpClient = clientBuilder.build();
    RequestConfig.Builder configBuilder = RequestConfig.custom();
    configBuilder.setRedirectsEnabled(false);
    configBuilder.setConnectionRequestTimeout(options.connectionTimeoutMs);
    configBuilder.setConnectTimeout(options.connectionTimeoutMs);
    configBuilder.setSocketTimeout(options.readTimeoutMs);

    HttpPost request = new HttpPost(url.toString());
    request.setHeader("errorStatus", "always200");
    request.setHeader("X-XSS-Protection", "1; mode=block");

    if (contentEncoding != null && contentEncoding.length() > 0)
      request.setHeader("Content-Encoding", contentEncoding);
      request.setHeader("Accept-Encoding", contentEncoding + ", identity");

    ByteArrayEntity inputEntity = new ByteArrayEntity(requestBody, 0, requestBodyLength);
    inputEntity.setContentType(contentType);

    request.setEntity("gzip".equals(contentEncoding) ? new GzipCompressingEntity(inputEntity) : inputEntity);

    request.setConfig(configBuilder.build());

    response = httpClient.execute(request);

    HttpEntity responseEntity = response.getEntity();
    responseStream = (responseEntity != null) ? responseEntity.getContent() : null;
    responseContentType = (responseEntity != null && responseEntity.getContentType() != null) ? responseEntity.getContentType().getValue() : null;
    responseEncoding = (responseEntity != null && responseEntity.getContentEncoding() != null) ? responseEntity.getContentEncoding().getValue() : null;
  }

  /**
   * Version of constructor with a Gzip Compression toggle, rather than a freely settable content-encoding.
   * If enableGzip is true, Content-Encoding is set to "gzip".
   */
  public ApacheHttpClient(URL url, int requestLength, boolean closeConnections, RpcOptions options,
                          byte[] requestBody, int requestBodyLength, String contentType, boolean enableGzip) throws IOException {
    this(url, requestLength, closeConnections, options, requestBody, requestBodyLength, contentType, enableGzip ? "gzip" : null);
  }

  private static void createConnectionManager() {
    connectionManager = new PoolingHttpClientConnectionManager();
    connectionManager.setMaxTotal(Knob.getInteger("scalyrClientMaxConnections", 20));
    connectionManager.setDefaultMaxPerRoute(Knob.getInteger("scalyrClientMaxConnectionsPreRoute", 15));
  }

  @Override public OutputStream getOutputStream() {
    throw new RuntimeException("Not implemented for ApacheHttpClient (pass request body to our constructor)");
  }

  @Override public int getResponseCode() {
    return response.getStatusLine().getStatusCode();
  }

  @Override public String getResponseContentType() {
    return responseContentType;
  }

  @Override public String getResponseEncoding() {
    return responseEncoding;
  }


  @Override public InputStream getInputStream() throws IOException {
    if (getResponseEncoding() != null && getResponseEncoding().contains("gzip"))
      return new GZIPInputStream(responseStream);
    return responseStream;
  }

  @Override public void finishedReadingResponse() throws IOException {
    // We must close the response stream. This tells the HTTP library that this connection can
    // be released back into the pool. We do *not* close the proxyResponse object, as that would
    // close the underlying connection to the backend server, defeating keepalive.
    if (responseStream != null) {
      responseStream.close();
    }
  }

  @Override public void disconnect() {
  }
}
