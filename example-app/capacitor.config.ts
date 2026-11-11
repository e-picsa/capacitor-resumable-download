import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'io.picsa.plugins.download.example',
  appName: 'Resumable Download Demo',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
  },
};

export default config;
