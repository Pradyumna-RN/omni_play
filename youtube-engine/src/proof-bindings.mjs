/** WEB_REMIX uses the web-session binding for player proofs too. Keep DATASYNC_ID intact. */
export function proofBindings({ client, videoId, visitorData, dataSyncId, authenticated, contentBoundGvs = false }) {
  const sessionBinding = authenticated ? dataSyncId : visitorData;
  if (!sessionBinding && (client === 'YTMUSIC' || !contentBoundGvs)) throw Object.assign(new Error(authenticated ? 'Reconnect your YouTube account to refresh playback session data.' : 'YouTube did not return visitor session data.'), { code: 'SESSION_DATA', diagnostic: 'runtime verification: missing content binding' });
  return { playerBinding: client === 'YTMUSIC' ? sessionBinding : videoId,
    streamBinding: contentBoundGvs ? videoId : sessionBinding };
}

/** Channel delegation for account headers only; never normalize the proof binding. */
export function delegatedAccount(dataSyncId, explicit = '') {
  if (/^[0-9]{1,64}$/.test(explicit)) return explicit;
  const [delegated, user] = String(dataSyncId || '').split('||');
  return user && /^[0-9]{1,64}$/.test(delegated) ? delegated : undefined;
}

/** Player-context flags are serialized query parameters; the final value wins. */
export function contentBoundGvs(config, client) {
  const contexts = config.WEB_PLAYER_CONTEXT_CONFIGS || {};
  const preferred = client === 'YTMUSIC' ? ['WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_PLAYER'] : ['WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH', 'WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_PLAYLIST'];
  const selected = preferred.map(key => contexts[key]).find(c => typeof c?.serializedExperimentFlags === 'string') ||
    (Object.keys(contexts).length === 1 ? Object.values(contexts)[0] : undefined);
  const flags = new URLSearchParams(selected?.serializedExperimentFlags || '');
  if (flags.has('html5_generate_content_po_token')) return flags.getAll('html5_generate_content_po_token').at(-1) === 'true';
  const fallback = config.EXPERIMENT_FLAGS?.html5_generate_content_po_token;
  return fallback === true || fallback === 'true';
}
