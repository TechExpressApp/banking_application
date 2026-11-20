'use strict';

/* ================= Config & state ================= */
const CURRENCY = 'INR';
const money = new Intl.NumberFormat(undefined, { style: 'currency', currency: CURRENCY });
const curSymbol = money.formatToParts(0).find(p => p.type === 'currency')?.value || '';

const state = {
  token: store('get', 'token'),
  customer: null,
  accountsByNo: new Map(),   // every account in the bank, for resolving names
};

const ROUTES = {
  login:          { title: 'Log in',                   guest: true },
  register:       { title: 'Create your account',      guest: true },
  dashboard:      { title: 'Dashboard',                auth: true, load: loadDashboard },
  send:           { title: 'Send money',               auth: true, load: loadSend },
  beneficiaries:  { title: 'Beneficiaries',            auth: true, load: loadBeneficiaries },
  'bank-transfer':{ title: 'Bank transfer',            load: loadBankTransfer },
  accounts:       { title: 'All accounts',             load: loadAccounts },
};

/* ================= Helpers ================= */
function store(op, key, val) {
  try {
    if (op === 'get') return sessionStorage.getItem(key);
    if (op === 'set') sessionStorage.setItem(key, val);
    if (op === 'del') sessionStorage.removeItem(key);
  } catch (_) { return null; }
}

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

function el(tag, attrs = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else if (k === 'html') n.innerHTML = v;          // only used with static SVG strings
    else n.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null) n.append(c instanceof Node ? c : String(c));
  return n;
}

const ICONS = {
  in:    '<svg viewBox="0 0 24 24"><path d="M17 7L7 17M7 17h8M7 17V9"/></svg>',
  out:   '<svg viewBox="0 0 24 24"><path d="M7 17L17 7M17 7H9M17 7v8"/></svg>',
  xfer:  '<svg viewBox="0 0 24 24"><path d="M7 16V4M7 4L3 8M7 4l4 4M17 8v12M17 20l4-4M17 20l-4-4"/></svg>',
  send:  '<svg viewBox="0 0 24 24"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6"/></svg>',
};

const AVATAR_COLORS = ['#2650e8', '#7c3aed', '#0f8a5f', '#c2410c', '#be185d', '#0e7490', '#4d7c0f', '#b45309'];
function initials(name = '?') { return name.trim().split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase(); }
function avatar(name) {
  let h = 0; for (const ch of name || '') h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return el('span', { class: 'avatar', style: `background:${AVATAR_COLORS[h % AVATAR_COLORS.length]}` }, initials(name));
}

