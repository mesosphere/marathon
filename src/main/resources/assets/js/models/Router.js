define([
  "Backbone"
], function(Backbone) {
  "use strict";

  var noop = function () {};

  return Backbone.Router.extend({

    routes: {
      "": "apps",
      "apps": "apps",
      "deployments": "deployments",
      "app/:appid(/:view)": "app",
      "newapp": "newapp",
      "about": "about"
    },

    apps: noop,
    app: noop,
    newapp: noop,
    deployments: noop,
    task: noop,
    about: noop,

    lastRoute: {
      route: null,
      params: null
    },

    current: function() { return Backbone.history.fragment; }
  });
});
