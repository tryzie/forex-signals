package dev.forexsignals.market;

import dev.forexsignals.config.ConfigException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The symbol catalogue from config/symbols.xml (bundled in the jar; conf.xml may point at another file).
 * Holds roles, clusters, default digits, trading-hours profile and the per-feed broker names.
 */
public final class SymbolCatalog {

    public enum Role { SIGNAL, CONTEXT, DATA_ONLY }

    public record FeedName(String feed, String brokerSymbol, boolean required) {}

    public record SymbolDef(String code, Role role, String assetClass, String base, String quote, int digits,
                            MarketHours hours, String cluster, boolean verified, Map<String, FeedName> feeds) {
        /** Smallest price increment at the default digits (the feed spec can override digits). */
        public double pointSize() { return Math.pow(10, -digits); }

        public Optional<FeedName> on(String feed) { return Optional.ofNullable(feeds.get(feed)); }

        /** Same symbol with the broker's own trading schedule (from its spec) instead of the default profile. */
        public SymbolDef withHours(MarketHours brokerHours) {
            return new SymbolDef(code, role, assetClass, base, quote, digits, brokerHours, cluster, verified, feeds);
        }

        /** Same symbol with the price digits the feed reports (the feed's spec wins over symbols.xml). */
        public SymbolDef withDigits(int feedDigits) {
            return new SymbolDef(code, role, assetClass, base, quote, feedDigits, hours, cluster, verified, feeds);
        }
    }

    private final Map<String, SymbolDef> byCode;

    private SymbolCatalog(Map<String, SymbolDef> byCode) { this.byCode = byCode; }

    public static SymbolCatalog load(Optional<Path> file) {
        try (InputStream in = file.isPresent() ? Files.newInputStream(file.get())
                : SymbolCatalog.class.getResourceAsStream("/config/symbols.xml")) {
            if (in == null) throw new ConfigException("bundled config/symbols.xml missing from the jar");
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(in);

            Map<String, SymbolDef> out = new LinkedHashMap<>();
            NodeList symbols = doc.getDocumentElement().getElementsByTagName("symbol");
            for (int i = 0; i < symbols.getLength(); i++) {
                Element s = (Element) symbols.item(i);
                String code = s.getAttribute("code");
                Map<String, FeedName> feeds = new LinkedHashMap<>();
                NodeList fl = s.getElementsByTagName("feed");
                for (int j = 0; j < fl.getLength(); j++) {
                    Element fe = (Element) fl.item(j);
                    feeds.put(fe.getAttribute("name"), new FeedName(fe.getAttribute("name"), fe.getAttribute("symbol"),
                            Boolean.parseBoolean(fe.getAttribute("required"))));
                }
                SymbolDef def = new SymbolDef(code, Role.valueOf(s.getAttribute("role")), s.getAttribute("class"),
                        s.getAttribute("base"), s.getAttribute("quote"), Integer.parseInt(s.getAttribute("digits")),
                        TradingHours.valueOf(s.getAttribute("hours")),
                        s.getAttribute("cluster").isBlank() ? null : s.getAttribute("cluster"),
                        !s.hasAttribute("verified") || Boolean.parseBoolean(s.getAttribute("verified")),
                        Map.copyOf(feeds));
                if (out.put(code, def) != null) throw new ConfigException("symbols.xml: duplicate symbol " + code);
            }
            return new SymbolCatalog(out);
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException("cannot load symbols.xml: " + e.getMessage(), e);
        }
    }

    public SymbolDef get(String code) {
        SymbolDef d = byCode.get(code);
        if (d == null) throw new IllegalArgumentException("unknown symbol " + code);
        return d;
    }

    public List<SymbolDef> all() { return List.copyOf(byCode.values()); }

    /** Symbols that have a mapping on this feed, optionally filtered by a comma list from the command line. */
    public List<SymbolDef> onFeed(String feed, Optional<String> commaList) {
        List<SymbolDef> out = new ArrayList<>();
        List<String> wanted = commaList.map(s -> List.of(s.split(","))).orElse(null);
        for (SymbolDef d : byCode.values()) {
            if (d.on(feed).isEmpty()) continue;
            if (wanted != null && !wanted.contains(d.code())) continue;
            out.add(d);
        }
        if (wanted != null) {
            for (String w : wanted) if (!byCode.containsKey(w)) throw new ConfigException("unknown symbol in --symbols: " + w);
        }
        return out;
    }
}
