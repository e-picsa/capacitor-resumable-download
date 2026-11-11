import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'io.picsa.plugins.download.example',
  appName: 'Resumable Download Demo',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
  },
  // Demo-only: lets the WebView probe the local throttled test server over
  // cleartext HTTP (http://10.0.2.2:8080/health) for the reachability pill.
  // Production apps should not enable this. (Must live under `android`:
  // native CapConfig only reads android.allowMixedContent / top-level.)
  android: {
    allowMixedContent: true,
  },
};

export default config;