function when(iso) {
  const d = new Date(iso), now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  return sameDay ? 'Today, ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                 : d.toLocaleString([], { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function holder(accNo) { return state.accountsByNo.get(accNo)?.holderName || accNo; }

function toast(msg, err = false) {
  const t = el('div', { class: 'toast' + (err ? ' err' : '') }, msg);
  $('#toasts').append(t);
  setTimeout(() => t.remove(), 4200);
}

function setError(id, msg) { $('#' + id).textContent = msg || ''; }

async function api(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (state.token) headers.Authorization = 'Bearer ' + state.token;
  let res;
  try {
    res = await fetch('/api' + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  } catch (_) {
    throw new Error('Cannot reach the server. Is the backend running?');
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && state.token && path !== '/auth/login') {
    setSession(null, null);
    toast('Your session ended. Please log in again.', true);
    location.hash = '#/login';
  }
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

async function refreshDirectory() {
  const accounts = await api('/accounts');
  state.accountsByNo = new Map(accounts.map(a => [a.accountNumber, a]));
  return accounts;
}

function busy(form, on) { $$('button[type=submit]', form).forEach(b => b.disabled = on); }

function formData(form) { return Object.fromEntries(new FormData(form).entries()); }

/* ================= Modal ================= */
function dialog({ title, amount, rows = [], ok = 'Confirm', cancel = 'Cancel', icon = 'send', success = false }) {
  return new Promise(resolve => {
    const m = $('#modal');
    $('#modalTitle').textContent = title;
    $('#modalAmount').textContent = amount || '';
    $('#modalIcon').innerHTML = ICONS[icon];
    $('#modalIcon').className = 'modal-icon' + (success ? ' success' : '');
    $('#modalRows').replaceChildren(...rows.flatMap(([k, v]) => [el('dt', {}, k), el('dd', {}, v)]));
    $('#modalOk').textContent = ok;
    $('#modalCancel').hidden = !cancel;
    $('#modalCancel').textContent = cancel || '';
    m.hidden = false;
    $('#modalOk').focus();
    const done = (v) => { m.hidden = true; cleanup(); resolve(v); };
    const onOk = () => done(true), onCancel = () => done(false);
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    const onBackdrop = (e) => { if (e.target === m) done(false); };
    $('#modalOk').addEventListener('click', onOk);
    $('#modalCancel').addEventListener('click', onCancel);
    document.addEventListener('keydown', onKey);
    m.addEventListener('click', onBackdrop);
    function cleanup() {
      $('#modalOk').removeEventListener('click', onOk);
      $('#modalCancel').removeEventListener('click', onCancel);
      document.removeEventListener('keydown', onKey);
      m.removeEventListener('click', onBackdrop);
    }
  });
}

function receipt(t) {
  return dialog({
    title: 'Transfer successful', amount: money.format(t.amount), icon: 'check', success: true,
    ok: 'Done', cancel: null,
    rows: [['From', `${holder(t.fromAccount)} · ${t.fromAccount}`], ['To', `${holder(t.toAccount)} · ${t.toAccount}`],
           ['Reference', t.transactionId], ['Time', new Date(t.timestamp).toLocaleString()],
           ...(t.note ? [['Note', t.note]] : [])],
  });
}

/* ================= Session & routing ================= */
function setSession(token, customer) {
  state.token = token; state.customer = customer;
  token ? store('set', 'token', token) : store('del', 'token');
  const inn = !!token;
  $$('[data-auth]').forEach(e => e.hidden = !inn);
  $$('[data-guest]').forEach(e => e.hidden = inn);
  if (customer) {
    $('#userName').textContent = customer.fullName;
    $('#userId').textContent = customer.customerId + ' · ' + customer.email;
    $('#userAvatar').replaceWith(Object.assign(avatar(customer.fullName), { id: 'userAvatar' }));
  }
}

function route() {
  let name = location.hash.replace(/^#\/?/, '') || (state.token ? 'dashboard' : 'login');
  if (!ROUTES[name]) name = state.token ? 'dashboard' : 'login';
  const r = ROUTES[name];
  if (r.auth && !state.token) { location.replace('#/login'); return; }
  if (r.guest && state.token) { location.replace('#/dashboard'); return; }

  $$('.view').forEach(v => v.classList.toggle('active', v.dataset.view === name));
  $$('.nav a').forEach(a => a.classList.toggle('active', a.dataset.route === name));
  $('#pageTitle').textContent = r.title;
  document.title = r.title + ' · Banking App';
  closeMenu();
  if (r.load) r.load().catch(e => toast(e.message, true));
  if (name === 'login') loadDemoList();
}

function closeMenu() { $('#sidebar').classList.remove('open'); $('#scrim').classList.remove('open'); }
$('#menuBtn').addEventListener('click', () => { $('#sidebar').classList.add('open'); $('#scrim').classList.add('open'); });
$('#scrim').addEventListener('click', closeMenu);

$('#logoutBtn').addEventListener('click', async () => {
  try { await api('/auth/logout', { method: 'POST' }); } catch (_) {}
  setSession(null, null);
  resetChat('assistant');
  toast('You have been logged out.');
  location.hash = '#/login';
});

// theme
(function theme() {
  const saved = (() => { try { return localStorage.getItem('theme'); } catch (_) { return null; } })();
  if (saved) document.documentElement.dataset.theme = saved;
  $('#themeToggle').addEventListener('click', () => {
    const cur = document.documentElement.dataset.theme
      || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    const next = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('theme', next); } catch (_) {}
  });
})();

// generic UI bits
$$('.pw-toggle').forEach(b => b.addEventListener('click', () => {
  const i = b.previousElementSibling; const show = i.type === 'password';
  i.type = show ? 'text' : 'password'; b.textContent = show ? 'Hide' : 'Show';
}));
$$('.cur').forEach(c => c.textContent = curSymbol);
$$('.chips').forEach(box => {
  [500, 1000, 5000, 10000].forEach(v => box.append(el('button', {
    type: 'button', onclick: () => { const i = $('#' + box.dataset.for); i.value = v; i.dispatchEvent(new Event('input')); },
  }, money.format(v).replace(/\.00$/, ''))));
});

/* ================= Login ================= */
let demoLoaded = false;
async function loadDemoList() {
  if (demoLoaded) return;
  try { await refreshDirectory(); } catch (_) { return; }
  // The 10 seeded sample customers use the e-mail pattern firstname.lastname@example.com
  const box = $('#demoList'); box.replaceChildren();
  [...state.accountsByNo.values()].slice(0, 10).forEach(a => {
    const email = a.holderName.toLowerCase().replace(/\s+/g, '.') + '@example.com';
    box.append(el('button', { type: 'button', onclick: () => {
      $('#loginEmail').value = email; $('#loginPassword').value = 'Password@123'; $('#loginForm').requestSubmit();
    } }, el('span', {}, a.holderName), el('span', {}, email)));
  });
  demoLoaded = true;
}

$('#loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target; const d = formData(f);
  if (!d.email || !d.password) return setError('loginError', 'Enter your e-mail and password.');
  setError('loginError'); busy(f, true);
  try {
    const r = await api('/auth/login', { method: 'POST', body: d });
    setSession(r.token, r.customer);
    f.reset();
    toast(`Welcome back, ${r.customer.fullName.split(' ')[0]}!`);
    location.hash = '#/dashboard';
  } catch (err) { setError('loginError', err.message); }
  finally { busy(f, false); }
});

/* ================= Register ================= */
$('#regPassword').addEventListener('input', (e) => {
  const v = e.target.value;
  let s = 0;
  if (v.length >= 8) s++; if (v.length >= 12) s++;
  if (/[A-Z]/.test(v) && /[a-z]/.test(v)) s++; if (/\d/.test(v)) s++; if (/[^A-Za-z0-9]/.test(v)) s++;
  const levels = [['0%', 'var(--danger)', 'At least 8 characters'], ['25%', 'var(--danger)', 'Weak'],
                  ['50%', '#d97706', 'Fair'], ['75%', '#65a30d', 'Good'], ['100%', 'var(--success)', 'Strong']];
  const [w, c, label] = levels[Math.min(s, 4)];
  $('#pwBar').style.width = v ? w : '0%'; $('#pwBar').style.background = c;
  $('#pwHint').textContent = v ? label : 'At least 8 characters';
});

$('#registerForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target; const d = formData(f);
  if (!d.fullName?.trim()) return setError('regError', 'Please enter your full name.');
  if (!f.email.checkValidity() || !d.email) return setError('regError', 'Please enter a valid e-mail address.');
  if ((d.password || '').length < 8) return setError('regError', 'Password must be at least 8 characters.');
  if (d.password !== d.confirm) return setError('regError', 'Passwords do not match.');
  delete d.confirm;
  if (d.openingDeposit === '') delete d.openingDeposit;
  setError('regError'); busy(f, true);
  try {
    const r = await api('/auth/register', { method: 'POST', body: d });
    // log straight in
    const login = await api('/auth/login', { method: 'POST', body: { email: d.email, password: d.password } });
    setSession(login.token, login.customer);
    f.reset(); $('#pwBar').style.width = '0%'; $('#pwHint').textContent = 'At least 8 characters';
    demoLoaded = false;
    await dialog({
      title: 'Your account is ready', icon: 'check', success: true, ok: 'Go to dashboard', cancel: null,
      amount: money.format(r.account.balance),
      rows: [['Customer ID', r.customer.customerId], ['Account number', r.account.accountNumber],
             ['Account type', r.account.accountType]],
    });
    location.hash = '#/dashboard';
  } catch (err) { setError('regError', err.message); }
  finally { busy(f, false); }
});

/* ================= Transactions list ================= */
function renderTxns(list, txns, mine /* Set of own account numbers, or null for bank view */) {
  if (!txns.length) {
    list.replaceChildren(el('li', { class: 'empty' }, el('div', {},
      el('div', { class: 'big' }, '🧾'), 'No transactions yet')));
    return;
  }
  list.replaceChildren(...txns.map(t => {
    let dir = 'neutral', title, sign = '';
    if (mine) {
      const out = mine.has(t.fromAccount), inn = mine.has(t.toAccount);
      if (out && inn) { title = 'Between your accounts'; }
      else if (out) { dir = 'out'; sign = '−'; title = 'To ' + holder(t.toAccount); }
      else { dir = 'in'; sign = '+'; title = 'From ' + holder(t.fromAccount); }
    } else {
      title = `${holder(t.fromAccount)} → ${holder(t.toAccount)}`;
    }
    const sub = [when(t.timestamp), mine ? null : `${t.fromAccount} → ${t.toAccount}`,
                 t.initiatedBy === 'TELLER' ? 'Bank transfer' : null, t.note || null].filter(Boolean).join(' · ');
    return el('li', {},
      el('span', { class: 'txn-ico ' + dir, html: ICONS[dir === 'neutral' ? 'xfer' : dir] }),
      el('div', { class: 'txn-main' }, el('div', { class: 'txn-title' }, title), el('div', { class: 'txn-sub' }, sub)),
      el('div', { class: 'txn-amt ' + dir }, sign + money.format(t.amount)));
  }));
}

/* ================= Dashboard ================= */
async function loadDashboard() {
  $('#myAccounts').replaceChildren(el('div', { class: 'skeleton' }), el('div', { class: 'skeleton' }));
  const [me, txns] = await Promise.all([api('/me'), api('/me/transactions'), refreshDirectory()]);
  setSession(state.token, me.customer);

  const hour = new Date().getHours();
  $('#greeting').textContent = `${hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'}, ${me.customer.fullName.split(' ')[0]}`;
  $('#totalBalance').textContent = money.format(me.accounts.reduce((s, a) => s + Number(a.balance), 0));

  $('#myAccounts').replaceChildren(...me.accounts.map(a => el('div', { class: 'acct-card' },
    el('span', { class: 'type' }, a.accountType),
    el('div', { class: 'bal' }, money.format(a.balance)),
    el('div', { class: 'no' }, a.accountNumber.replace(/(\d{4})(\d{3})(\d{3})/, '$1 $2 $3')))));

  const mine = new Set(me.accounts.map(a => a.accountNumber));
  let inSum = 0, outSum = 0;
  txns.forEach(t => {
    const o = mine.has(t.fromAccount), i = mine.has(t.toAccount);
    if (o && !i) outSum += Number(t.amount); if (i && !o) inSum += Number(t.amount);
  });
  $('#flowStats').replaceChildren(
    el('span', {}, 'In ', el('b', { class: 'in' }, money.format(inSum))),
    el('span', {}, 'Out ', el('b', { class: 'out' }, money.format(outSum))));
  renderTxns($('#myTxns'), txns.slice(0, 15), mine);
}

/* ================= Beneficiaries ================= */
let lookupTimer;
$('#benAcc').addEventListener('input', (e) => {
  e.target.value = e.target.value.replace(/\D/g, '').slice(0, 10);
  const v = e.target.value, box = $('#benLookup');
  clearTimeout(lookupTimer);
  if (v.length < 10) { box.textContent = v ? `${10 - v.length} more digit(s)` : ''; box.className = 'lookup'; return; }
  lookupTimer = setTimeout(async () => {
    if (!state.accountsByNo.size) await refreshDirectory().catch(() => {});
    const a = state.accountsByNo.get(v);
    const own = state.accountsByNo.size && a && state._myAccounts?.has(v);
    if (!a) { box.textContent = '✕ No account with this number in our bank'; box.className = 'lookup err'; }
    else if (own) { box.textContent = '✕ That is your own account'; box.className = 'lookup err'; }
    else { box.textContent = `✓ ${a.holderName} · ${a.accountType}`; box.className = 'lookup ok'; }
  }, 150);
});

async function loadBeneficiaries() {
  const [bens, me] = await Promise.all([api('/me/beneficiaries'), api('/me'), refreshDirectory()]);
  state._myAccounts = new Set(me.accounts.map(a => a.accountNumber));
  $('#benCount').textContent = bens.length;
  const list = $('#benList');
  if (!bens.length) {
    list.replaceChildren(el('div', { class: 'card empty' }, el('div', { class: 'big' }, '👥'),
      'No beneficiaries yet. Add someone with the form to start sending money.'));
    return;
  }
  list.replaceChildren(...bens.map(b => el('div', { class: 'ben-card' },
    el('div', { class: 'ben-top' }, avatar(b.holderName),
      el('div', {}, el('div', { class: 'ben-name' }, b.nickname),
        el('div', { class: 'muted small' }, `${b.holderName} · ${b.accountNumber}`))),
    el('div', { class: 'ben-actions' },
      el('a', { class: 'btn small primary', href: '#/send',
                onclick: (e) => { e.preventDefault(); state.preselect = b.beneficiaryId; location.hash = '#/send'; } }, 'Send money'),
      el('button', { class: 'btn small danger', type: 'button', onclick: () => removeBeneficiary(b) }, 'Remove')))));
}

async function removeBeneficiary(b) {
  const yes = await dialog({ title: 'Remove beneficiary?', icon: 'trash', ok: 'Remove',
    rows: [['Nickname', b.nickname], ['Holder', b.holderName], ['Account', b.accountNumber]] });
  if (!yes) return;
  try { await api('/me/beneficiaries/' + encodeURIComponent(b.beneficiaryId), { method: 'DELETE' });
        toast(`${b.nickname} removed`); loadBeneficiaries(); }
  catch (err) { toast(err.message, true); }
}

$('#beneficiaryForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target; const d = formData(f);
  if (!/^\d{10}$/.test(d.accountNumber)) return setError('benError', 'Enter a 10-digit account number.');
  setError('benError'); busy(f, true);
  try {
    const b = await api('/me/beneficiaries', { method: 'POST', body: d });
    f.reset(); $('#benLookup').textContent = '';
    toast(`${b.nickname} added as a beneficiary`);
    loadBeneficiaries();
  } catch (err) { setError('benError', err.message); }
  finally { busy(f, false); }
});

