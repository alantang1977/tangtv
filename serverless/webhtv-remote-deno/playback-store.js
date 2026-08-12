const CHUNK_BYTES = 48 * 1024;
let kvPromise;

export function createDenoKvPlaybackStore() {
  const configured = typeof globalThis.Deno !== 'undefined' && typeof globalThis.Deno.openKv === 'function';
  return {
    persistent: true,
    isConfigured: () => configured,
    async load(spaceKey) {
      const kv = await openKv();
      const manifestKey = keyFor(spaceKey, 'manifest');
      const entry = await kv.get(manifestKey);
      if (!entry.value) return { version: { versionstamp: entry.versionstamp, dataVersion: '', chunks: 0 }, state: null };
      const manifest = entry.value;
      if (!manifest || typeof manifest.dataVersion !== 'string' || !Number.isSafeInteger(manifest.chunks) || manifest.chunks <= 0) {
        throw new Error('Invalid playback manifest');
      }
      const chunks = await Promise.all(Array.from({ length: manifest.chunks }, async (_, index) => {
        const chunk = await kv.get(keyFor(spaceKey, 'data', manifest.dataVersion, index));
        if (!(chunk.value instanceof Uint8Array)) throw new Error('Playback data chunk is missing');
        return chunk.value;
      }));
      const size = chunks.reduce((total, item) => total + item.byteLength, 0);
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      return {
        version: { versionstamp: entry.versionstamp, dataVersion: manifest.dataVersion, chunks: manifest.chunks },
        state: JSON.parse(new TextDecoder().decode(bytes))
      };
    },
    async compareAndSet(spaceKey, version, state) {
      const kv = await openKv();
      const bytes = new TextEncoder().encode(JSON.stringify(state));
      const chunks = [];
      for (let offset = 0; offset < bytes.byteLength; offset += CHUNK_BYTES) chunks.push(bytes.slice(offset, offset + CHUNK_BYTES));
      if (!chunks.length) chunks.push(new Uint8Array());
      const dataVersion = crypto.randomUUID();
      await Promise.all(chunks.map((chunk, index) => kv.set(keyFor(spaceKey, 'data', dataVersion, index), chunk)));
      const manifestKey = keyFor(spaceKey, 'manifest');
      const result = await kv.atomic()
        .check({ key: manifestKey, versionstamp: version?.versionstamp ?? null })
        .set(manifestKey, { dataVersion, chunks: chunks.length })
        .commit();
      if (!result.ok) {
        await deleteChunks(kv, spaceKey, dataVersion, chunks.length);
        return false;
      }
      if (version?.dataVersion && version.chunks > 0) await deleteChunks(kv, spaceKey, version.dataVersion, version.chunks);
      return true;
    }
  };
}

function openKv() {
  if (!kvPromise) kvPromise = globalThis.Deno.openKv();
  return kvPromise;
}

function keyFor(spaceKey, ...parts) {
  return ['webhtv', 'playback', 'v1', spaceKey, ...parts];
}

async function deleteChunks(kv, spaceKey, dataVersion, count) {
  await Promise.all(Array.from({ length: count }, (_, index) => kv.delete(keyFor(spaceKey, 'data', dataVersion, index))));
}
