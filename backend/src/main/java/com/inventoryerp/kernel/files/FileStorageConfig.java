package com.inventoryerp.kernel.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/**
 * Storage drivers. {@code local} (default) writes under STORAGE_LOCAL_DIR with path-traversal checks;
 * {@code s3} is a real S3/S3-compatible client (AWS Signature V4 over HTTPS, path-style URLs) configured
 * by S3_BUCKET, S3_REGION, S3_ENDPOINT (optional), S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY.
 */
@Configuration(proxyBeanMethods = false)
public class FileStorageConfig {

    @Bean
    FileStorage fileStorage(
            @Value("${app.storage.driver:local}") String driver,
            @Value("${app.storage.local-dir:./var/uploads}") String localDir,
            @Value("${S3_BUCKET:}") String bucket,
            @Value("${S3_REGION:us-east-1}") String region,
            @Value("${S3_ENDPOINT:}") String endpoint,
            @Value("${S3_ACCESS_KEY_ID:${AWS_ACCESS_KEY_ID:}}") String accessKey,
            @Value("${S3_SECRET_ACCESS_KEY:${AWS_SECRET_ACCESS_KEY:}}") String secretKey) {
        if ("s3".equals(driver)) {
            return new S3Storage(bucket, region, endpoint.isEmpty() ? "https://s3." + region + ".amazonaws.com" : endpoint,
                    accessKey, secretKey);
        }
        return new LocalStorage(Path.of(localDir).toAbsolutePath().normalize());
    }

    /** Files under a root directory. */
    static final class LocalStorage implements FileStorage {
        private final Path root;

        LocalStorage(Path root) {
            this.root = root;
            try {
                Files.createDirectories(root);
            } catch (IOException ignored) {
                // created lazily on first upload
            }
        }

        private Path resolve(String key) {
            Path p = root.resolve(key).normalize();
            if (!p.startsWith(root)) {
                throw new IllegalArgumentException("Invalid storage key");
            }
            return p;
        }

        @Override
        public String driver() {
            return "local";
        }

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            try {
                Path p = resolve(key);
                Files.createDirectories(p.getParent());
                Files.write(p, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public InputStream get(String key) {
            try {
                return Files.newInputStream(resolve(key));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void delete(String key) {
            try {
                Files.deleteIfExists(resolve(key));
            } catch (IOException ignored) {
                // Nest: unlink(...).catch(() => undefined)
            }
        }
    }

    /** Minimal S3 client (PUT/GET/DELETE object) with SigV4. */
    static final class S3Storage implements FileStorage {
        private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        private final String bucket;
        private final String region;
        private final String endpoint;
        private final String accessKey;
        private final String secretKey;
        private final RestClient http = RestClient.create();
        private final java.net.http.HttpClient streamClient = java.net.http.HttpClient.newHttpClient();

        private record Signed(URI uri, String amzDate, String payloadHash, String auth) {}

        S3Storage(String bucket, String region, String endpoint, String accessKey, String secretKey) {
            this.bucket = bucket;
            this.region = region;
            this.endpoint = endpoint.replaceAll("/$", "");
            this.accessKey = accessKey;
            this.secretKey = secretKey;
        }

        @Override
        public String driver() {
            return "s3";
        }

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            send(HttpMethod.PUT, key, bytes, contentType);
        }

        /** Streams the object (DM-0: no longer buffered in memory). */
        @Override
        public InputStream get(String key) {
            Signed signed = sign(HttpMethod.GET, key, new byte[0]);
            var req = java.net.http.HttpRequest.newBuilder(signed.uri()).GET()
                    .header("x-amz-date", signed.amzDate())
                    .header("x-amz-content-sha256", signed.payloadHash())
                    .header("Authorization", signed.auth())
                    .build();
            try {
                var res = streamClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() >= 300) {
                    res.body().close();
                    throw new IllegalStateException("S3 GET " + key + " failed: HTTP " + res.statusCode());
                }
                return res.body();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void delete(String key) {
            try {
                send(HttpMethod.DELETE, key, new byte[0], null);
            } catch (RuntimeException ignored) {
                // best effort, as for local files
            }
        }

        private byte[] send(HttpMethod method, String key, byte[] payload, String contentType) {
            Signed signed = sign(method, key, payload);
            var spec = http.method(method).uri(signed.uri())
                    .header("x-amz-date", signed.amzDate())
                    .header("x-amz-content-sha256", signed.payloadHash())
                    .header("Authorization", signed.auth());
            if (method == HttpMethod.PUT) {
                spec = spec.header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                        .body(payload);
            }
            return spec.retrieve().body(byte[].class);
        }

        private Signed sign(HttpMethod method, String key, byte[] payload) {
            String path = "/" + bucket + "/" + encodePath(key);
            URI uri = URI.create(endpoint + path);
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            String amzDate = AMZ.format(now);
            String date = amzDate.substring(0, 8);
            String payloadHash = hex(sha256(payload));
            String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            String canonicalHeaders = "host:" + host + "\nx-amz-content-sha256:" + payloadHash + "\nx-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalRequest = method.name() + "\n" + uri.getRawPath() + "\n\n" + canonicalHeaders + "\n"
                    + signedHeaders + "\n" + payloadHash;
            String scope = date + "/" + region + "/s3/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                    + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
            byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] kSigning = hmac(hmac(hmac(kDate, region), "s3"), "aws4_request");
            String signature = hex(hmac(kSigning, stringToSign));
            String auth = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;
            return new Signed(uri, amzDate, payloadHash, auth);
        }

        private static String encodePath(String key) {
            StringBuilder sb = new StringBuilder();
            for (String seg : key.split("/")) {
                if (!sb.isEmpty()) {
                    sb.append('/');
                }
                sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            return sb.toString();
        }

        private static byte[] sha256(byte[] data) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(data);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static byte[] hmac(byte[] key, String data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static String hex(byte[] b) {
            return HexFormat.of().formatHex(b);
        }
    }
}