/* ================= Send money ================= */
let sendAccounts = [], sendBens = [];

async function loadSend() {
  const [me, bens] = await Promise.all([api('/me'), api('/me/beneficiaries'), refreshDirectory()]);
  sendAccounts = me.accounts; sendBens = bens;
  const sel = $('#sendFrom'); const prev = sel.value;
  sel.replaceChildren(...me.accounts.map(a => el('option', { value: a.accountNumber },
    `${a.accountType} · ${a.accountNumber}`)));
  if (prev && me.accounts.some(a => a.accountNumber === prev)) sel.value = prev;
  updateAvail();

  const picker = $('#benPicker');
  if (!bens.length) {
    picker.replaceChildren(el('div', { class: 'muted small' }, 'You have no beneficiaries yet. ',
      el('a', { href: '#/beneficiaries' }, 'Add one first →')));
    $('#sendTo').value = '';
    return;
  }
  const chosen = state.preselect || $('#sendTo').value;
  state.preselect = null;
  picker.replaceChildren(...bens.map(b => el('button', {
    type: 'button', class: 'ben-pick', 'data-id': b.beneficiaryId,
    onclick: () => pickBen(b.beneficiaryId),
  }, avatar(b.holderName), el('div', {}, el('b', {}, b.nickname), el('small', {}, b.accountNumber)))));
  pickBen(bens.some(b => b.beneficiaryId === chosen) ? chosen : '');
}

