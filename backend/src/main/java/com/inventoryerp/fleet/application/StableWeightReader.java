package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.AsciiFrameParser.Frame;
import com.inventoryerp.fleet.application.WeighbridgeAdapter.StableWeight;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntConsumer;

/**
 * Turns a stream of indicator frames into one stable weight (fleet 1.4): frames are parsed with the device's
 * {@link AsciiFrameParser}; motion frames reset the run, an overload frame fails at once, and the weight is stable once
 * {@code stableFrames} consecutive stable frames show the same value. Everything is bounded by a deadline — the read
 * never blocks a request thread longer than the timeout.
 */
public final class StableWeightReader {

    /** Next frame within {@code max} (null when none arrived in time); {@link EOFException} when the source closed. */
    @FunctionalInterface
    public interface FrameSource {
        String next(Duration max) throws IOException;
    }

    private static final int MAX_FRAME = 256;

    private StableWeightReader() {}

    public static StableWeight read(FrameSource source, AsciiFrameParser parser, Duration timeout, String deviceLabel) {
        return read(source, parser, timeout, deviceLabel, Clock.systemUTC());
    }

    static StableWeight read(FrameSource source, AsciiFrameParser parser, Duration timeout, String deviceLabel,
            Clock clock) {
        Instant deadline = clock.instant().plus(timeout);
        BigDecimal last = null;
        int run = 0;
        int frames = 0;
        int parsed = 0;
        boolean motion = false;
        while (true) {
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                break;
            }
            String raw;
            try {
                raw = source.next(remaining);
            } catch (EOFException e) {
                throw new WeighbridgeReadException("unreachable", "Indicator " + deviceLabel
                        + " closed the connection before a stable weight was read");
            } catch (IOException e) {
                throw new WeighbridgeReadException("unreachable", "Indicator " + deviceLabel + " cannot be read: "
                        + e.getMessage(), e);
            }
            if (raw == null) {
                continue;
            }
            frames++;
            Frame f = parser.parse(raw);
            if (f == null) {
                continue;
            }
            parsed++;
            if (f.overload()) {
                throw new WeighbridgeReadException("overload", "Indicator " + deviceLabel + " reports an overload ("
                        + f.raw() + ")");
            }
            if (!f.stable()) {
                motion = true;
                last = null;
                run = 0;
                continue;
            }
            if (last != null && last.compareTo(f.kilograms()) == 0) {
                run++;
            } else {
                last = f.kilograms();
                run = 1;
            }
            if (run >= parser.stableFrames()) {
                return new StableWeight(f.kilograms(), clock.instant(), f.raw(), true);
            }
        }
        long secs = Math.max(1, Math.round(timeout.toMillis() / 1000.0));
        if (frames == 0) {
            throw new WeighbridgeReadException("timeout", "Indicator " + deviceLabel + " sent no weight within "
                    + secs + " s");
        }
        if (parsed == 0) {
            throw new WeighbridgeReadException("format", "Indicator " + deviceLabel + " sent " + frames
                    + " frame(s) that do not match the configured frame format");
        }
        throw new WeighbridgeReadException("unstable", "The weight on indicator " + deviceLabel
                + (motion ? " did not settle" : " was not stable") + " within " + secs + " s");
    }

    /**
     * Frames from a byte stream: a frame ends at CR, LF, STX or ETX. {@code soTimeout} sets the read timeout of the
     * underlying socket before each read ({@link SocketTimeoutException} = no frame yet; the partial frame is kept).
     */
    public static FrameSource lines(InputStream in, IntConsumer soTimeout) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        return max -> {
            long end = System.nanoTime() + max.toNanos();
            while (true) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return null;
                }
                soTimeout.accept((int) Math.min(Integer.MAX_VALUE, left));
                int b;
                try {
                    b = in.read();
                } catch (SocketTimeoutException e) {
                    return null;
                }
                if (b < 0) {
                    if (buf.size() > 0) {
                        String s = buf.toString(StandardCharsets.US_ASCII);
                        buf.reset();
                        return s;
                    }
                    throw new EOFException();
                }
                if (b == '\r' || b == '\n' || b == 0x02 || b == 0x03) {
                    if (buf.size() == 0) {
                        continue;
                    }
                    String s = buf.toString(StandardCharsets.US_ASCII);
                    buf.reset();
                    return s;
                }
                if (buf.size() >= MAX_FRAME) {
                    buf.reset();
                }
                buf.write(b);
            }
        };
    }
}
