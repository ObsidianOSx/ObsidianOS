package android.os;

/**
 * The phone's build properties, including the one that says which OBSIDIAN release is installed.
 * Real class: frameworks/base/core/java/android/os/SystemProperties.java, hidden from the public
 * SDK. Only the read that OBSIDIAN needs is declared.
 */
public class SystemProperties {
    public static String get(String key, String def) { throw new RuntimeException("stub"); }
}
