const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const asset = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'portal-bridge.js');
// A missing bridge has the current behavior: it cannot fill or submit the page.
const source = fs.existsSync(asset) ? fs.readFileSync(asset, 'utf8') :
  '(function () { return JSON.stringify({ state: "waiting", reason: "" }); })';

class Element {
  constructor(tag, attributes = {}, text = '') {
    this.tagName = tag.toUpperCase();
    this.attributes = { ...attributes };
    this.name = attributes.name || '';
    this.id = attributes.id || '';
    this.type = attributes.type || '';
    this.value = attributes.value || '';
    this.textContent = text;
    this.innerText = text;
    this.disabled = false;
    this.hidden = false;
    this.style = {};
    this.events = [];
    this.clicks = 0;
    this.options = [];
    this.checked = false;
    this.onclick = null;
    this.form = null;
  }
  getAttribute(name) { return this.attributes[name] ?? null; }
  hasAttribute(name) { return name in this.attributes; }
  getClientRects() { return this.hidden || this.style.display === 'none' ? [] : [{}]; }
  dispatchEvent(event) {
    this.events.push(event.type);
    const listener = this['on' + event.type];
    if (typeof listener === 'function') listener.call(this, event);
    return true;
  }
  click() {
    this.clicks++;
    // 真实 DOM 里点单选按钮会把它设为选中，并且**取消同组其它项的选中**。
    // 夹具要照做：否则「两个运营商同时亮着」这种错会被漏掉。
    if (this.type === 'radio') {
      if (Array.isArray(this.radioGroup)) for (const sibling of this.radioGroup) sibling.checked = false;
      this.checked = true;
    }
    this.dispatchEvent({ type: 'click' });
    if (this.type === 'submit' && this.form) this.form.dispatchEvent({ type: 'submit' });
  }
  closest(selector) { return selector === 'form' ? this.form : null; }
}

