/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.performanceanalyzer;


import io.netty.handler.codec.http.HttpMethod;
import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.performanceanalyzer.core.Util;

public class LocalhostConnectionUtil {

    private static final int TIMEOUT_MILLIS = 30000;

    private static final Logger LOG = LogManager.getLogger(LocalhostConnectionUtil.class);

    public static void disablePA() throws InterruptedException {
        String PA_CONFIG_PATH = Util.PA_BASE_URL + "/cluster/config";
        String PA_DISABLE_PAYLOAD = "{\"enabled\": false}";
        int retryCount = 5;

        while (retryCount > 0) {
            HttpURLConnection connection = null;
            try {
                connection = createHTTPConnection(PA_CONFIG_PATH, HttpMethod.POST);
                DataOutputStream stream = new DataOutputStream(connection.getOutputStream());
                stream.writeBytes(PA_DISABLE_PAYLOAD);
                stream.flush();
                stream.close();
                LOG.info(
                        "PA Disable Response: "
                                + connection.getResponseCode()
                                + " "
                                + connection.getResponseMessage());
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    return;
                }
            } catch (Exception e) {
                LOG.error("PA Disable Request failed: " + e.getMessage(), e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            --retryCount;
            Thread.sleep((int) (60000 * (Math.random() * 2) + 100));
        }
        throw new RuntimeException("Failed to disable PA after 5 attempts");
    }

    private static HttpURLConnection createHTTPConnection(String path, HttpMethod httpMethod) {
        try {
            String endPoint = "http://localhost:9200" + path;
            URL endpointUrl = new URL(endPoint);
            HttpURLConnection connection = (HttpURLConnection) endpointUrl.openConnection();
            connection.setRequestMethod(httpMethod.toString());
            connection.setRequestProperty("Content-Type", "application/json");

            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            return connection;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create OpenSearch Connection: " + e.getMessage(), e);
        }
    }
}
