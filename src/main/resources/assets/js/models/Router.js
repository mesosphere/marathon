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
      "task(/:taskid)": "task",
      "about": "about"
    },

    apps: noop,
    app: noop,
    deployments: noop,
    task: noop,
    about: noop,

    current: function() { return Backbone.history.fragment; }
  });
});