function matches(element, selector) {
  selector = selector.trim();
  if (selector === '*') return true;
  const tag = selector.match(/^[a-z][a-z0-9-]*/i)?.[0];
  if (tag && element.tagName !== tag.toUpperCase()) return false;
  const id = selector.match(/#([\w-]+)/)?.[1];
  if (id && element.id !== id) return false;
  const cssClass = selector.match(/\.([\w-]+)/)?.[1];
  if (cssClass && !String(element.getAttribute('class') || '').split(/\s+/).includes(cssClass)) return false;
  for (const match of selector.matchAll(/\[([\w-]+)(?:\s*=\s*["']?([^\]"']*)["']?)?\]/g)) {
    const actual = element.getAttribute(match[1]);
    if (match[2] === undefined ? actual === null : actual !== match[2]) return false;
  }
  return true;
}

function fixture({ ready = true, controls = true, carrier = 'select' } = {}) {
  const elements = [];
  const document = {
    readyState: 'complete',
    cookie: 'PHPSESSID=fixture-session',
    body: { textContent: '', innerText: '' },
    querySelectorAll(selector) {
      const alternatives = selector.split(',');
      return elements.filter(element => alternatives.some(part => matches(element, part)));
    },
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; },
    getElementById(id) { return elements.find(element => element.id === id) || null; },
    getElementsByName(name) { return elements.filter(element => element.name === name); },
    getElementsByTagName(tag) { return elements.filter(element => tag === '*' || element.tagName === tag.toUpperCase()); },
  };
  const window = {
    location: { protocol: 'https:', hostname: 'yc.gxnu.edu.cn', port: '', href: 'https://yc.gxnu.edu.cn/' },
    Event: class Event { constructor(type, options) { this.type = type; this.bubbles = !!options?.bubbles; } },
    getComputedStyle: element => ({ display: element.style.display || 'block', visibility: 'visible' }),
    document,
    page: { index: ready ? 'fixture-page' : '', name: ready ? 'fixture-program' : '', kind: 'mobile', login_method: 1 },
    term: { ip: ready ? '10.20.30.40' : '000.000.000.000', ipv6: '', suffix: '', online: {} },
    login: { init() {}, login() {}, portal_login() {} },
    ee() { return false; },
    util: { _jsonp() {} },
    store: { get() {}, set() {}, remove() {}, clear() {} },
    cookie: { get() {}, set() {} },
  };
  window.window = window;
  window.self = window;
  window.top = window;
  const context = vm.createContext(window);
  let account;
  let password;
  let provider;
  let login;
  let logout;
  let form;
  function addControls() {
    form = new Element('form', { name: 'f1', method: 'post', action: '', onsubmit: 'return ee(1)' });
    form.onsubmit = function () { window.ee(1); return false; };
    account = new Element('input', { name: 'DDDDD', type: 'text' });
    password = new Element('input', { name: 'upass', type: 'password' });
    login = new Element('input', { name: '0MKKey', type: 'submit', value: '登录' });
    login.onclick = function () {};
    logout = new Element('button', { id: 'logout', type: 'button' }, '注销');
    logout.onclick = function () {};
    const providerControls = [];
    if (carrier === 'radiobutton') {
      // 本门户实测形态：carrier.yys.mode = radiobutton，页面上**没有** select。
      // 学校自己声明的四个后缀（注意没有广电网络 @gd）。
      window.carrier = JSON.stringify({
        yys: {
          title: '服务类型', mode: 'radiobutton', type: '0', defaultID: '1',
          data: [
            { id: '1', name: '校园用户', suffix: '' },
            { id: '2', name: '中国电信', suffix: '@ctc' },
            { id: '3', name: '中国联通', suffix: '@cuc' },
            { id: '4', name: '中国移动', suffix: '@cmc' },
          ],
        },
      });
      for (const item of [{ suffix: '' }, { suffix: '@ctc' }, { suffix: '@cuc' }, { suffix: '@cmc' }]) {
        const radio = new Element('input', { name: 'ISP_select', type: 'radio', value: item.suffix });
        radio.checked = item.suffix === '';
        providerControls.push(radio);
      }
      // 同 name 的是一组：选中一个要取消其它。
      for (const radio of providerControls) radio.radioGroup = providerControls;
    } else {
      provider = new Element('select', { name: 'ISP_select' });
      provider.options = [
        { value: '', textContent: '校园网' }, { value: '@ctc', textContent: '中国电信' },
        { value: '@cuc', textContent: '中国联通' }, { value: '@cmc', textContent: '中国移动' },
        { value: '@gd', textContent: '广电网络' },
      ];
      provider.value = '@ctc';
      providerControls.push(provider);
    }
    for (const control of [account, password, ...providerControls, login]) {
      control.form = form;
      control.parentElement = form;
    }
    elements.push(form, account, password, ...providerControls, login, logout);
  }
  if (controls) addControls();
  function run(accountValue = 'fixture-student', passwordValue = 'fixture-password', suffix = '', title = '校园网') {
    const parameters = [accountValue, passwordValue, suffix, title].map(JSON.stringify).join(',');
    const result = vm.runInContext(`(${source})(${parameters})`, context, { timeout: 1000 });
    return JSON.parse(result);
  }
  return {
    window, document, elements, run, addControls,
    get account() { return account; }, get password() { return password; },
    get provider() { return provider; }, get login() { return login; }, get logout() { return logout; },
    get form() { return form; },
    get radios() { return elements.filter(element => element.type === 'radio'); },
  };
}

test('the packaged official-page bridge exists', () => assert.ok(fs.existsSync(asset)));

test('waits for asynchronous school configuration and template before filling', () => {
  const f = fixture({ ready: false, controls: false });
  assert.equal(f.run().state, 'waiting');
  f.addControls();
  assert.equal(f.run().state, 'waiting');
  assert.equal(f.account.value, '');
  f.window.page.index = 'fixture-page';
  f.window.page.name = 'fixture-program';
  f.window.term.ip = '10.20.30.40';
  assert.equal(f.run().state, 'submitted');
  assert.equal(f.login.clicks, 1);
});

test('fills the actual fields and preserves malicious characters as password text', () => {
  const f = fixture();
  const password = ' \\"\'); window.injected = true; //\n中文\\\u2028\u2029 ';
  assert.equal(f.run(' fixture-student@ctc ', password).state, 'submitted');
  assert.equal(f.account.value, 'fixture-student');
  assert.equal(f.password.value, password);
  assert.equal(f.window.injected, undefined);
  assert.deepEqual(f.account.events, ['input', 'change']);
  assert.deepEqual(f.password.events, ['input', 'change']);
});

for (const [suffix, title] of [['', '校园网'], ['@ctc', '中国电信'], ['@cuc', '中国联通'], ['@cmc', '中国移动'], ['@gd', '广电网络']]) {
  test(`selects ${title} through its form control before submitting`, () => {
    const f = fixture();
    let selectionAtClick;
    f.login.onclick = function () { selectionAtClick = f.provider.value; };
    assert.equal(f.run('fixture-student@cuc@ctc', 'fixture-password', suffix, title).state, 'submitted');
    assert.equal(selectionAtClick, suffix);
    assert.equal(f.account.value, 'fixture-student');
    assert.ok(f.provider.events.includes('change'));
    assert.equal(f.window.term.suffix, '');
  });
}