function pickBen(id) {
  $('#sendTo').value = id;
  $$('.ben-pick').forEach(p => p.classList.toggle('selected', p.dataset.id === id));
}

function updateAvail() {
  const a = sendAccounts.find(x => x.accountNumber === $('#sendFrom').value);
  $('#sendAvail').textContent = a ? `Available balance: ${money.format(a.balance)}` : '';
}
$('#sendFrom').addEventListener('change', updateAvail);

$('#sendForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target; const d = formData(f);
  const from = sendAccounts.find(a => a.accountNumber === d.fromAccount);
  const ben = sendBens.find(b => b.beneficiaryId === d.beneficiaryId);
  const amt = Number(d.amount);
  if (!from) return setError('sendError', 'Choose the account to send from.');
  if (!ben) return setError('sendError', 'Choose who to send money to.');
  if (!(amt > 0)) return setError('sendError', 'Enter an amount greater than zero.');
  if (amt > Number(from.balance)) return setError('sendError', `Not enough money: available ${money.format(from.balance)}.`);
  setError('sendError');

  const ok = await dialog({ title: 'Confirm transfer', amount: money.format(amt), ok: 'Send now',
    rows: [['From', `${from.accountType} · ${from.accountNumber}`], ['To', `${ben.nickname} (${ben.holderName})`],
           ['Account', ben.accountNumber], ...(d.note ? [['Note', d.note]] : [])] });
  if (!ok) return;
  busy(f, true);
  try {
    const t = await api('/me/transfers', { method: 'POST', body: d });
    f.amount.value = ''; f.note.value = '';
    await receipt(t);
    loadSend();
  } catch (err) { setError('sendError', err.message); }
  finally { busy(f, false); }
});

