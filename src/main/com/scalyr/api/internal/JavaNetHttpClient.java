package com.scalyr.api.internal;

import com.scalyr.api.internal.ScalyrService.RpcOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

/**
 * AbstractHttpClient implementation based on java.net.HttpURLConnection.
 * Has Gzip compression capability.
 */
public class JavaNetHttpClient extends AbstractHttpClient {
  private HttpURLConnection connection;
  private InputStream responseStream;
  private boolean enableGzip;

  /**
   * Version of constructor with desired Content-Encoding passed in.
   */
  public JavaNetHttpClient(URL url, int requestLength, boolean closeConnections, RpcOptions options,
                           String contentType, String contentEncoding) throws IOException {
    connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setUseCaches(false);
    connection.setDoInput(true);
    connection.setConnectTimeout(options.connectionTimeoutMs);
    connection.setReadTimeout(options.readTimeoutMs);

    enableGzip = "gzip".equals(contentEncoding);

    if (closeConnections)
      connection.setRequestProperty("connection", "close");

    connection.setRequestProperty("Content-Type", contentType);
    connection.setRequestProperty("Content-Length", "" + requestLength);
    connection.setRequestProperty("errorStatus", "always200");

    if (contentEncoding != null && contentEncoding.length() > 0)
      connection.setRequestProperty("Content-Encoding", contentEncoding);
      connection.setRequestProperty("Accept-Encoding", contentEncoding + ", identity");

    connection.setDoOutput(true);
  }

  /**
   * Version of constructor with a Gzip Compression toggle, rather than a freely settable content-encoding.
   * If enableGzip is true, Content-Encoding is set to "gzip".
   */
  public JavaNetHttpClient(URL url, int requestLength, boolean closeConnections, RpcOptions options,
                           String contentType, boolean enableGzip) throws IOException {
    this(url, requestLength, closeConnections, options, contentType, enableGzip ? "gzip" : null);
  }

  @Override public OutputStream getOutputStream() throws IOException {
    return enableGzip ? new GZIPOutputStream(connection.getOutputStream()) : connection.getOutputStream();
  }

  @Override public int getResponseCode() throws IOException {
    return connection.getResponseCode();
  }

  @Override public String getResponseContentType() {
    return connection.getContentType();
  }

  @Override public String getResponseEncoding() {
    return connection.getContentEncoding();
  }

  @Override public InputStream getInputStream() throws IOException {
    if (responseStream != null) return responseStream;
    if (getResponseEncoding() != null && getResponseEncoding().contains("gzip"))
      return responseStream = new GZIPInputStream(connection.getInputStream());
    return responseStream = connection.getInputStream();
  }

  @Override public void finishedReadingResponse() throws IOException {
    if (responseStream != null) {
      responseStream.close();
    }
  }

  @Override public void disconnect() {
    connection.disconnect();
  }
}
