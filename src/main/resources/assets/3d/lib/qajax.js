/*
 * Qajax.js - Simple Promise ajax library based on Q
 */
/*jslint newcap: true */
(function (global, definition) {
  if (typeof exports === "object") {
    module.exports = definition;
  }
  else if (typeof define === 'function' && define.amd){
    define(['q'], function (Q) {
      return definition(Q, global.XMLHttpRequest, global.FormData);
    });
  }
  else {
    global.Qajax = definition(global.Q, global.XMLHttpRequest, global.FormData);
  }
})(this, function (Q, XMLHttpRequest, FormData) {
  "use strict";

  var CONTENT_TYPE = "Content-Type";
  var neverEnding = Q.defer().promise;

  function noop (){}

  // Serialize a map of properties (as a JavaScript object) to a query string
  function serializeQuery(paramsObj) {
    var k, params = [];
    for (k in paramsObj) {
      if (paramsObj.hasOwnProperty(k) && paramsObj[k] !== undefined) {
        params.push(encodeURIComponent(k) + "=" + encodeURIComponent(paramsObj[k]));
      }
    }
    return params.join("&");
  }

  function hasQuery(url) {
    return (url.indexOf("?") === -1);
  }

  function cloneObject (obj) {
    var clone = {};
    if (obj) {
      for (var key in obj) {
        if (obj.hasOwnProperty(key)) {
          clone[key] = obj[key];
        }
      }
    }
    return clone;
  }

  function QajaxBuilder (urlOrSettings, maybeSettings) {
    if (arguments.length === 0) throw new Error("Qajax: settings are required");

    var settings;
    if (typeof urlOrSettings === "string") {
      settings = typeof maybeSettings === 'object' && maybeSettings || {};
      settings.url = urlOrSettings;
    }
    else if (typeof urlOrSettings === "object") {
      settings = urlOrSettings;
    }
    else {
      throw new Error("Qajax: settings must be an object");
    }

    if (!settings.url) {
      throw new Error("Qajax: settings.url is required");
    }
    if ("cancellation" in settings && !Q.isPromiseAlike(settings.cancellation)) {
      throw new Error("cancellation must be a Promise.");
    }

    // Instance defaults (other defaults are extended by prototype)
    this.headers = cloneObject(this.headers);
    this.params = {};
    for (var key in settings)
      this[key] = settings[key];

    // Handle the settings to prepare some work.
    var params = this.params;
    var cacheParam = this.cache;
    if (cacheParam)
      params[cacheParam === true ? "_" : cacheParam] = (new Date()).getTime();

    // Let's build the url based on the configuration
    var url = this.base + this.url;
    var queryParams = serializeQuery(params);
    if (queryParams)
      url = url + (hasQuery(url) ? "?" : "&") + queryParams;
    this.url = url;

    // if data is a Javascript object, JSON is used
    var data = this.data;
    var headers = this.headers;
    if (
      data !== null &&
      typeof data === "object" &&
      (!FormData || !(data instanceof FormData))) {
      if (!(CONTENT_TYPE in headers))
        headers[CONTENT_TYPE] = "application/json";
      this.data = JSON.stringify(data);
    }

    // Protect send from any exception which will be encapsulated in a failure.
    var _send = this._send;
    var ctx = this;
    this.send = function () {
      return Q.fcall(function () { return _send.call(ctx); });
    };
  }

  QajaxBuilder.prototype = {

    // Qajax defaults are stored in the QajaxBuilder prototype*

    log: noop, // Provide a `log` function. by default there won't be logs.
    timeout: 60000,
    cache: typeof window === "undefined" ? false : !!(window.ActiveXObject || "ActiveXObject" in window), // By default, only enabled on old IE (also the presence of ActiveXObject is a nice correlation with the cache bug)
    method: "GET",
    base: "",
    withCredentials: false,
    cancellation: neverEnding,


    _send: function () {
      var xhr = new XMLHttpRequest(),
        xhrResult = Q.defer(),
        log = this.log,
        method = this.method,
        url = this.url;

      // Bind the XHR finished callback
      xhr.onreadystatechange = function () {
        if (xhr.readyState === 4) {
          try {
            log(method + " " + url + " => " + xhr.status);
            if (xhr.status) {
              xhrResult.resolve(xhr);
            } else {
              xhrResult.reject(xhr); // this case occured mainly when xhr.abort() has been called.
            }
          } catch (e) {
            xhrResult.reject(xhr); // IE could throw an error
          }
        }
      };

      xhr.onprogress = function (progress) {
        xhrResult.notify(progress);
      };

      xhr.open(method, url, true);

      if (this.responseType)
        xhr.responseType = this.responseType;

      var headers = this.headers;
      for (var h in headers)
        if (headers.hasOwnProperty(h))
          xhr.setRequestHeader(h, headers[h]);

      if (this.withCredentials)
        xhr.withCredentials = true;

      // Send the XHR
      var data = this.data;
      if (data !== undefined && data !== null)
        xhr.send(data);
      else
        xhr.send();

      this.cancellation.fin(function () {
        if (!xhrResult.promise.isFulfilled()) {
          log("Qajax cancellation reached.");
          xhr.abort();
        }
      });

      // If no timeout, just return the promise
      if (!this.timeout) {
        return xhrResult.promise;
      }
      // Else, either the xhr promise or the timeout is reached
      else {
        return xhrResult.promise.timeout(this.timeout).fail(function (errorOrXHR) {
          // If timeout has reached (Error is triggered)
          if (errorOrXHR instanceof Error) {
            log("Qajax request delay reach in " + method + " " + url);
            xhr.abort(); // Abort this XHR so it can reject xhrResult
          }
          // Make the promise fail again.
          throw xhr;
        });
      }
    }
  };


  function Qajax (urlOrSettings, maybeSettings) {
    return new QajaxBuilder(urlOrSettings, maybeSettings).send();
  }

  // Defaults settings of Qajax are bound to the QajaxBuilder.prototype.
  Qajax.defaults = QajaxBuilder.prototype;

  // The builder is exposed in case you want different instance of Qajax (with different defaults)
  Qajax.Builder = QajaxBuilder;


  /// Some helpers are attached to Qajax object.

  Qajax.filterStatus = function (validStatus) {
    var log = this.log;
    var check, typ;
    typ = typeof validStatus;
    if (typ === "function") {
      check = validStatus;
    } else if (typ === "number") {
      check = function (s) {
        return s === validStatus;
      };
    } else {
      throw "validStatus type " + typ + " unsupported";
    }
    return function (xhr) {
      var status = 0;
      try {
        status = xhr.status; // IE can fail to access status
      } catch (e) {
        log("Qajax: failed to read xhr.status");
      }
      if (status === 1223) {
        status = 204; // 204 No Content IE bug
      }
      return check(status) ? Q.resolve(xhr) : Q.reject(xhr);
    };
  };

  Qajax.filterSuccess = Qajax.filterStatus(function (s) {
    return s >= 200 && s < 300 || s === 304;
  });

  Qajax.toJSON = function (xhr) {
    return Q.fcall(function () {
      return JSON.parse(xhr.responseText);
    });
  };

  Qajax.getJSON = function (url) {
    return Qajax({ url: url, method: "GET" })
      .then(Qajax.filterSuccess)
      .then(Qajax.toJSON);
  };

  Qajax.serialize = serializeQuery;

  return Qajax;

});