/* ================= Bank transfer ================= */
async function loadBankTransfer() {
  const [accounts, txns] = await Promise.all([refreshDirectory(), api('/transactions')]);
  for (const id of ['bankFrom', 'bankTo']) {
    const sel = $('#' + id); const prev = sel.value;
    sel.replaceChildren(...accounts.map(a => el('option', { value: a.accountNumber }, `${a.accountNumber} — ${a.holderName}`)));
    if (prev && state.accountsByNo.has(prev)) sel.value = prev;
  }
  if ($('#bankFrom').value === $('#bankTo').value && accounts.length > 1) $('#bankTo').selectedIndex = 1;
  previewAccounts();
  renderTxns($('#bankTxns'), txns.slice(0, 20), null);
}

function previewAccounts() {
  for (const [selId, boxId] of [['bankFrom', 'bankFromPrev'], ['bankTo', 'bankToPrev']]) {
    const a = state.accountsByNo.get($('#' + selId).value);
    $('#' + boxId).replaceChildren(...(a ? [avatar(a.holderName),
      el('div', {}, el('b', {}, a.holderName), el('small', {}, `${a.accountType} · Balance ${money.format(a.balance)}`))] : []));
  }
}
$('#bankFrom').addEventListener('change', previewAccounts);
$('#bankTo').addEventListener('change', previewAccounts);
$('#swapBtn').addEventListener('click', () => {
  const a = $('#bankFrom').value; $('#bankFrom').value = $('#bankTo').value; $('#bankTo').value = a; previewAccounts();
});

