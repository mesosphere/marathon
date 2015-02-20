var Backbone = require("backbone");

var noop = function () {};

var Router = Backbone.Router.extend({
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

  currentHash: function () { return Backbone.history.fragment; }
});

module.exports = Router;
