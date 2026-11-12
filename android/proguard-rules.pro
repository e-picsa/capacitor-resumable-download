# =============================================================================
# DUAL-COMPATIBILITY NOTICE (Capacitor 8 & Capacitor 9):
# =============================================================================
# Capacitor 9 enables R8 full mode shrinking in release builds by default.
# These rules are distributed via `consumerProguardFiles "proguard-rules.pro"` in build.gradle
# so consuming apps (both Capacitor 8 and Capacitor 9) do not strip reflection-instantiated
# classes during release builds.
#
# Do NOT remove this file or the consumerProguardFiles declaration.
# =============================================================================

# Keep WorkManager worker constructor for reflection instantiation
-keep public class io.picsa.plugins.download.DownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep Capacitor plugin class and public methods for runtime bridge reflection
-keep public class io.picsa.plugins.download.ResumableDownloadPlugin {
    public <init>();
    public *;
}
