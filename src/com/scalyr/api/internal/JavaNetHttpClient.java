package com.scalyr.api.internal;

import com.scalyr.api.internal.ScalyrService.RpcOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * AbstractHttpClient implementation based on java.net.HttpURLConnection.
 */
public class JavaNetHttpClient extends AbstractHttpClient {
  private HttpURLConnection connection;
  private InputStream responseStream;

  public JavaNetHttpClient(URL url, int requestLength, boolean closeConnections, RpcOptions options,
                           String contentType, String contentEncoding) throws IOException {
    connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("POST");
    connection.setUseCaches(false);
    connection.setDoInput(true);
    connection.setConnectTimeout(options.connectionTimeoutMs);
    connection.setReadTimeout(options.readTimeoutMs);

    if (closeConnections)
      connection.setRequestProperty("connection", "close");

    connection.setRequestProperty("Content-Type", contentType);
    connection.setRequestProperty("Content-Length", "" + requestLength);
    connection.setRequestProperty("errorStatus", "always200");

    if (contentEncoding != null && contentEncoding.length() > 0)
      connection.setRequestProperty("Content-Encoding", "contentEncoding");

    connection.setDoOutput(true);
  }

  @Override public OutputStream getOutputStream() throws IOException {
    return connection.getOutputStream();
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
    responseStream = connection.getInputStream();
    return responseStream;
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
