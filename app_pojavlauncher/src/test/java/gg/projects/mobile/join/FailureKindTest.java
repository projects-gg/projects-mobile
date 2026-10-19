package gg.projects.mobile.join;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import gg.projects.mobile.content.ContentException;

/** M17: "check your internet" only for a connection problem (a full disk sends the player the wrong way). */
public class FailureKindTest {
    @Test
    public void connectionProblemsAreKnownByTheirType() {
        assertTrue(FailureKind.isNetwork(new UnknownHostException("cdn.projects.gg"), false));
        assertTrue(FailureKind.isNetwork(new IOException("download failed", new ConnectException("refused")), false));
        assertTrue(FailureKind.isNetwork(new RuntimeException(new SocketTimeoutException("read timed out")), false));
        assertTrue(FailureKind.isNetwork(new ContentException(ContentException.Kind.NETWORK, "GET failed",
                new SSLHandshakeException("bad cert")), false));
    }

    @Test
    public void aFullDiskNeverIs() {
        assertFalse(FailureKind.isNetwork(new IOException("write failed: ENOSPC (No space left on device)"), true));
        // Even wrapped as a network failure by a lower layer, beside a connection type.
        assertFalse(FailureKind.isNetwork(new ContentException(ContentException.Kind.NETWORK, "cannot cache",
                new IOException("No space left on device", new ConnectException("x"))), true));
    }

    @Test
    public void anythingElseIsTheCallersAnswer() {
        assertTrue(FailureKind.isNetwork(new IOException("Server returned HTTP response code: 503"), true));
        assertFalse(FailureKind.isNetwork(new IOException("Server returned HTTP response code: 503"), false));
        assertFalse(FailureKind.isNetwork(new java.util.zip.ZipException("invalid entry"), false));
    }
}
