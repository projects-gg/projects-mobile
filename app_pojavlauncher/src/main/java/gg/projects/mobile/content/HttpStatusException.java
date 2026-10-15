package gg.projects.mobile.content;

import java.io.IOException;

/** A mirror answered, but not with 200 OK (404 means "not published"). */
public final class HttpStatusException extends IOException {
    public final int status;

    public HttpStatusException(int status, String url) {
        super("HTTP " + status + " for " + url);
        this.status = status;
    }
}
