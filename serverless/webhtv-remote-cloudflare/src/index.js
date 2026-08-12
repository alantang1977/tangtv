import { createRelayState, handleRelayRequest, snapshotRelayState } from './relay.js';
import { handlePlaybackSyncGateway, isPlaybackSyncPath, WebHTVPlaybackSyncDO } from './playback-sync.js';

export { WebHTVPlaybackSyncDO };

const SERVER_NAME = 'Cloudflare Worker Relay';
const RELAY_DO_NAME = 'default';
const RELAY_STATE_KEY = 'relayState';

export class WebHTVRemoteRelayDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.relayState = createRelayState();
    this.ready = state.blockConcurrencyWhile(async () => {
      const snapshot = await state.storage.get(RELAY_STATE_KEY);
      this.relayState = createRelayState(snapshot);
    });
  }

  async fetch(request) {
    await this.ready;
    const response = await handleRelayRequest(request, {
      serverName: SERVER_NAME,
      relayMode: 'cloudflare-durable-object',
      persistentStorage: true,
      playbackSync: Boolean(this.env && this.env.PLAYBACK_DO),
      playbackPersistentStorage: Boolean(this.env && this.env.PLAYBACK_DO),
      state: this.relayState
    });
    await this.saveState();
    return response;
  }

  async saveState() {
    try {
      await this.state.storage.put(RELAY_STATE_KEY, snapshotRelayState(this.relayState));
    } catch (e) {
      console.error('Failed to persist relay state', e && e.message ? e.message : e);
    }
  }
}

export default {
  async fetch(request, env) {
    if (isPlaybackSyncPath(new URL(request.url).pathname)) return handlePlaybackSyncGateway(request, env);
    if (env && env.RELAY_DO) return env.RELAY_DO.getByName(RELAY_DO_NAME).fetch(request);
    return handleRelayRequest(request, {
      serverName: SERVER_NAME,
      playbackSync: Boolean(env && env.PLAYBACK_DO),
      playbackPersistentStorage: Boolean(env && env.PLAYBACK_DO)
    });
  }
};
