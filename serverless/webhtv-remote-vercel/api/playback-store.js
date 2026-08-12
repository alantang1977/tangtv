const NULL_VERSION = '__WEBHTV_PLAYBACK_NULL__';
const CAS_SCRIPT = `
local current = redis.call('GET', KEYS[1])
if ARGV[1] == '${NULL_VERSION}' then
  if current then return 0 end
elseif current ~= ARGV[1] then
  return 0
end
redis.call('SET', KEYS[1], ARGV[2])
return 1
`;

export function createVercelRedisPlaybackStore(env = process.env) {
  const url = String(env.KV_REST_API_URL || env.UPSTASH_REDIS_REST_URL || '').trim().replace(/\/+$/, '');
  const token = String(env.KV_REST_API_TOKEN || env.UPSTASH_REDIS_REST_TOKEN || '').trim();
  const prefix = String(env.WEBHTV_PLAYBACK_REDIS_PREFIX || 'webhtv:playback:v1').trim();
  const configured = Boolean(url && token);

  async function command(parts) {
    if (!configured) throw new Error('Playback Redis REST storage is not configured');
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify(parts)
    });
    let payload;
    try {
      payload = await response.json();
    } catch {
      throw new Error(`Playback Redis REST returned HTTP ${response.status}`);
    }
    if (!response.ok || payload.error) throw new Error(payload.error || `Playback Redis REST returned HTTP ${response.status}`);
    return payload.result;
  }

  return {
    persistent: true,
    isConfigured: () => configured,
    async load(spaceKey) {
      const raw = await command(['GET', `${prefix}:${spaceKey}`]);
      if (raw == null) return { version: null, state: null };
      if (typeof raw !== 'string') throw new Error('Playback Redis state is invalid');
      return { version: raw, state: JSON.parse(raw) };
    },
    async compareAndSet(spaceKey, version, state) {
      const raw = JSON.stringify(state);
      const result = await command([
        'EVAL',
        CAS_SCRIPT,
        1,
        `${prefix}:${spaceKey}`,
        version == null ? NULL_VERSION : version,
        raw
      ]);
      return Number(result) === 1;
    }
  };
}
