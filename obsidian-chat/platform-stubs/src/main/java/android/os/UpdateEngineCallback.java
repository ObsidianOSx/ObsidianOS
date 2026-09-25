package android.os;

/** How update_engine reports progress and the final result. See the note in UpdateEngine. */
public abstract class UpdateEngineCallback {
    public abstract void onStatusUpdate(int status, float percent);
    public abstract void onPayloadApplicationComplete(int errorCode);
}
