import { registerPlugin } from '@capacitor/core';
import type { ResumableDownloadPlugin } from './definitions';

const ResumableDownload = registerPlugin<ResumableDownloadPlugin>('ResumableDownload', {
  web: () => import('./web').then((m) => new m.ResumableDownloadWeb()),
});

export * from './definitions';
export { ResumableDownload };
