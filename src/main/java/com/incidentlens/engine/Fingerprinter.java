package com.incidentlens.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import com.incidentlens.model.TelemetryEvent;

/**
 * Collapses error events that differ only in variable data (ids, numbers, hosts) into one group.
 *
 * <p>The fingerprint is built from service + exception type + peer + either the top in-app stack frames or,
 * without a stack trace, the message template. "Order 8812 failed after 2003ms" and
 * "Order 9120 failed after 1998ms" become the same template: "Order &lt;num&gt; failed after &lt;num&gt;ms".
 */
public final class Fingerprinter {

    private static final List<Rule> RULES = List.of(
            new Rule("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>"),
            new Rule("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?", "<ts>"),
            new Rule("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b", "<ip>"),
            new Rule("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "<email>"),
            new Rule("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{12,}\\b", "<hex>"),
            new Rule("'[^']*'|\"[^\"]*\"", "<str>"),
            new Rule("(?<![A-Za-z])\\d+(\\.\\d+)?", "<num>"),
            new Rule("\\s+", " "));

    private static final int FRAMES = 3;

    private final List<String> inAppPrefixes;

    public Fingerprinter(List<String> inAppPrefixes) {
        this.inAppPrefixes = List.copyOf(inAppPrefixes);
    }

    public String template(String message) {
        if (message == null) {
            return "";
        }
        String t = message;
        for (Rule rule : RULES) {
            t = rule.pattern.matcher(t).replaceAll(rule.replacement);
        }
        return t.trim();
    }

    public String fingerprint(TelemetryEvent e) {
        StringBuilder key = new StringBuilder()
                .append(e.service()).append('|')
                .append(e.exceptionType() == null ? "" : e.exceptionType()).append('|')
                // Failed calls to different dependencies usually share the same client stack frames, but they
                // are different problems, so the peer is part of the identity.
                .append(e.peerService() == null ? "" : e.peerService()).append('|');
        List<String> frames = significantFrames(e.stackFrames());
        if (frames.isEmpty()) {
            key.append(template(e.message()));
        } else {
            frames.forEach(f -> key.append(f).append(';'));
        }
        return sha1(key.toString()).substring(0, 12);
    }

    private List<String> significantFrames(List<String> frames) {
        List<String> inApp = frames.stream()
                .filter(f -> inAppPrefixes.stream().anyMatch(f::startsWith))
                .map(Fingerprinter::stripLineNumber)
                .limit(FRAMES)
                .toList();
        return inApp.isEmpty() ? frames.stream().map(Fingerprinter::stripLineNumber).limit(FRAMES).toList() : inApp;
    }

    /** Line numbers change with every deploy; the method doesn't. */
    private static String stripLineNumber(String frame) {
        return frame.replaceAll(":\\d+\\)", ")");
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Rule(Pattern pattern, String replacement) {
        Rule(String regex, String replacement) {
            this(Pattern.compile(regex), replacement);
        }
    }
}
