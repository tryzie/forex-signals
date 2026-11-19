package dev.forexsignals.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * conf.xml loader with the CLEARTEXT to ENCRYPTED rewrite (PLAN §10).
 *
 * Load sequence:
 *  1. Parse the XML with DTDs and external entities disabled.
 *  2. Every element with a TYPE attribute is a secret. TYPE="CLEARTEXT" with a non-empty value is
 *     encrypted in place and switched to TYPE="ENCRYPTED"; TYPE="ENCRYPTED" is decrypted into memory.
 *     Empty CLEARTEXT values mean "not configured" and are left untouched.
 *  3. If anything was encrypted, the file is rewritten atomically (temp file 600 in the same directory,
 *     then rename). Comments and layout are kept because the DOM is written back as-is.
 *
 * Secrets are addressed by element path, e.g. "config/ctrader/clientSecret". Plain settings are read
 * with {@link #attr}. Secret values never appear in logs or exception messages.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    public static final String CONF_ENV = "FOREXSIGNALS_CONF";

    private final Path file;
    private final ConfigCrypto crypto;
    private final Document doc;
    private final Map<String, Secret> secrets;      // path -> decrypted value (may be missing)
    private final Map<String, Element> secretNodes;  // path -> DOM element, for write-back

    private AppConfig(Path file, ConfigCrypto crypto, Document doc, Map<String, Secret> secrets, Map<String, Element> secretNodes) {
        this.file = file;
        this.crypto = crypto;
        this.doc = doc;
        this.secrets = secrets;
        this.secretNodes = secretNodes;
    }

    public static Path defaultConfFile() {
        String env = System.getenv(CONF_ENV);
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".forexsignals", "conf.xml");
    }

    public static AppConfig load(Path file, ConfigCrypto crypto) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("config file not found: " + file
                    + ". Create it from conf.example.xml (see README, 'Configuration').");
        }
        warnIfReadableByOthers(file);

        // 1. Parse securely.
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(file.toFile());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ConfigException("cannot parse " + file + ": " + e.getMessage(), e);
        }
        if (!"config".equals(doc.getDocumentElement().getTagName())) {
            throw new ConfigException(file + ": root element must be <config>");
        }

        // 2. Walk every element; handle the ones carrying TYPE.
        Map<String, Secret> secrets = new LinkedHashMap<>();
        Map<String, Element> nodes = new HashMap<>();
        int encryptedNow = 0;
        List<Element> stack = new ArrayList<>();
        stack.add(doc.getDocumentElement());
        while (!stack.isEmpty()) {
            Element e = stack.removeLast();
            NodeList children = e.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element child) stack.add(child);
            }
            if (!e.hasAttribute("TYPE")) continue;

            String path = pathOf(e);
            if (nodes.containsKey(path)) throw new ConfigException(file + ": secret element " + path + " appears twice");
            nodes.put(path, e);
            String type = e.getAttribute("TYPE");
            String text = e.getTextContent() == null ? "" : e.getTextContent().strip();
            switch (type) {
                case "CLEARTEXT" -> {
                    if (text.isEmpty()) {
                        secrets.put(path, new Secret(null));
                    } else {
                        e.setTextContent(crypto.encrypt(text, path));
                        e.setAttribute("TYPE", "ENCRYPTED");
                        secrets.put(path, new Secret(text));
                        encryptedNow++;
                    }
                }
                case "ENCRYPTED" -> secrets.put(path, new Secret(text.isEmpty() ? null : crypto.decrypt(text, path)));
                default -> throw new ConfigException(file + ": " + path + " has TYPE=\"" + type + "\"; use CLEARTEXT or ENCRYPTED");
            }
        }

        AppConfig cfg = new AppConfig(file, crypto, doc, secrets, nodes);
        // 3. Persist the encrypted form if anything changed.
        if (encryptedNow > 0) {
            cfg.writeAtomically();
            log.info("conf.xml: encrypted {} CLEARTEXT value(s) and rewrote {}", encryptedNow, file);
        }
        return cfg;
    }

    private static String pathOf(Element e) {
        StringBuilder sb = new StringBuilder(e.getTagName());
        Node p = e.getParentNode();
        while (p instanceof Element pe) {
            sb.insert(0, pe.getTagName() + "/");
            p = pe.getParentNode();
        }
        return sb.toString();
    }

    private static void warnIfReadableByOthers(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("{} is readable by group/others ({}); chmod 600 is recommended", file, PosixFilePermissions.toString(perms));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX file system: nothing to check.
        }
    }

    /** Writes the current DOM to a 600 temp file next to the original, then renames it over the original. */
    private synchronized void writeAtomically() {
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, ".conf-", ".xml.tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.STANDALONE, "no");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                t.transform(new DOMSource(doc), new StreamResult(out));
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | TransformerException e) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            }
            throw new ConfigException("cannot rewrite " + file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ accessors

    public Path file() { return file; }

    public Secret secret(String path) {
        return secrets.getOrDefault(path, new Secret(null));
    }

    /** Replaces a secret (e.g. a refreshed cTrader token) and persists it encrypted. */
    public synchronized void updateSecret(String path, String newValue) {
        updateSecrets(Map.of(path, newValue));
    }

    /**
     * Replaces several secrets in ONE atomic file rewrite (e.g. a refreshed access + refresh token pair,
     * which must never be persisted half-updated: the old refresh token is already used up).
     */
    public synchronized void updateSecrets(Map<String, String> values) {
        for (String path : values.keySet()) {
            if (!secretNodes.containsKey(path)) throw new ConfigException("no secret element " + path + " in " + file);
        }
        values.forEach((path, v) -> {
            Element e = secretNodes.get(path);
            e.setTextContent(crypto.encrypt(v, path));
            e.setAttribute("TYPE", "ENCRYPTED");
            secrets.put(path, new Secret(v));
        });
        writeAtomically();
    }

    /** First element at "config/a/b" (path without the root is also accepted: "a/b"). */
    public Optional<Element> element(String path) {
        String[] parts = (path.startsWith("config/") ? path.substring(7) : path).split("/");
        Element cur = doc.getDocumentElement();
        for (String part : parts) {
            Element next = null;
            NodeList children = cur.getChildNodes();
            for (int i = 0; i < children.getLength() && next == null; i++) {
                if (children.item(i) instanceof Element c && c.getTagName().equals(part)) next = c;
            }
            if (next == null) return Optional.empty();
            cur = next;
        }
        return Optional.of(cur);
    }

    /** All direct children with a given tag under an element path, e.g. ("costs", "commission"). */
    public List<Element> children(String path, String tag) {
        List<Element> out = new ArrayList<>();
        element(path).ifPresent(parent -> {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element c && c.getTagName().equals(tag)) out.add(c);
            }
        });
        return out;
    }

    public Optional<String> attr(String path, String name) {
        return element(path).map(e -> e.getAttribute(name)).filter(v -> !v.isBlank()).map(String::strip);
    }

    public String attr(String path, String name, String dflt) { return attr(path, name).orElse(dflt); }

    public int intAttr(String path, String name, int dflt) {
        String v = attr(path, name, null);
        if (v == null) return dflt;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) {
            throw new ConfigException(file + ": " + path + "@" + name + " must be an integer, got '" + v + "'");
        }
    }

    public double doubleAttr(String path, String name, double dflt) {
        String v = attr(path, name, null);
        if (v == null) return dflt;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) {
            throw new ConfigException(file + ": " + path + "@" + name + " must be a number, got '" + v + "'");
        }
    }

    // ------------------------------------------------------------------ typed sections used in Phase 1

    public record Database(String host, int port, String name, String user, Secret password, int poolSize) {
        public String jdbcUrl() { return "jdbc:postgresql://" + host + ":" + port + "/" + name; }
    }

    public record Deriv(String endpoint, String appId) {}

    public record CTrader(String host, int port, String accountNumber, Secret clientId, Secret clientSecret,
                          Secret accessToken, Secret refreshToken) {
        public boolean credentialsPresent() {
            return tokensPresent() && !accountNumber.isBlank();
        }

        /** App credentials and token present (the account number may still be missing). */
        public boolean tokensPresent() {
            return clientId.isSet() && clientSecret.isSet() && accessToken.isSet();
        }
    }

    public record Health(int staleSecFx, int staleSecMetal, int checkEverySec) {}

    public record Recording(int batchSize, int flushMs, int tickRetentionDays) {}

    public record Import(int years, double maxReqPerSec, int maxAttempts, int backoffMaxSec) {}

    public Database database() {
        Secret pw = secret("config/database/password");
        if (!pw.isSet()) throw new ConfigException(file + ": <database><password> is empty");
        return new Database(attr("database", "host", "localhost"), intAttr("database", "port", 5432),
                attr("database", "name", "forex"), attr("database", "user", "forex"), pw, intAttr("database", "poolSize", 8));
    }

    public String primaryFeed() { return attr("feed", "primary", "deriv"); }

    public Deriv deriv() {
        return new Deriv(attr("deriv", "endpoint", "wss://api.derivws.com/trading/v1/options/ws/public"), attr("deriv", "appId", ""));
    }

    public CTrader ctrader() {
        return new CTrader(attr("ctrader", "host", "demo.ctraderapi.com"), intAttr("ctrader", "port", 5035),
                attr("ctrader", "accountNumber", ""),
                secret("config/ctrader/clientId"), secret("config/ctrader/clientSecret"),
                secret("config/ctrader/accessToken"), secret("config/ctrader/refreshToken"));
    }

    public int candleGraceSec() { return intAttr("candles", "graceSec", 2); }

    public Health health() {
        return new Health(intAttr("health", "staleSecFx", 60), intAttr("health", "staleSecMetal", 120), intAttr("health", "checkEverySec", 5));
    }

    public Recording recording() {
        return new Recording(intAttr("recording", "batchSize", 500), intAttr("recording", "flushMs", 1000), intAttr("recording", "tickRetentionDays", 90));
    }

    public Import importSettings() {
        double rate = doubleAttr("import", "maxReqPerSec", 4.0);
        if (rate <= 0 || rate > 4.0) throw new ConfigException(file + ": import@maxReqPerSec must be in (0, 4] (cTrader history limit is 5/s)");
        return new Import(intAttr("import", "years", 3), rate, intAttr("import", "maxAttempts", 8), intAttr("import", "backoffMaxSec", 60));
    }

    public Optional<Path> symbolsFile() { return attr("symbols", "file").map(Path::of); }
}
