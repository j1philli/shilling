(() => {
  if (window.ShillingDesktopUpdates) return;
  let ready;
  window.ShillingDesktopUpdates = { open: () => ready?.then(() => open()) };
  const start = async () => {
    const { invoke } = window.__TAURI__.core;
    const { listen } = window.__TAURI__.event;
    const status = await invoke('desktop_update_status');
    let channel = 'stable';
    try { if (localStorage.getItem('shilling.update-channel') === 'beta') channel = 'beta'; } catch {}
    let candidate = null;
    let busy = false;
    let installing = false;
    let checked = false;
    let previousFocus;
    const dialog = document.createElement('dialog');
    dialog.setAttribute('aria-labelledby', 'shilling-updates-title');
    dialog.innerHTML = `<style>
      #shilling-updates { box-sizing:border-box; width:min(440px,calc(100vw - 40px)); padding:28px; border:1px solid #d8dedb; border-radius:18px; color:#172720; background:#fff; font:15px/1.5 system-ui,sans-serif; box-shadow:0 24px 80px #0003; }
      #shilling-updates::backdrop {background:#10241b66}
      #shilling-updates h2 {margin:0 0 8px;font-size:23px}
      #shilling-updates p {margin:8px 0}
      #shilling-updates label {display:flex;align-items:center;gap:10px;margin:20px 0 4px}
      #shilling-updates small {display:block;color:#53645a}
      #shilling-updates pre {white-space:pre-wrap;font:inherit;max-height:180px;overflow:auto}
      #shilling-updates .actions {display:flex;justify-content:flex-end;flex-wrap:wrap;gap:10px;margin-top:24px}
      #shilling-updates button {font:inherit;padding:9px 14px;border-radius:9px;border:1px solid #c8d3cc;background:#fff;color:inherit;cursor:pointer}
      #shilling-updates button.primary {background:#176d4d;color:#fff;border-color:#176d4d}
      #shilling-updates button:disabled {opacity:.55;cursor:default}
      #shilling-updates button:focus-visible, #shilling-updates input:focus-visible {outline:3px solid #47ac85;outline-offset:3px}
      @media(prefers-color-scheme:dark) {#shilling-updates{color:#e6efe9;background:#18241e;border-color:#46594c}#shilling-updates small{color:#b1c5b7}#shilling-updates button{background:#263a2e;border-color:#607466}}
    </style>
    <h2 id="shilling-updates-title">Shilling updates</h2>
    <p data-current></p>
    <label><input type="checkbox" data-beta> Get beta updates</label>
    <small>Try new versions before they reach the stable channel. Turning this off waits for a newer stable release.</small>
    <p data-message role="status" aria-live="polite"></p>
    <pre data-notes hidden></pre>
    <div class="actions"><button data-close>Later</button><button data-check>Check for updates</button><button class="primary" data-install hidden>Install and restart</button></div>`;
    dialog.id = 'shilling-updates';
    document.body.appendChild(dialog);
    const el = key => dialog.querySelector(`[data-${key}]`);
    el('current').textContent = `Version ${status.version} · Build ${status.build}`;
    el('beta').checked = channel === 'beta';
    const message = text => { el('message').textContent = text; };
    const controls = () => {
      el('check').disabled = busy || !status.supported;
      el('beta').disabled = busy || !status.supported;
      el('install').disabled = busy;
      el('install').hidden = !candidate;
      el('close').disabled = installing;
    };
    const show = () => {
      if (!dialog.open) { previousFocus = document.activeElement; dialog.showModal(); }
    };
    const check = async (manual) => {
      if (busy || !status.supported) return;
      busy = true; candidate = null; checked = true;
      el('notes').hidden = true; message('Checking for updates…'); controls();
      try {
        candidate = await invoke('desktop_update_check', { channel });
        if (candidate) {
          message(`Version ${candidate.version} is ready to install.`);
          el('notes').textContent = candidate.notes;
          el('notes').hidden = !candidate.notes;
          show();
        } else message(`You’re up to date on the ${channel} channel.`);
      } catch (error) {
        console.error('Desktop update check failed', error);
        message('Couldn’t check for updates. Check your connection and try again.');
        if (manual) show();
      } finally { busy = false; controls(); }
    };
    open = () => { show(); if (!checked && status.supported) check(true); };
    el('check').onclick = () => check(true);
    el('beta').onchange = () => {
      channel = el('beta').checked ? 'beta' : 'stable';
      try { localStorage.setItem('shilling.update-channel', channel); } catch {}
      check(true);
    };
    el('close').onclick = () => dialog.close();
    dialog.addEventListener('cancel', event => { if (installing) event.preventDefault(); });
    dialog.addEventListener('close', () => previousFocus?.focus());
    el('install').onclick = async () => {
      if (!candidate || busy) return;
      busy = true; installing = true; controls(); message('Downloading and verifying the update…');
      try {
        await invoke('desktop_update_install', { version: candidate.version });
        message('Restarting Shilling…');
      } catch (error) {
        console.error('Desktop update install failed', error);
        message('The update could not be installed. Your current version is still available. Try again.');
        busy = false; installing = false; controls();
      }
    };
    await listen('desktop-update-open', () => open());
    await listen('desktop-update-progress', ({ payload }) => {
      const amount = payload.total ? `${Math.min(100, Math.round(100 * payload.received / payload.total))}%` : `${Math.round(payload.received / 1048576)} MB`;
      message(`Downloading update… ${amount}`);
    });
    if (!status.supported) message('Use an installed desktop release to receive updates. On Linux, use AppImage or update through your package manager.');
    controls();
    if (status.supported) {
      setTimeout(() => check(false), 15000);
      setInterval(() => { if (document.visibilityState === 'visible') check(false); }, 6 * 60 * 60 * 1000);
    }
  };
  let open = () => {};
  const initialize = () => { ready = start().catch(error => console.error('Desktop update setup failed', error)); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
  else initialize();
})();
