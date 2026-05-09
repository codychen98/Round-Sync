package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds readable deterministic cache key labels.
 *
 * Format: safeBaseName + "__" + shortStableHash + ext
 */
final class ReadableCacheKey {

    private static final int MAX_FILENAME_LENGTH = 96;
    private static final int SHORT_HASH_CHARS = 12;
    private static final Pattern SANITIZE_PATTERN = Pattern.compile("[^A-Za-z0-9._-]");

    private ReadableCacheKey() {
    }

    @NonNull
    static String fromStablePath(@NonNull String stablePath, @NonNull String namespace) {
        String source = namespace + "|" + stablePath;
        String leaf = stablePath;
        int slash = stablePath.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < stablePath.length()) {
            leaf = stablePath.substring(slash + 1);
        }
        String ext = "";
        String base = leaf;
        int dot = leaf.lastIndexOf('.');
        if (dot > 0 && dot + 1 < leaf.length()) {
            base = leaf.substring(0, dot);
            ext = sanitizeExt(leaf.substring(dot));
        }
        String safeBase = sanitizeBase(base);
        String shortHash = shortSha256Hex(source, SHORT_HASH_CHARS);
        String suffix = "__" + shortHash + ext;
        int maxBaseLen = Math.max(1, MAX_FILENAME_LENGTH - suffix.length());
        if (safeBase.length() > maxBaseLen) {
            safeBase = safeBase.substring(0, maxBaseLen);
        }
        return safeBase + suffix;
    }

    @NonNull
    private static String sanitizeBase(@NonNull String value) {
        String out = SANITIZE_PATTERN.matcher(value).replaceAll("_");
        if (out.isEmpty()) {
            return "item";
        }
        return out;
    }

    @NonNull
    private static String sanitizeExt(@NonNull String ext) {
        String out = SANITIZE_PATTERN.matcher(ext).replaceAll("_");
        if (!out.startsWith(".")) {
            out = "." + out;
        }
        if (out.length() > 12) {
            out = out.substring(0, 12);
        }
        return out;
    }

    @NonNull
    private static String shortSha256Hex(@NonNull String value, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            String full = sb.toString();
            return full.substring(0, Math.min(length, full.length()));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
