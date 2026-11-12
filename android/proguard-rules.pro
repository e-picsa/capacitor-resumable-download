# ProGuard / R8 rules for @picsa/capacitor-resumable-download

# Keep WorkManager worker constructor for reflection instantiation
-keep public class io.picsa.plugins.download.DownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep Capacitor plugin class and public methods
-keep public class io.picsa.plugins.download.ResumableDownloadPlugin {
    public <init>();
    public *;
}
