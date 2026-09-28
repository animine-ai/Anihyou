package de.kiyori.ep01;

import android.content.Context;
import android.os.Debug;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** JNI facade for the isolated EP01 probe; guest handles never escape a call. */
public final class RuntimeChecks {
    static { System.loadLibrary("ep01_runtime"); }
    private RuntimeChecks() {}

    private static native String nativeRun(byte[] wasm, byte[] planInput, byte[] planOutput,
                                          byte[] parseInput, byte[] parseOutput);
    private static native void nativeSpin(byte[] wasm, Runnable onStarted);

    public static JSONObject run(Context app) throws Exception {
        long heapBefore = usedHeap();
        JSONObject report = new JSONObject(nativeRun(asset(app, "provider.wasm"),
            asset(app, "plan-input.json"), asset(app, "plan-output.json"),
            asset(app, "parse-input.json"), asset(app, "parse-output.json")));
        report.put("javaHeapBefore", heapBefore).put("javaHeapAfter", usedHeap())
            .put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize());
        JSONArray checks = report.getJSONArray("checks");
        for (int n = 0; n < checks.length(); n++) {
            android.util.Log.i("EP01", "PASS " + checks.getString(n));
        }
        return report;
    }

    static void spinWithoutListener(Context app, Runnable onStarted) throws Exception {
        nativeSpin(asset(app, "provider.wasm"), onStarted);
        throw new AssertionError("unmetered guest loop returned");
    }

    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static byte[] asset(Context app, String name) throws IOException {
        try (InputStream in = app.getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 1048576) throw new IOException("asset bound");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
