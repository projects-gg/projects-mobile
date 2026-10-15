package gg.projects.mobile.content;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** GET with a size cap; implemented over HttpURLConnection, replaced by fakes in tests. */
public interface HttpFetcher {
    byte[] get(String url, long maxBytes) throws IOException;

    /** Plain HttpURLConnection fetcher with short timeouts (content is small). */
    final class UrlConnection implements HttpFetcher {
        private final String userAgent;

        public UrlConnection(String userAgent) {
            this.userAgent = userAgent;
        }

        @Override
        public byte[] get(String url, long maxBytes) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", "application/json, */*");
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) throw new HttpStatusException(status, url);
                long declared = connection.getContentLength();
                if (declared > maxBytes) throw new IOException("response is larger than " + maxBytes + " bytes");
                try (InputStream in = connection.getInputStream()) {
                    return AtomicFiles.readBounded(in, maxBytes);
                }
            } finally {
                connection.disconnect();
            }
        }
    }
}
