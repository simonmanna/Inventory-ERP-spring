package com.inventoryerp.fleet.application;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Generic continuous-output ASCII indicator on a local serial port (fleet 1.4): frames are read from the serial device
 * file ({@code port}, e.g. {@code /dev/ttyUSB0}). The line settings (baud, parity, data / stop bits) are set on the host
 * (e.g. {@code stty -F /dev/ttyUSB0 9600 cs8 -cstopb -parenb raw}); {@code baudRate} etc. in the configuration are
 * documentation for that set-up. Bytes are pumped by a virtual thread, so the request waits at most the timeout. The
 * frame format keys are those of {@link AsciiFrameParser}. Prefer a serial-to-Ethernet converter with the
 * {@code ascii-tcp} adapter when the server is not next to the indicator.
 */
@Component
public class AsciiSerialWeighbridgeAdapter implements WeighbridgeAdapter {

    public static final String TYPE = "ascii-serial";
    private static final String EOF = "\u0000EOF";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void validate(Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        String port = AsciiFrameParser.str(c.get("port"));
        if (port == null || !port.trim().startsWith("/")) {
            throw new IllegalArgumentException("The serial port must be a device path such as /dev/ttyUSB0");
        }
        int baud = AsciiFrameParser.integer(c.get("baudRate"), 9600);
        if (baud < 300 || baud > 921600) {
            throw new IllegalArgumentException("The baud rate must be between 300 and 921600");
        }
        AsciiFrameParser.of(c);
    }

    @Override
    public StableWeight readStable(DeviceSpec device, Duration timeout) {
        Map<String, Object> c = device.config() == null ? Map.of() : device.config();
        AsciiFrameParser parser;
        try {
            validate(c);
            parser = AsciiFrameParser.of(c);
        } catch (IllegalArgumentException e) {
            throw new WeighbridgeReadException("format", "Indicator " + device.code() + " is misconfigured: "
                    + e.getMessage());
        }
        Path port = Path.of(AsciiFrameParser.str(c.get("port")).trim());
        if (!Files.isReadable(port)) {
            throw new WeighbridgeReadException("unreachable", "Serial port " + port + " of indicator "
                    + device.code() + " is not readable");
        }
        BlockingQueue<String> frames = new LinkedBlockingQueue<>(1000);
        InputStream in;
        try {
            in = new FileInputStream(port.toFile());
        } catch (IOException e) {
            throw new WeighbridgeReadException("unreachable", "Serial port " + port + " cannot be opened: "
                    + e.getMessage(), e);
        }
        Thread pump = Thread.ofVirtual().name("weighbridge-serial-" + device.code()).start(() -> {
            StableWeightReader.FrameSource lines = StableWeightReader.lines(in, ms -> { });
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String f = lines.next(Duration.ofDays(1));
                    if (f != null && !frames.offer(f)) {
                        frames.clear();
                    }
                }
            } catch (IOException e) {
                frames.offer(EOF);
            }
        });
        try {
            return StableWeightReader.read(max -> {
                try {
                    String f = frames.poll(Math.max(1, max.toMillis()), TimeUnit.MILLISECONDS);
                    if (EOF.equals(f)) {
                        throw new EOFException();
                    }
                    return f;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }, parser, timeout, device.code());
        } finally {
            pump.interrupt();
            try {
                in.close();
            } catch (IOException ignored) {
                // closing a serial device that is still being read can fail; the pump thread ends with it
            }
        }
    }

    @Override
    public DeviceHealth health(DeviceSpec device, Duration timeout) {
        Map<String, Object> c = device.config() == null ? Map.of() : device.config();
        try {
            validate(c);
        } catch (IllegalArgumentException e) {
            return new DeviceHealth(false, e.getMessage());
        }
        Path port = Path.of(AsciiFrameParser.str(c.get("port")).trim());
        return Files.isReadable(port) ? new DeviceHealth(true, "Serial port " + port + " is readable")
                : new DeviceHealth(false, "Serial port " + port + " is not readable");
    }
}