$('#bankForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target; const d = formData(f);
  const from = state.accountsByNo.get(d.fromAccount), to = state.accountsByNo.get(d.toAccount);
  const amt = Number(d.amount);
  if (!from || !to) return setError('bankError', 'Choose both a source and a target account.');
  if (d.fromAccount === d.toAccount) return setError('bankError', 'Source and target accounts must be different.');
  if (!(amt > 0)) return setError('bankError', 'Enter an amount greater than zero.');
  if (amt > Number(from.balance)) return setError('bankError', `Not enough money in the source account (${money.format(from.balance)}).`);
  setError('bankError');

  const ok = await dialog({ title: 'Confirm bank transfer', amount: money.format(amt), icon: 'xfer', ok: 'Transfer',
    rows: [['From', `${from.holderName} · ${from.accountNumber}`], ['To', `${to.holderName} · ${to.accountNumber}`],
           ...(d.note ? [['Note', d.note]] : [])] });
  if (!ok) return;
  busy(f, true);
  try {
    const t = await api('/transfers', { method: 'POST', body: d });
    f.amount.value = ''; f.note.value = '';
    await receipt(t);
    loadBankTransfer();
  } catch (err) { setError('bankError', err.message); }
  finally { busy(f, false); }
});

/* ================= All accounts ================= */
async function loadAccounts() {
  const accounts = await refreshDirectory();
  const total = accounts.reduce((s, a) => s + Number(a.balance), 0);
  const stat = (label, value) => el('div', { class: 'stat' }, el('div', { class: 'label' }, label), el('div', { class: 'value' }, value));
  $('#acctStats').replaceChildren(
    stat('Accounts', accounts.length),
    stat('Total deposits', money.format(total)),
    stat('Average balance', money.format(accounts.length ? total / accounts.length : 0)));
  renderAccountRows();
}

function renderAccountRows() {
  const q = $('#acctSearch').value.trim().toLowerCase();
  const rows = [...state.accountsByNo.values()].filter(a =>
    !q || a.holderName.toLowerCase().includes(q) || a.accountNumber.includes(q));
  const tbody = $('#acctTable tbody');
  if (!rows.length) {
    tbody.replaceChildren(el('tr', {}, el('td', { colspan: 4, class: 'empty' }, 'No matching accounts')));
    return;
  }
  tbody.replaceChildren(...rows.map(a => el('tr', {},
    el('td', { class: 'mono' }, a.accountNumber),
    el('td', {}, el('div', { class: 'holder' }, avatar(a.holderName), a.holderName)),
    el('td', {}, el('span', { class: 'pill' }, a.accountType)),
    el('td', { class: 'num' }, money.format(a.balance)))));
}
$('#acctSearch').addEventListener('input', renderAccountRows);

/* ================= AI agents ================= */
Object.assign(ROUTES, {
  assistant:       { title: 'AI Assistant',     auth: true, load: () => initChat('assistant') },
  help:            { title: 'Help',             load: () => initChat('support') },
  'ops-assistant': { title: 'Ops Assistant',    load: () => initChat('operations') },
  fraud:           { title: 'Fraud Alerts',     load: loadFraud },
});

