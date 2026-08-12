import { handleRelayRequest } from './relay.js';
import { handlePlaybackSyncRequest, isPlaybackSyncPath } from './playback-sync.js';
import { createDenoKvPlaybackStore } from './playback-store.js';

const playbackStore = createDenoKvPlaybackStore();

Deno.serve((request) => {
  if (isPlaybackSyncPath(new URL(request.url).pathname)) return handlePlaybackSyncRequest(request, playbackStore);
  return handleRelayRequest(request, {
    serverName: 'Deno Deploy Relay',
    playbackSync: playbackStore.isConfigured(),
    playbackPersistentStorage: playbackStore.persistent && playbackStore.isConfigured()
  });
});
