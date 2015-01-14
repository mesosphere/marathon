define([
  "Backbone"
], function(Backbone) {
  "use strict";

  var noop = function () {};

  return Backbone.Router.extend({

    routes: {
      "": "apps",
      "apps(/:appid)(/:view)": "apps",
      "deployments": "deployments",
      "newapp": "newapp",
      "about": "about"
    },

    apps: noop,
    newapp: noop,
    deployments: noop,
    task: noop,
    about: noop,

    lastRoute: {
      route: null,
      params: null
    },

    currentHash: function () { return Backbone.history.fragment; }
  });
});