// --- tiny, safe markdown: escape first, then add a few formats ---
function esc(s) { return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function inline(s) {
  return esc(s).replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>');
}
function markdown(text) {
  const lines = text.replace(/\r/g, '').split('\n');
  let html = '', i = 0;
  while (i < lines.length) {
    const l = lines[i];
    if (/^\s*\|.*\|\s*$/.test(l)) {                       // table
      const rows = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) rows.push(lines[i++]);
      const cells = r => r.trim().replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      const body = rows.filter(r => !/^\s*\|[\s:|-]+\|\s*$/.test(r));
      html += '<table>' + body.map((r, n) => '<tr>' + cells(r).map(c =>
        n === 0 ? `<th>${inline(c)}</th>` : `<td>${inline(c)}</td>`).join('') + '</tr>').join('') + '</table>';
    } else if (/^\s*([-*]|\d+\.)\s+/.test(l)) {            // list
      const ordered = /^\s*\d+\./.test(l); const items = [];
      while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*([-*]|\d+\.)\s+/, ''));
      html += (ordered ? '<ol>' : '<ul>') + items.map(x => `<li>${inline(x)}</li>`).join('') + (ordered ? '</ol>' : '</ul>');
    } else if (/^#{1,4}\s/.test(l)) {
      html += `<p><strong>${inline(l.replace(/^#+\s/, ''))}</strong></p>`; i++;
    } else if (!l.trim()) { i++; }
    else {
      const para = [];
      while (i < lines.length && lines[i].trim() && !/^\s*(\||[-*]\s|\d+\.\s|#{1,4}\s)/.test(lines[i])) para.push(lines[i++]);
      html += `<p>${para.map(inline).join('<br>')}</p>`;
    }
  }
  return html;
}

const chats = {};   // agent -> chat state

function resetChat(agent) { chats[agent] = undefined; }

function initChat(agent) {
  const root = $(`.chat[data-agent="${agent}"]`);
  const owner = state.customer?.customerId || null;
  if (agent === 'assistant' && chats[agent] && chats[agent].owner !== owner) resetChat(agent);
  if (chats[agent]) { chats[agent].input.focus(); return Promise.resolve(); }

  const log = el('div', { class: 'chat-log' });
  const sugg = el('div', { class: 'suggestions' });
  const input = el('textarea', { rows: 1, placeholder: 'Type a message…', 'aria-label': 'Message' });
  const send = el('button', { class: 'btn primary', type: 'submit' }, 'Send');
  const form = el('form', { class: 'chat-input' }, input, send);
  const newChat = el('button', { class: 'btn small', type: 'button' }, 'New chat');
  root.replaceChildren(el('div', { class: 'chat-head' }, newChat), log, sugg, form);

  const c = chats[agent] = { agent, root, log, sugg, input, send, conversationId: null, busy: false, owner };
  addBot(c, root.dataset.intro);
  (root.dataset.suggestions || '').split('|').filter(Boolean).forEach(s =>
    sugg.append(el('button', { type: 'button', onclick: () => sendChat(c, s) }, s)));

  form.addEventListener('submit', e => { e.preventDefault(); sendChat(c, input.value); });
  input.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); } });
  input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight, 140) + 'px'; });
  newChat.addEventListener('click', () => { resetChat(agent); initChat(agent); });
  input.focus();
  return Promise.resolve();
}

function addRow(c, who, content) {
  const row = el('div', { class: 'msg-row ' + who },
    who === 'bot' ? el('span', { class: 'bot-ico' }, '✦') : null, content);
  c.log.append(row);
  c.log.scrollTop = c.log.scrollHeight;
  return row;
}
function addBot(c, text, extraClass = '') {
  return addRow(c, 'bot', el('div', {}, el('div', { class: 'bubble ' + extraClass, html: markdown(text) })));
}

async function sendChat(c, text) {
  text = text.trim();
  if (!text || c.busy) return;
  c.busy = true; c.send.disabled = true; c.input.value = ''; c.input.style.height = 'auto';
  c.sugg.replaceChildren();
  addRow(c, 'user', el('div', { class: 'bubble' }, text));
  const typing = addRow(c, 'bot', el('div', { class: 'bubble typing' }, el('span'), el('span'), el('span')));
  try {
    let r;
    try {
      r = await api(`/agents/${c.agent}/chat`, { method: 'POST', body: { conversationId: c.conversationId, message: text } });
    } catch (err) {
      // The server restarted or another instance answered: start a fresh conversation and retry once.
      if (!c.conversationId || !/conversation expired/i.test(err.message)) throw err;
      c.conversationId = null;
      r = await api(`/agents/${c.agent}/chat`, { method: 'POST', body: { conversationId: null, message: text } });
    }
    c.conversationId = r.conversationId;
    typing.replaceWith();
    const wrap = addBot(c, r.reply).lastChild;
    if (r.toolCalls?.length) {
      wrap.append(el('div', { class: 'tools-used' }, 'Used: ' + [...new Set(r.toolCalls.map(t => t.tool.replace(/_/g, ' ')))].join(', ')));
    }
    (r.pendingActions || []).filter(a => !c.root.querySelector(`[data-action="${a.id}"]`))
      .forEach(a => wrap.append(actionCard(c, a)));
    c.log.scrollTop = c.log.scrollHeight;
  } catch (err) {
    typing.replaceWith();
    addBot(c, err.message, 'err');
  } finally {
    c.busy = false; c.send.disabled = false; c.input.focus();
  }
}

