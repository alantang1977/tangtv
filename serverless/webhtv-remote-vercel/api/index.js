import { handleRelayRequest } from './relay.js';
import { handlePlaybackSyncRequest, isPlaybackSyncPath } from './playback-sync.js';
import { createVercelRedisPlaybackStore } from './playback-store.js';

export const config = {
  runtime: 'edge'
};

const playbackStore = createVercelRedisPlaybackStore();

export default function handler(request) {
  if (isPlaybackSyncPath(new URL(request.url).pathname)) return handlePlaybackSyncRequest(request, playbackStore);
  return handleRelayRequest(request, {
    serverName: 'Vercel Edge Relay',
    playbackSync: playbackStore.isConfigured(),
    playbackPersistentStorage: playbackStore.persistent && playbackStore.isConfigured()
  });
}
