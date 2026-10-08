(function (account, password, providerSuffix, providerTitle) {
  'use strict';

  function result(state, reason) {
    return JSON.stringify({ state: state, reason: reason || '' });
  }
  function visible(element) {
    if (!element || element.hidden || element.type === 'hidden') return false;
    // The host may keep the WebView offscreen. Visibility comes from CSS and ancestors,
    // not a viewport intersection or element bounds.
    for (var current = element; current && current.nodeType !== 9; current = current.parentElement) {
      if (current.hidden) return false;
      var style = window.getComputedStyle(current);
      if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false;
    }
    return true;
  }
  function elements(selector) {
    return Array.prototype.filter.call(document.querySelectorAll(selector), visible);
  }
  function hasFunction(object, name) {
    return object && typeof object[name] === 'function';
  }
  function originalSubmit(form) {
    if (!form || form.name !== 'f1' || !hasFunction(window, 'ee')) return false;
    return typeof form.onsubmit === 'function' ||
      /^\s*return\s+ee\s*\(\s*1\s*\)\s*;?\s*$/.test(String(form.getAttribute('onsubmit') || ''));
  }
  function setValue(element, value) {
    element.value = value;
    element.dispatchEvent(new window.Event('input', { bubbles: true }));
    element.dispatchEvent(new window.Event('change', { bubbles: true }));
  }

  /**
   * 学校自己声明的运营商配置。实测 yc.gxnu.edu.cn 上是：
   *   {"yys":{"mode":"radiobutton","data":[{"suffix":""},{"suffix":"@ctc"},...]}}
   * 它同时告诉我们两件事：控件是哪种形态、以及这个学校**到底有哪几个**运营商。
   * 后者很重要 —— App 里列了广电网络(@gd)，而这所学校并没有这一项。
   */
  function carrierSuffixes() {
    try {
      var raw = window.carrier;
      if (!raw) return null;
      var parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
      var yys = parsed && parsed.yys;
      if (!yys || !yys.data || !yys.data.length) return null;
      return yys.data.map(function (item) {
        return String(item.suffix == null ? '' : item.suffix);
      });
    } catch (_) {
      return null;
    }
  }

  /**
   * 把运营商选成 suffix，返回 'ok'（已选中）或 'none'（这所学校没有这一项）。
   *
   * 为什么不能只找 `<select>`：运营商控件的形态由学校的 `carrier.yys.mode` 决定，实测这台
   * 门户给的是 `radiobutton`，页面上**一个 select 都没有**。原来只找
   * `select[name="ISP_select"]` 的写法因此永远匹配不到，用户看到的就是「运营商那里还是没有
   * 选择」。现在按学校声明的后缀集合去认控件，select 与单选按钮都能处理。
   *
   * 顺序上先认「后缀集合」再选值，是为了不误碰页面上其它同值控件 —— 空后缀尤其危险，
   * 很多无关控件也以空串为值。
   */
  function setProvider(suffix) {
    var wanted = String(suffix == null ? '' : suffix);
    // 学校没声明时的兜底：本门户文档化的四个后缀。没有它就认不出单选按钮组。
    var declared = carrierSuffixes() || ['', '@ctc', '@cuc', '@cmc', '@gd'];

    // 1) select 形态（部分模板/学校用它，字段名固定 ISP_select）
    var selects = elements('select[name="ISP_select"]');
    if (selects.length === 1 && !selects[0].disabled) {
      var option = Array.prototype.find.call(selects[0].options, function (item) {
        return !item.disabled && String(item.value) === wanted;
      });
      if (option) { setValue(selects[0], wanted); return 'ok'; }
      return 'none';
    }

    // 2) 单选按钮形态（本机实测的形态）。同一 name 的一组才算「那组运营商」。
    var radios = elements('input[type="radio"]').filter(function (input) {
      return !input.disabled && declared.indexOf(String(input.value)) !== -1;
    });
    if (radios.length) {
      var counts = {};
      radios.forEach(function (input) {
        var key = String(input.name);
        counts[key] = (counts[key] || 0) + 1;
      });
      var best = null;
      Object.keys(counts).forEach(function (key) {
        if (best === null || counts[key] > counts[best]) best = key;
      });
      var members = radios.filter(function (input) { return String(input.name) === best; });
      // 只有一项的「组」更可能是无关控件，不足以当成运营商选择器。
      if (members.length >= 2) {
        var chosen = members.filter(function (input) { return String(input.value) === wanted; })[0];
        if (!chosen) return 'none';
        // click() 才会让学校自己的脚本收到通知；直接改 checked 有些模板不认。
        if (!chosen.checked) chosen.click();
        chosen.dispatchEvent(new window.Event('change', { bubbles: true }));
        return 'ok';
      }
    }

    // 3) 名字不叫 ISP_select 的 select：只认值匹配的 option
    var anySelect = elements('select');
    for (var i = 0; i < anySelect.length; i++) {
      if (anySelect[i].disabled) continue;
      var found = Array.prototype.find.call(anySelect[i].options, function (item) {
        return !item.disabled && String(item.value) === wanted;
      });
      if (found) { setValue(anySelect[i], wanted); return 'ok'; }
    }

    // 4) 隐藏字段形态：提交时才读值（visible() 会滤掉 hidden，所以直接查）
    var hidden = document.querySelectorAll('input[name="ISP_select"]');
    for (var j = 0; j < hidden.length; j++) {
      if (String(hidden[j].type) === 'hidden') { setValue(hidden[j], wanted); return 'ok'; }
    }

    return 'none';
  }
  function validAddress(ip, ipv6) {
    var parts = String(ip || '').split('.');
    var ipv4 = parts.length === 4 && parts.every(function (part) {
      return /^\d{1,3}$/.test(part) && Number(part) <= 255;
    }) && parts.some(function (part) { return Number(part) !== 0; });
    return ipv4 || (/^[0-9a-fA-F:.]{2,45}$/.test(String(ipv6 || '')) &&
      String(ipv6).indexOf(':') !== -1 && String(ipv6) !== '::');
  }
  function errorCategory(text) {
    if (/验证码|校验码|captcha|verification\s*code/i.test(text)) return 'CAPTCHA';
    if (/账号|帐号|账户|密码|account|password|invalid\s*user/i.test(text)) return 'ACCOUNT';
    return 'PAGE';
  }
  function rejection() {
    var schoolError = window.error;
    if (schoolError) {
      var records = [schoolError.portalErr, schoolError.localErr];
      for (var i = 0; i < records.length; i++) {
        var record = records[i];
        if (!record || typeof record !== 'object') continue;
        var code = Number(record.ret_code);
        // The school documents ret_code 2 as already online. Wi-Fi verification decides success.
        if (code === 2) continue;
        if (code === 1 || code === 4 || code === 7) return 'ACCOUNT';
        if (code === 3 || code === 6 || code === 8 || code === 10 || code === 998) return 'PAGE';
        if (typeof record.msg === 'string' && record.msg.trim()) return errorCategory(record.msg);
      }
    }
    var messages = elements('#message,#error_message,[role="alert"],.error,.layui-layer-content');
    for (var j = 0; j < messages.length; j++) {
      var text = String(messages[j].innerText || messages[j].textContent || '').trim();
      if (text && /错误|失败|不正确|无效|拒绝|验证码|error|fail|invalid|incorrect|reject|captcha/i.test(text)) {
        return errorCategory(text);
      }
    }
    return '';
  }

  try {
    var location = window.location;
    if (window.top !== window || location.protocol !== 'https:' ||
        location.hostname !== 'yc.gxnu.edu.cn' ||
        (location.port !== '' && location.port !== '443' && location.port !== '802')) {
      return result('manual', 'PAGE');
    }

    var marker = window.__gxnuOfficialPortalBridge;
    if (!marker) {
      marker = { submitted: false, memoryStore: null };
      window.__gxnuOfficialPortalBridge = marker;
    }
    // The host preserves this fixed flag across main-frame navigation within one attempt.
    // A new document can still report errors, but cannot fill or click again.
    if (window.__campusOfficialSubmitted === true) marker.submitted = true;

    // The school's cookie wrapper uses store. Keep its contract, but retain credentials only
    // in this WebView's memory; the App owns their encrypted persistence.
    var store = window.store;
    if (hasFunction(store, 'get') && hasFunction(store, 'set') && marker.memoryStore !== store) {
      var memory = Object.create(null);
      store.set = function (key, value) { memory[String(key)] = value; return value; };
      store.get = function (key, fallback) {
        return Object.prototype.hasOwnProperty.call(memory, String(key)) ? memory[String(key)] : fallback;
      };
      store.remove = function (key) { delete memory[String(key)]; };
      store.clear = function () { memory = Object.create(null); };
      marker.memoryStore = store;
    }

    if (marker.submitted) {
      var reason = rejection();
      if (reason) return result(reason === 'CAPTCHA' ? 'manual' : 'rejected', reason);
      return result('submitted');
    }

    var page = window.page;
    var term = window.term;
    if (term && term.online && Number(term.online.result) === 1) return result('waiting');
    if (page && /^(?:pc_(?:1|3|5)|(?:mobile|hipad|vipad)_(?:31|33|35))$/.test(String(page.kind))) {
      return result('waiting');
    }

    if (document.readyState === 'loading' || !page || !page.index || !page.name || !term ||
        !validAddress(term.ip, term.ipv6) || !hasFunction(window.login, 'init') ||
        !hasFunction(window.login, 'login') ||
        (!hasFunction(window.login, 'portal_login') && !hasFunction(window.login, 'login_portal')) ||
        !hasFunction(window.util, '_jsonp') || !hasFunction(window.cookie, 'get') ||
        !hasFunction(window.cookie, 'set') || !hasFunction(store, 'get') ||
        !hasFunction(store, 'set') || marker.memoryStore !== store) {
      return result('waiting');
    }

    var captcha = elements('input').some(function (input) {
      return /captcha|verify_?code|check_?code|rand_?code|vcode/i.test(String(input.name) + ' ' + String(input.id));
    });
    if (captcha) return result('manual', 'CAPTCHA');

    var accounts = elements('input[name="DDDDD"]').filter(function (input) { return !input.disabled; });
    var passwords = elements('input[name="upass"]').filter(function (input) { return !input.disabled; });
    var buttons = elements('input[name="0MKKey"],button[name="0MKKey"]').filter(function (button) {
      return !button.disabled && !/注销|退出|logout|sign\s*out/i.test(String(button.value) + ' ' + String(button.textContent));
    });

    // Fill the form the moment its fields exist, without waiting for the click to be possible.
    // The school builds this form asynchronously and its shape varies, so an all-or-nothing bridge
    // left the fields empty whenever any submit precondition was not met -- the user saw an empty
    // login box and no explanation. Filling is idempotent and carries no risk; clicking is the part
    // that needs every precondition. So they are decided separately, below.
    var normalizedAccount = String(account).trim();
    // The official form appends the selected suffix itself, just as when a student types.
    while (/@(?:ctc|cuc|cmc|gd)$/i.test(normalizedAccount)) {
      normalizedAccount = normalizedAccount.replace(/@(?:ctc|cuc|cmc|gd)$/i, '').trimEnd();
    }
    var filledAccount = accounts.length === 1 && normalizedAccount.length > 0;
    var filledPassword = passwords.length === 1 && String(password).length > 0;
    if (filledAccount) setValue(accounts[0], normalizedAccount);
    if (filledPassword) setValue(passwords[0], String(password));

    var provider = setProvider(providerSuffix);

    if (accounts.length !== 1 || passwords.length !== 1 || buttons.length !== 1) {
      return result(filledAccount || filledPassword ? 'filled' : 'waiting');
    }
    var button = buttons[0];
    var form = button.form || button.closest('form');
    if (!originalSubmit(form) || accounts[0].form !== form || passwords[0].form !== form) {
      return result('filled');
    }
    if (elements('input[name="C1"][type="checkbox"]').some(function (checkbox) { return !checkbox.checked; })) {
      return result('manual', 'PAGE');
    }
    // 选不上运营商就不许提交：带着错误的运营商去认证，比不提交更糟。
    if (provider !== 'ok') return result('manual', 'PROVIDER');
    if (!normalizedAccount || !String(password).length) return result('rejected', 'ACCOUNT');

    elements('input[type="checkbox"]').forEach(function (checkbox) {
      if (/remember|savepass|save_pass|autologin|auto_login|记住|保存密码|自动登录/i.test(
          String(checkbox.name) + ' ' + String(checkbox.id) + ' ' + String(checkbox.getAttribute('title') || ''))) {
        checkbox.checked = false;
        checkbox.dispatchEvent(new window.Event('change', { bubbles: true }));
      }
    });
    // Mark before click so synchronous callbacks and later polling cannot resubmit.
    marker.submitted = true;
    button.click();
    return result('submitted');
  } catch (_) {
    // No exception text, payload, credential or school URL crosses the bridge.
    return result('manual', 'PAGE');
  }
})