function actionCard(c, a) {
  const status = el('div', { class: 'muted small' });
  const ok = el('button', { class: 'btn primary small', type: 'button' }, 'Confirm');
  const no = el('button', { class: 'btn small', type: 'button' }, 'Cancel');
  const buttons = el('div', { class: 'row' }, ok, no);
  const card = el('div', { class: 'action-card', 'data-action': a.id },
    el('strong', {}, a.summary),
    el('dl', { class: 'kv' }, ...Object.entries(a.details).flatMap(([k, v]) =>
      [el('dt', {}, k), el('dd', {}, k === 'Amount' ? money.format(Number(v)) : v)])),
    buttons, status);
  const finish = (msg) => { card.classList.add('done'); buttons.hidden = true; status.textContent = msg; };
  ok.onclick = async () => {
    ok.disabled = no.disabled = true;
    try {
      const r = await api(`/agents/${c.agent}/actions/${encodeURIComponent(a.id)}/confirm`, { method: 'POST' });
      finish(`✓ Done · ref ${r.result.transactionId}`);
      await refreshDirectory().catch(() => {});
      receipt(r.result);
    } catch (err) { finish('✕ ' + err.message); }
  };
  no.onclick = async () => {
    ok.disabled = no.disabled = true;
    try { await api(`/agents/${c.agent}/actions/${encodeURIComponent(a.id)}/cancel`, { method: 'POST' }); finish('Cancelled. No money moved.'); }
    catch (err) { finish('✕ ' + err.message); }
  };
  return card;
}

// --- fraud alerts ---
async function loadFraud() {
  const status = $('input[name="fraudStatus"]:checked').value;
  const [alerts] = await Promise.all([api('/agents/fraud/alerts' + (status ? '?status=' + status : '')), refreshDirectory()]);
  const list = $('#fraudList');
  if (!alerts.length) {
    list.replaceChildren(el('div', { class: 'card empty' }, el('div', { class: 'big' }, '🛡️'),
      status === 'OPEN' ? 'No open alerts. Large or unusual transfers show up here after review.' : 'No alerts.'));
  } else {
    list.replaceChildren(...alerts.map(a => {
      const resolved = a.status === 'RESOLVED';
      const card = el('div', { class: `alert-card ${a.riskLevel}${resolved ? ' resolved' : ''}` },
        el('div', { class: 'alert-top' },
          el('div', {}, el('span', { class: 'risk ' + a.riskLevel }, `${a.riskLevel} · ${a.riskScore}`), ' ',
            el('strong', {}, `${money.format(Number(a.amount))}  ${holder(a.fromAccount)} → ${holder(a.toAccount)}`)),
          el('span', { class: 'muted small' }, `${a.alertId} · ${when(a.createdAt)}`)),
        el('ul', {}, ...a.reasons.split(' | ').filter(Boolean).map(r => el('li', {}, r))),
        el('div', { class: 'muted small' }, `Recommended: ${(a.recommendedAction || '').replace(/_/g, ' ').toLowerCase()} · Flagged by: ${a.triggers} · Txn ${a.transactionId}`));
      if (resolved) card.append(el('div', { class: 'small' }, '✓ Resolved' + (a.resolvedNote ? ': ' + a.resolvedNote : '')));
      else card.append(el('div', {}, el('button', { class: 'btn small', type: 'button', onclick: async () => {
        const note = prompt('Resolution note (what did you check / find?)');
        if (note === null) return;
        try { await api(`/agents/fraud/alerts/${encodeURIComponent(a.alertId)}/resolve`, { method: 'POST', body: { note } }); toast('Alert resolved'); loadFraud(); }
        catch (err) { toast(err.message, true); }
      } }, 'Mark resolved')));
      return card;
    }));
  }
  updateFraudBadge();
}
async function updateFraudBadge() {
  try {
    const open = await api('/agents/fraud/alerts?status=OPEN');
    const b = $('#fraudBadge'); b.textContent = open.length; b.hidden = !open.length;
  } catch (_) {}
}
$$('input[name="fraudStatus"]').forEach(r => r.addEventListener('change', loadFraud));
updateFraudBadge();
setInterval(updateFraudBadge, 30000);

/* ================= Boot ================= */
(async function boot() {
  setSession(state.token, null);
  if (state.token) {
    try { const me = await api('/me'); setSession(state.token, me.customer); }
    catch (_) { setSession(null, null); }
  }
  window.addEventListener('hashchange', route);
  route();
})();