test('repeated polling submits only once and never clicks logout', () => {
  const f = fixture();
  for (let i = 0; i < 6; i++) assert.equal(f.run().state, 'submitted');
  assert.equal(f.login.clicks, 1);
  assert.equal(f.logout.clicks, 0);
  assert.equal(f.account.events.length, 2);
});

test('the host submission flag observes a fresh document without filling or clicking again', () => {
  const f = fixture();
  f.window.__campusOfficialSubmitted = true;
  assert.deepEqual(f.run(), { state: 'submitted', reason: '' });
  assert.equal(f.account.value, '');
  assert.equal(f.password.value, '');
  assert.equal(f.login.clicks, 0);
  f.window.error = { portalErr: { ret_code: 1, msg: 'fixture-password' } };
  assert.deepEqual(f.run(), { state: 'rejected', reason: 'ACCOUNT' });
  assert.equal(f.login.clicks, 0);
});

test('an already-online official page is only observed', () => {
  const f = fixture();
  f.window.page.kind = 'mobile_31';
  f.window.term.online = { result: 1 };
  assert.equal(f.run().state, 'waiting');
  assert.equal(f.login.clicks, 0);
  assert.equal(f.logout.clicks, 0);
  assert.equal(f.password.value, '');
});

test('ignores duplicate controls in the schools hidden legacy form', () => {
  const f = fixture();
  const legacyAccount = new Element('input', { name: 'DDDDD', type: 'text' });
  const legacyPassword = new Element('input', { name: 'upass', type: 'password' });
  const legacySubmit = new Element('input', { name: '0MKKey', type: 'submit' });
  const legacyForm = new Element('form', { name: 'f0' });
  legacyForm.style.display = 'none';
  for (const control of [legacyAccount, legacyPassword, legacySubmit]) control.parentElement = legacyForm;
  f.elements.unshift(legacyAccount, legacyPassword, legacySubmit);
  assert.equal(f.run().state, 'submitted');
  assert.equal(f.account.value, 'fixture-student');
  assert.equal(legacyAccount.value, '');
  assert.equal(legacyPassword.value, '');
  assert.equal(legacySubmit.clicks, 0);
});

test('school cookie calls keep passwords in memory and never persist them to localStorage', () => {
  const f = fixture();
  const persistentWrites = [];
  f.window.store.set = (key, value) => persistentWrites.push([key, value]);
  f.window.cookie.set = (key, value) => f.window.store.set(key, { val: value, exp: 1000 });
  f.login.onclick = function () { f.window.cookie.set('upass', f.password.value); };
  assert.equal(f.run().state, 'submitted');
  assert.deepEqual(persistentWrites, []);
  assert.equal(f.window.store.get('upass').val, 'fixture-password');
  f.window.store.remove('upass');
  assert.equal(f.window.store.get('upass'), undefined);
});

test('reads structured school errors without returning the schools message', () => {
  const f = fixture();
  assert.equal(f.run().state, 'submitted');
  f.window.error = { portalErr: { ret_code: 1, msg: 'fixture-password private school text' } };
  assert.deepEqual(f.run(), { state: 'rejected', reason: 'ACCOUNT' });
  assert.equal(f.login.clicks, 1);
});

test('waits for the schools original form handler and cookie helper', () => {
  const f = fixture();
  f.window.ee = undefined;
  // 字段已经在页面上了，所以先填好（filled）；不能提交，因为学校的表单处理器还没就绪。
  // 之前这里断言 'waiting' 且密码为空 —— 那正是用户报的「不给我填好」，现在填与提交分开判定。
  assert.equal(f.run().state, 'filled');
  assert.equal(f.password.value, 'fixture-password');
  assert.equal(f.login.clicks, 0);
  f.window.ee = function () { return false; };
  // 连 cookie 助手都没有：这不是「还没填」，而是学校这套登录流程根本跑不起来 ——
  // 那一项在填充**之前**的前置检查里，所以这里仍是 waiting，且不该去动表单。
  f.window.cookie = undefined;
  assert.equal(f.run().state, 'waiting');
  assert.equal(f.login.clicks, 0);
  f.window.cookie = { get() {}, set() {} };
  assert.equal(f.run().state, 'submitted');
});

test('clicks the submit control and lets the original form ee handler authenticate', () => {
  const f = fixture();
  const originalSubmissions = [];
  f.login.onclick = null;
  f.form.submit = () => { throw new Error('Do not bypass the original form event'); };
  f.window.ee = id => { originalSubmissions.push(id); return false; };
  assert.equal(f.run().state, 'submitted');
  assert.equal(f.login.clicks, 1);
  assert.deepEqual(originalSubmissions, [1]);
});

