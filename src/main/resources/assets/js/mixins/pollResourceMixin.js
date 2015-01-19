"use strict";

var UPDATE_INTERVAL = 5000;

var pollResourceMixin = {
  poll: function () {
    if (typeof this._pollResource === "function") {
      this._pollResource();
    }
  },

  setPollResource: function (func) {
    if (this._pollResource !== func) {
      // Kill any poll that is in flight to ensure it doesn't fire after having changed
      // the `_pollResource` function.
      this.stopPolling();
      this._pollResource = func;
      this.startPolling();
    }
  },

  startPolling: function () {
    if (this._interval == null) {
      this.poll();
      this._interval = setInterval(this.poll, UPDATE_INTERVAL);
    }
  },

  stopPolling: function () {
    if (this._interval != null) {
      clearInterval(this._interval);
      this._interval = null;
    }
  }
};

module.exports = pollResourceMixin;
