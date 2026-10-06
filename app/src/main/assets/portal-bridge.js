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
    if (accounts.length !== 1 || passwords.length !== 1 || buttons.length !== 1) return result('waiting');
    var button = buttons[0];
    var form = button.form || button.closest('form');
    if (!originalSubmit(form) || accounts[0].form !== form || passwords[0].form !== form) return result('waiting');
    if (elements('input[name="C1"][type="checkbox"]').some(function (checkbox) { return !checkbox.checked; })) {
      return result('manual', 'PAGE');
    }

    var providers = elements('select[name="ISP_select"]');
    if (providers.length !== 1) return result('manual', 'PAGE');
    var select = providers[0];
    var option = Array.prototype.find.call(select.options, function (item) {
      return !item.disabled && String(item.value) === providerSuffix;
    });
    if (!option || select.disabled) return result('manual', 'PAGE');

    var normalizedAccount = String(account).trim();
    // The official form appends the selected suffix itself, just as when a student types.
    while (/@(?:ctc|cuc|cmc|gd)$/i.test(normalizedAccount)) {
      normalizedAccount = normalizedAccount.replace(/@(?:ctc|cuc|cmc|gd)$/i, '').trimEnd();
    }
    if (!normalizedAccount || !String(password).length) return result('rejected', 'ACCOUNT');

    elements('input[type="checkbox"]').forEach(function (checkbox) {
      if (/remember|savepass|save_pass|autologin|auto_login|记住|保存密码|自动登录/i.test(
          String(checkbox.name) + ' ' + String(checkbox.id) + ' ' + String(checkbox.getAttribute('title') || ''))) {
        checkbox.checked = false;
        checkbox.dispatchEvent(new window.Event('change', { bubbles: true }));
      }
    });
    setValue(select, providerSuffix);
    setValue(accounts[0], normalizedAccount);
    setValue(passwords[0], String(password));
    // Mark before click so synchronous callbacks and later polling cannot resubmit.
    marker.submitted = true;
    button.click();
    return result('submitted');
  } catch (_) {
    // No exception text, payload, credential or school URL crosses the bridge.
    return result('manual', 'PAGE');
  }
})
