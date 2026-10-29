package com.inventoryerp.fleet.application;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generic continuous-output ASCII indicator over TCP (fleet 1.4) — an indicator with an Ethernet port or a
 * serial-to-Ethernet converter in raw TCP mode. Configuration: {@code host}, {@code port}, optional
 * {@code requestCommand} sent after connecting (for indicators that answer a poll, e.g. {@code "W\r\n"}; escapes
 * {@code \r \n \t}), {@code connectTimeoutMs} (default 2000) plus the {@link AsciiFrameParser} format keys. Connect and
 * every read are bounded by the request's timeout.
 */
@Component
public class AsciiTcpWeighbridgeAdapter implements WeighbridgeAdapter {

    public static final String TYPE = "ascii-tcp";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void validate(Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        if (AsciiFrameParser.str(c.get("host")) == null) {
            throw new IllegalArgumentException("The indicator host is required");
        }
        int port = AsciiFrameParser.integer(c.get("port"), 0);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("The indicator port must be between 1 and 65535");
        }
        int connect = AsciiFrameParser.integer(c.get("connectTimeoutMs"), 2000);
        if (connect < 100 || connect > 30000) {
            throw new IllegalArgumentException("The connect timeout must be between 100 and 30000 ms");
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
        long started = System.nanoTime();
        try (Socket socket = connect(c, timeout, device.code())) {
            Duration left = timeout.minusNanos(System.nanoTime() - started);
            if (left.isNegative() || left.isZero()) {
                throw new WeighbridgeReadException("timeout", "Indicator " + device.code()
                        + " sent no weight within " + Math.max(1, timeout.toSeconds()) + " s");
            }
            String cmd = AsciiFrameParser.str(c.get("requestCommand"));
            if (cmd != null) {
                socket.getOutputStream().write(unescape(cmd).getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            InputStream in = new BufferedInputStream(socket.getInputStream());
            return StableWeightReader.read(StableWeightReader.lines(in, ms -> soTimeout(socket, ms)), parser, left,
                    device.code());
        } catch (IOException e) {
            throw new WeighbridgeReadException("unreachable", "Indicator " + device.code() + " cannot be read: "
                    + e.getMessage(), e);
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
        try (Socket ignored = connect(c, timeout, device.code())) {
            return new DeviceHealth(true, "Connected to " + c.get("host") + ":" + c.get("port"));
        } catch (WeighbridgeReadException | IOException e) {
            return new DeviceHealth(false, e.getMessage());
        }
    }

    private static Socket connect(Map<String, Object> c, Duration timeout, String code) throws IOException {
        String host = AsciiFrameParser.str(c.get("host")).trim();
        int port = AsciiFrameParser.integer(c.get("port"), 0);
        int connect = (int) Math.min(AsciiFrameParser.integer(c.get("connectTimeoutMs"), 2000),
                Math.max(1, timeout.toMillis()));
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connect);
            return socket;
        } catch (SocketTimeoutException e) {
            socket.close();
            throw new WeighbridgeReadException("unreachable", "Indicator " + code + " at " + host + ":" + port
                    + " did not answer within " + connect + " ms", e);
        } catch (IOException e) {
            socket.close();
            throw new WeighbridgeReadException("unreachable", "Indicator " + code + " at " + host + ":" + port
                    + " is unreachable: " + e.getMessage(), e);
        }
    }

    private static void soTimeout(Socket socket, int ms) {
        try {
            socket.setSoTimeout(Math.max(1, ms));
        } catch (SocketException e) {
            throw new WeighbridgeReadException("unreachable", "Socket error: " + e.getMessage(), e);
        }
    }

    static String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t");
    }
}
