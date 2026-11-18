package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Detector models and edge agent releases this installation offers (ADR-S077 §7), from configuration:
 * {@code LP_VISION_MODELS} and {@code LP_EDGE_RELEASES} are JSON arrays, {@code LP_VISION_DEFAULT_MODEL} names the model
 * an agent gets unless it is set to another. Both are signed by the vendor; the agent checks the signature against its
 * trust key before it uses either, so a wrong entry here is refused on the box, never run.
 *
 * <p>Empty by default: no detector is shipped until its licence is cleared, and an agent then runs the methods that
 * need none (drawer reference picture, motion).
 */
@Component
public class LpModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(LpModelCatalog.class);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,99}");

    /** A detector model: an ONNX file the agent downloads and checks. */
    public record Model(String version, String detector, String url, String sha256, String signature, int inputSize,
            List<String> classes) {

        Map<String, Object> view() {
            return Obj.of("version", version, "detector", detector, "url", url, "sha256", sha256, "signature",
                    signature, "inputSize", inputSize, "classes", classes);
        }
    }

    /** An agent release: a container image pinned by digest. */
    public record Release(String version, String image, String digest, String signature) {

        Map<String, Object> view() {
            return Obj.of("version", version, "image", image, "digest", digest, "signature", signature);
        }
    }

    private final List<Model> models;
    private final List<Release> releases;
    private final String defaultModel;

    public LpModelCatalog(@Value("${app.lossprevention.vision.models:[]}") String models,
            @Value("${app.lossprevention.vision.releases:[]}") String releases,
            @Value("${app.lossprevention.vision.default-model:}") String defaultModel, JsonMapper mapper) {
        this.models = parseModels(models, mapper);
        this.releases = parseReleases(releases, mapper);
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? null : defaultModel.trim();
        if (this.defaultModel != null && model(this.defaultModel) == null) {
            log.error("LP_VISION_DEFAULT_MODEL '{}' is not in LP_VISION_MODELS; agents get no model", this.defaultModel);
        }
    }

    private static JsonNode array(String json, String name, JsonMapper mapper) {
        try {
            JsonNode node = mapper.readTree(json == null || json.isBlank() ? "[]" : json);
            if (node.isArray()) {
                return node;
            }
        } catch (RuntimeException e) {
            // reported below
        }
        log.error("{} is not a JSON array; it is ignored", name);
        return mapper.createArrayNode();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isString() || v.asString().isBlank() ? null : v.asString();
    }

    private static List<Model> parseModels(String json, JsonMapper mapper) {
        List<Model> out = new ArrayList<>();
        for (JsonNode n : array(json, "LP_VISION_MODELS", mapper)) {
            String version = text(n, "version");
            String url = text(n, "url");
            String sha = text(n, "sha256");
            String signature = text(n, "signature");
            if (version == null || !VERSION.matcher(version).matches() || url == null || !url.startsWith("https://")
                    || sha == null || !SHA256.matcher(sha).matches() || signature == null) {
                log.error("LP_VISION_MODELS entry {} is incomplete (version, https url, sha256, signature); ignored",
                        version);
                continue;
            }
            List<String> classes = new ArrayList<>();
            if (n.get("classes") != null && n.get("classes").isArray()) {
                n.get("classes").forEach(c -> classes.add(c.asString()));
            }
            int inputSize = n.get("inputSize") != null && n.get("inputSize").isNumber() ? n.get("inputSize").asInt()
                    : 640;
            out.add(new Model(version, text(n, "detector") == null ? "yolox" : text(n, "detector"), url, sha,
                    signature, inputSize, List.copyOf(classes)));
        }
        return List.copyOf(out);
    }

    private static List<Release> parseReleases(String json, JsonMapper mapper) {
        List<Release> out = new ArrayList<>();
        for (JsonNode n : array(json, "LP_EDGE_RELEASES", mapper)) {
            String version = text(n, "version");
            String image = text(n, "image");
            String digest = text(n, "digest");
            String signature = text(n, "signature");
            if (version == null || !VERSION.matcher(version).matches() || image == null || digest == null
                    || !digest.startsWith("sha256:") || signature == null) {
                log.error("LP_EDGE_RELEASES entry {} is incomplete (version, image, sha256 digest, signature);"
                        + " ignored", version);
                continue;
            }
            out.add(new Release(version, image, digest, signature));
        }
        return List.copyOf(out);
    }

    public Model model(String version) {
        return version == null ? null : models.stream().filter(m -> m.version().equals(version)).findFirst()
                .orElse(null);
    }

    public Release release(String version) {
        return version == null ? null : releases.stream().filter(r -> r.version().equals(version)).findFirst()
                .orElse(null);
    }

    /** The model an agent runs: its own target, else the installation's default, else none. */
    public Map<String, Object> modelFor(String desired) {
        Model m = model(desired != null ? desired : defaultModel);
        return m == null ? null : m.view();
    }

    public Map<String, Object> releaseFor(String desired) {
        Release r = release(desired);
        return r == null ? null : r.view();
    }

    /** {@code GET /loss-prevention/models}. */
    public Map<String, Object> view() {
        return Obj.of("models", models.stream().map(Model::view).toList(), "releases",
                releases.stream().map(Release::view).toList(), "defaultModelVersion", defaultModel);
    }
}
