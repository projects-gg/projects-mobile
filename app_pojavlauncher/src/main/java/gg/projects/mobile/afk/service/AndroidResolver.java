package gg.projects.mobile.afk.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import gg.projects.mobile.afk.net.AfkDns;
import gg.projects.mobile.afk.net.AfkResolver;

/**
 * The phone's address lookup for AFK: a server's SRV record when no port was written (Android 10+ asks the system's
 * resolver for it, private DNS included; older phones ask their DNS servers over UDP), then A/AAAA. A failed SRV lookup
 * falls back to the host and port 25565 like the game. Runs on the wire's resolver thread.
 */
final class AndroidResolver implements AfkResolver {
    private static final int TIMEOUT_MS = 4000;
    private final Context app;
    private final SecureRandom random = new SecureRandom();

    AndroidResolver(Context context) {
        this.app = context.getApplicationContext();
    }

    @Override
    public Resolved resolve(String host, int port) throws UnknownHostException {
        String target = host;
        int targetPort = port;
        if (port == 0 && !AfkResolver.isIpLiteral(host)) {
            AfkDns.Srv srv = srv(host);
            if (srv != null) {
                target = srv.target;
                targetPort = srv.port;
            }
        }
        InetAddress address = InetAddress.getByName(target);
        return new Resolved(address, target, targetPort == 0 ? DEFAULT_PORT : targetPort);
    }

    private AfkDns.Srv srv(String host) {
        String name = AfkDns.srvName(host);
        try {
            byte[] answer = Build.VERSION.SDK_INT >= 29 ? rawQuery(name) : udpQuery(name);
            return answer == null ? null : AfkDns.pick(AfkDns.parseSrv(answer, -1));
        } catch (Exception e) {
            return null;
        }
    }

    @RequiresApi(29)
    @SuppressLint("WrongConstant")
    private byte[] rawQuery(String name) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final byte[][] out = new byte[1][];
        DnsResolver.getInstance().rawQuery(null, name, DnsResolver.CLASS_IN, AfkDns.TYPE_SRV, DnsResolver.FLAG_EMPTY,
                Runnable::run, null, new DnsResolver.Callback<byte[]>() {
                    @Override
                    public void onAnswer(byte[] answer, int rcode) {
                        if (rcode == 0) out[0] = answer;
                        done.countDown();
                    }

                    @Override
                    public void onError(DnsResolver.DnsException error) {
                        done.countDown();
                    }
                });
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return out[0];
    }

    private byte[] udpQuery(String name) {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network network = cm.getActiveNetwork();
        LinkProperties link = network == null ? null : cm.getLinkProperties(network);
        List<InetAddress> servers = link == null ? null : link.getDnsServers();
        if (servers == null) return null;
        for (InetAddress server : servers) {
            int id = random.nextInt(0x10000);
            byte[] query = AfkDns.query(id, name, AfkDns.TYPE_SRV);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(TIMEOUT_MS / 2);
                socket.send(new DatagramPacket(query, query.length, server, 53));
                byte[] buf = new byte[1500];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                socket.receive(reply);
                byte[] answer = new byte[reply.getLength()];
                System.arraycopy(buf, 0, answer, 0, answer.length);
                // An answer, with records or without (no SRV: the host and the default port).
                if (answer.length >= 12) return answer;
            } catch (Exception ignored) {
                // The next server.
            }
        }
        return null;
    }
}
