package me.weishu.kernelsu.ui.webmanager

/**
 * The native WebView bridge remains available; this bridge is only injected
 * into module pages served by WebManagerServer.
 */
internal val WEBUI_BRIDGE_SCRIPT_TEMPLATE: String = """
(function () {
  "use strict";
  var moduleId = __MODULE_ID__;
  var moduleInfoJson = __MODULE_INFO_JSON__;
  var packageData = __PACKAGE_DATA__;
  var tokenPrefix = __TOKEN_PREFIX__;

  function endpoint(path) {
    return tokenPrefix + path + (path.indexOf("?") >= 0 ? "&" : "?") +
      "module=" + encodeURIComponent(moduleId);
  }

  function normalizeOptions(options) {
    if (!options) return {};
    if (typeof options === "string") {
      try { return JSON.parse(options); } catch (_) { return {}; }
    }
    return options;
  }

  function requestExec(command, args, options) {
    var payload = { command: String(command || ""), options: normalizeOptions(options) };
    if (args !== null) payload.args = args || [];
    return fetch(endpoint("/api/webui/exec"), {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    }).then(function (response) {
      return response.text().then(function (text) {
        var data;
        try { data = JSON.parse(text); } catch (_) { data = { error: text }; }
        if (!response.ok) {
          var error = new Error(data.error || "WebUI command failed");
          error.errno = data.errno || response.status;
          error.stderr = data.stderr || "";
          throw error;
        }
        return data;
      });
    });
  }

  function emitter() {
    var listeners = {};
    return {
      on: function (event, listener) {
        (listeners[event] || (listeners[event] = [])).push(listener);
        return this;
      },
      emit: function (event) {
        var args = Array.prototype.slice.call(arguments, 1);
        (listeners[event] || []).slice().forEach(function (listener) {
          try { listener.apply(null, args); } catch (error) { setTimeout(function () { throw error; }, 0); }
        });
      }
    };
  }

  function startSpawn(command, args, options, child) {
    requestExec(command, args, options).then(function (result) {
      if (result.stdout) child.stdout.emit("data", result.stdout);
      if (result.stderr) child.stderr.emit("data", result.stderr);
      child.emit("exit", result.errno);
    }).catch(function (error) {
      child.emit("error", error);
      child.emit("exit", error.errno || 126);
    });
  }

  var ksu = window.ksu || {};
  ksu.exec = function (command, options, callback) {
    var request = requestExec(command, null, options);
    if (typeof callback === "string") {
      request.then(function (result) {
        if (typeof window[callback] === "function") {
          window[callback](result.errno, result.stdout || "", result.stderr || "");
        }
      }).catch(function (error) {
        if (typeof window[callback] === "function") {
          window[callback](error.errno || 126, "", error.message || "WebUI command failed");
        }
      });
      return;
    }
    if (typeof callback === "function") {
      request.then(function (result) { callback(result.errno, result.stdout || "", result.stderr || ""); });
      return;
    }
    return request;
  };

  ksu.spawn = function (command, args, options, callback) {
    var parsedArgs = args;
    var parsedOptions = options;
    if (typeof parsedArgs === "string") {
      try { parsedArgs = JSON.parse(parsedArgs); } catch (_) { parsedArgs = []; }
    }
    if (!(parsedArgs instanceof Array)) {
      parsedOptions = parsedArgs;
      parsedArgs = [];
    }
    parsedOptions = normalizeOptions(parsedOptions);
    var child = emitter();
    child.stdout = emitter();
    child.stderr = emitter();
    if (typeof callback === "string" && window[callback]) {
      child = window[callback];
    }
    startSpawn(command, parsedArgs, parsedOptions, child);
    return child;
  };

  ksu.moduleInfo = function () { return moduleInfoJson; };
  ksu.listPackages = function (type) {
    var wanted = String(type || "all").toLowerCase();
    return JSON.stringify(packageData.filter(function (item) {
      return wanted === "all" || (wanted === "system" && item.isSystem) ||
        (wanted === "user" && !item.isSystem);
    }).map(function (item) { return item.packageName; }));
  };
  ksu.getPackagesInfo = function (packages) {
    var names = packages;
    if (typeof names === "string") {
      try { names = JSON.parse(names); } catch (_) { names = []; }
    }
    if (!(names instanceof Array)) names = [];
    return JSON.stringify(names.map(function (name) {
      var item = packageData.find(function (candidate) { return candidate.packageName === name; });
      return item || { packageName: name, error: "Package not found or inaccessible" };
    }));
  };
  ksu.toast = function (message) {
    var old = document.getElementById("__apkesu_webui_toast");
    if (old) old.remove();
    var toast = document.createElement("div");
    toast.id = "__apkesu_webui_toast";
    toast.textContent = String(message || "");
    toast.style.cssText = "position:fixed;left:50%;bottom:24px;transform:translateX(-50%);z-index:2147483647;padding:10px 14px;border-radius:8px;background:#202124;color:#fff;font:14px system-ui;box-shadow:0 4px 18px #0006";
    document.body.appendChild(toast);
    setTimeout(function () { if (toast.isConnected) toast.remove(); }, 2400);
  };
  ksu.fullScreen = function (enable) {
    var promise = enable ? document.documentElement.requestFullscreen : document.exitFullscreen;
    if (typeof promise === "function") {
      try { return enable ? document.documentElement.requestFullscreen() : document.exitFullscreen(); } catch (_) {}
    }
  };
  ksu.enableEdgeToEdge = function (enable) {
    document.documentElement.dataset.apkesuEdgeToEdge = enable ? "true" : "false";
  };
  ksu.exit = function () {
    try { history.back(); } catch (_) { location.href = "/"; }
  };
  window.ksu = ksu;

  function iconUrl(value) {
    var text = String(value || "");
    return text.indexOf("ksu://icon/") === 0
      ? tokenPrefix + "/api/webui/icon/" + encodeURIComponent(text.substring(11)) +
        "?module=" + encodeURIComponent(moduleId)
      : value;
  }
  var originalSetAttribute = Element.prototype.setAttribute;
  Element.prototype.setAttribute = function (name, value) {
    if (String(name).toLowerCase() === "src") value = iconUrl(value);
    return originalSetAttribute.call(this, name, value);
  };
  if (window.HTMLImageElement) {
    var descriptor = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "src");
    if (descriptor && descriptor.set && descriptor.get) {
      Object.defineProperty(HTMLImageElement.prototype, "src", {
        configurable: descriptor.configurable,
        enumerable: descriptor.enumerable,
        get: descriptor.get,
        set: function (value) { descriptor.set.call(this, iconUrl(value)); }
      });
    }
  }
})();
""".trimIndent()