test('does not depend on the offscreen WebViews element bounds', () => {
  const f = fixture();
  for (const control of f.elements) control.getClientRects = () => [];
  assert.equal(f.run().state, 'submitted');
  assert.equal(f.login.clicks, 1);
});

test('disables the schools remember-password and automatic-login options', () => {
  const f = fixture();
  const savePassword = new Element('input', { name: 'savePassword', type: 'checkbox' });
  const autoLogin = new Element('input', { name: 'checkAutoLogin', type: 'checkbox' });
  savePassword.checked = true;
  autoLogin.checked = true;
  f.elements.push(savePassword, autoLogin);
  assert.equal(f.run().state, 'submitted');
  assert.equal(savePassword.checked, false);
  assert.equal(autoLogin.checked, false);
  assert.deepEqual(savePassword.events, ['change']);
  assert.deepEqual(autoLogin.events, ['change']);
});

test('does not accept the schools agreement checkbox for the student', () => {
  const f = fixture();
  const agreement = new Element('input', { name: 'C1', type: 'checkbox' });
  agreement.checked = false;
  f.elements.push(agreement);
  // 关键性质没变：绝不替用户勾选协议、绝不点击。但「密码留空」不再是要求 ——
  // 把账号密码填进表单与「同意协议」是两件事，填好字段反而让用户少打两次字。
  assert.deepEqual(f.run(), { state: 'manual', reason: 'PAGE' });
  assert.equal(agreement.checked, false);
  assert.equal(f.login.clicks, 0);
});

test('turns a school rejection into a fixed safe category without echoing text', () => {
  const f = fixture();
  assert.equal(f.run().state, 'submitted');
  f.elements.push(new Element('div', { id: 'message' }, '账号 fixture-student 或密码 fixture-password 错误 https://example.test/private'));
  assert.deepEqual(f.run(), { state: 'rejected', reason: 'ACCOUNT' });
  assert.equal(f.login.clicks, 1);
});

test('a visible captcha requires manual interaction before filling credentials', () => {
  const f = fixture();
  f.elements.push(new Element('input', { name: 'captcha', type: 'text' }));
  assert.deepEqual(f.run(), { state: 'manual', reason: 'CAPTCHA' });
  assert.equal(f.password.value, '');
  assert.equal(f.login.clicks, 0);
});

test('unknown provider is not silently submitted with the default operator', () => {
  const f = fixture();
  f.provider.options = [{ value: '@ctc', textContent: '中国电信' }];
  // 关键性质没变：绝不拿默认运营商替他提交。理由码从笼统的 PAGE 改成 PROVIDER，
  // 因为这是用户能自己修的一件事（回去改选运营商），值得单独说。
  assert.deepEqual(f.run(), { state: 'manual', reason: 'PROVIDER' });
  assert.equal(f.login.clicks, 0);
});

for (const location of [
  { protocol: 'http:', hostname: 'yc.gxnu.edu.cn', port: '' },
  { protocol: 'https:', hostname: 'example.test', port: '' },
  { protocol: 'https:', hostname: 'yc.gxnu.edu.cn', port: '801' },
]) {
  test(`does not fill credentials outside the school HTTPS origin (${location.protocol}/${location.hostname}/${location.port})`, () => {
    const f = fixture();
    Object.assign(f.window.location, location);
    assert.deepEqual(f.run(), { state: 'manual', reason: 'PAGE' });
    assert.equal(f.password.value, '');
    assert.equal(f.login.clicks, 0);
  });
}

test('only operates in the top frame', () => {
  const f = fixture();
  f.window.top = {};
  assert.deepEqual(f.run(), { state: 'manual', reason: 'PAGE' });
  assert.equal(f.password.value, '');
});

// 用户报的故障：「运营商那里还是没有选择」。
// 实测 yc.gxnu.edu.cn 的 window.carrier 是 mode:"radiobutton"，渲染出来的页面**一个 select
// 都没有**（浏览器里查 selects 为空数组）。桥原来只找 select[name="ISP_select"]，所以运营商
// 永远选不上。下面这几条用真实形态（单选按钮）钉住修复。
test('selects the operator from the schools radiobutton group, not only from a select', () => {
  const f = fixture({ carrier: 'radiobutton' });
  assert.equal(f.run('fixture-student', 'fixture-password', '@cmc', '中国移动').state, 'submitted');
  const chosen = f.radios.filter(r => r.value === '@cmc');
  assert.equal(chosen.length, 1);
  assert.equal(chosen[0].checked, true);
  assert.equal(chosen[0].clicks, 1);
  // 其它运营商不许被顺手勾上
  assert.deepEqual(f.radios.filter(r => r.value !== '@cmc').map(r => r.checked), [false, false, false]);
  assert.equal(f.login.clicks, 1);
});

