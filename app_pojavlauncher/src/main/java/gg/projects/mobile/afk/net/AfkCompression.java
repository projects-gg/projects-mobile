package gg.projects.mobile.afk.net;

import org.geysermc.mcprotocollib.network.compression.PacketCompression;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * MCProtocolLib's zlib through byte arrays (AFK-M0 §4.4): its ZlibCompression calls the ByteBuffer forms of
 * Inflater/Deflater, Java 11 methods Android has only from API 35, so every server that compresses (vanilla's
 * threshold is 256 bytes) would end in NoSuchMethodError below Android 15. One instance per connection, used on its
 * event loop only.
 */
final class AfkCompression implements PacketCompression {
    private static final int KEEP_MAX = 1 << 20;
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final Inflater inflater = new Inflater();
    private byte[] in = new byte[8192];
    private byte[] out = new byte[8192];

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize) throws DataFormatException {
        int n = source.readableBytes();
        byte[] input = buffer(true, n);
        source.readBytes(input, 0, n);
        inflater.setInput(input, 0, n);
        try {
            byte[] output = buffer(false, uncompressedSize);
            int produced = 0;
            while (produced < uncompressedSize && !inflater.finished()) {
                int r = inflater.inflate(output, produced, uncompressedSize - produced);
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                produced += r;
            }
            if (produced != uncompressedSize) {
                throw new DecoderException("Badly compressed packet: " + produced + " of " + uncompressedSize + " bytes");
            }
            destination.writeBytes(output, 0, produced);
        } finally {
            inflater.reset();
        }
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) {
        int n = source.readableBytes();
        byte[] input = buffer(true, n);
        source.readBytes(input, 0, n);
        deflater.setInput(input, 0, n);
        deflater.finish();
        byte[] chunk = buffer(false, 8192);
        while (!deflater.finished()) {
            int produced = deflater.deflate(chunk, 0, chunk.length);
            destination.writeBytes(chunk, 0, produced);
        }
        deflater.reset();
    }

    @Override
    public void close() {
        deflater.end();
        inflater.end();
    }

    private byte[] buffer(boolean input, int size) {
        byte[] current = input ? in : out;
        if (current.length >= size) return current;
        byte[] grown = new byte[size];
        if (size <= KEEP_MAX) {
            if (input) in = grown;
            else out = grown;
        }
        return grown;
    }
}
