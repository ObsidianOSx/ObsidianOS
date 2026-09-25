package android.os;

/**
 * The part of Android that writes an operating system update into the unused half of the phone.
 * Real class: frameworks/base/core/java/android/os/UpdateEngine.java, marked SystemApi, so it is
 * present on every phone but absent from the public SDK. Only the calls OBSIDIAN makes are declared.
 */
public class UpdateEngine {
    public UpdateEngine() { throw new RuntimeException("stub"); }
    public boolean bind(UpdateEngineCallback callback) { throw new RuntimeException("stub"); }
    public void unbind() { throw new RuntimeException("stub"); }
    public void applyPayload(String url, long offset, long size, String[] headerKeyValuePairs) {
        throw new RuntimeException("stub");
    }
    public void cancel() { throw new RuntimeException("stub"); }
    public void resetStatus() { throw new RuntimeException("stub"); }
}