test('the campus operator is selectable too, including its empty suffix', () => {
  const f = fixture({ carrier: 'radiobutton' });
  assert.equal(f.run('fixture-student', 'fixture-password', '', '校园网').state, 'submitted');
  assert.equal(f.radios.filter(r => r.value === '')[0].checked, true);
  assert.equal(f.login.clicks, 1);
});

test('a radio group is re-selected when the operator changes between attempts', () => {
  const f = fixture({ carrier: 'radiobutton' });
  assert.equal(f.run('fixture-student', 'fixture-password', '@ctc', '中国电信').state, 'submitted');
  assert.equal(f.radios.filter(r => r.value === '@ctc')[0].checked, true);
  // 换一个运营商：上一次的选中必须被替换，而不是两个都亮着
  const g = fixture({ carrier: 'radiobutton' });
  assert.equal(g.run('fixture-student', 'fixture-password', '@cuc', '中国联通').state, 'submitted');
  assert.equal(g.radios.filter(r => r.value === '@cuc')[0].checked, true);
});

test('an operator the school does not offer is refused by name, not submitted', () => {
  // 这所学校只有 校园用户/电信/联通/移动，**没有广电网络**，而 App 里列了它。
  // 选不上就不许提交（否则会拿错误的运营商去认证），并且要说清是运营商的问题。
  const f = fixture({ carrier: 'radiobutton' });
  assert.deepEqual(f.run('fixture-student', 'fixture-password', '@gd', '广电网络'),
    { state: 'manual', reason: 'PROVIDER' });
  assert.equal(f.login.clicks, 0);
  assert.deepEqual(f.radios.map(r => r.checked), [true, false, false, false]);
  // 但账号密码仍然先填好了，用户只需改运营商
  assert.equal(f.account.value, 'fixture-student');
  assert.equal(f.password.value, 'fixture-password');
});

test('the select form keeps working for schools that use one', () => {
  // 修复不能只照顾单选按钮这一种形态。
  const f = fixture({ carrier: 'select' });
  assert.equal(f.run('fixture-student', 'fixture-password', '@cmc', '中国移动').state, 'submitted');
  assert.equal(f.provider.value, '@cmc');
  assert.equal(f.login.clicks, 1);
});

// 用户报的故障：「我填了东西，去认证的时候不给我填好」。
// 桥原来是全有或全无的：任何一项提交前置条件不满足就返回 waiting，一个字段都不填，
// 而界面只认得 waiting/submitted/rejected/manual，于是「填好了但还不能提交」显示成
// 「正在准备学校登录页」—— 用户面对空表单，像是应用什么都没做。
// 现在填充与点击分开判定：字段出现就填，点击才需要全部前置条件；中间态是 'filled'。
test('fills the fields as soon as they exist, even while submission is still blocked', () => {
  const f = fixture();
  // 学校的表单处理器还没注册 —— 不能提交，但字段已经在页面上了。
  f.window.ee = undefined;
  assert.equal(f.run().state, 'filled');
  assert.equal(f.account.value, 'fixture-student');
  assert.equal(f.password.value, 'fixture-password');
  assert.equal(f.login.clicks, 0);
});

test('keeps polling after filling and submits once the form becomes submittable', () => {
  const f = fixture();
  f.window.ee = undefined;
  assert.equal(f.run().state, 'filled');
  f.window.ee = function () { return false; };
  // 第二次轮询就能提交了，而且只点一次。
  assert.equal(f.run().state, 'submitted');
  assert.equal(f.run().state, 'submitted');
  assert.equal(f.login.clicks, 1);
});

test('never reports filled for a page it did not fill', () => {
  const f = fixture({ controls: false });
  assert.equal(f.run().state, 'waiting');
  assert.equal(f.account, undefined);
});

test('a missing agreement checkbox still blocks the click but leaves the fields filled', () => {
  const f = fixture();
  const agreement = new Element('input', { name: 'C1', type: 'checkbox' });
  agreement.checked = false;
  f.elements.push(agreement);
  assert.deepEqual(f.run(), { state: 'manual', reason: 'PAGE' });
  assert.equal(agreement.checked, false);
  assert.equal(f.login.clicks, 0);
  assert.equal(f.password.value, 'fixture-password');
});
