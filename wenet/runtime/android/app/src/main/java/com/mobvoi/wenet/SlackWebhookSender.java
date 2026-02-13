package com.mobvoi.wenet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.OutputStream;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONObject;

public class SlackWebhookSender {

    private static final String LOG_TAG = "WENET";
    private static final String PREFS_NAME = "wenet_settings";
    private static final String KEY_WEBHOOK_URL = "slack_webhook_url";

    public static void send(Context context, String recordingName, String recognitionText) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String webhookUrl = prefs.getString(KEY_WEBHOOK_URL, "");
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            Log.i(LOG_TAG, "Slack webhook URL not configured, skipping send");
            return;
        }

        new Thread(() -> {
            try {
                String message = "\ud83d\udcdd [" + recordingName + "]\n" + recognitionText;
                JSONObject payload = new JSONObject();
                payload.put("text", message);

                TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    }
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAll, new SecureRandom());

                URL url = new URL(webhookUrl);
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setSSLSocketFactory(sslContext.getSocketFactory());
                conn.setHostnameVerifier((hostname, session) -> true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                byte[] body = payload.toString().getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    Log.i(LOG_TAG, "Slack webhook sent successfully");
                } else {
                    Log.e(LOG_TAG, "Slack webhook failed with code: " + responseCode);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Slack webhook error: " + e.getMessage());
            }
        }).start();
    }
}
