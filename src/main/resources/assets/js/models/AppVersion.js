define([
  "Backbone"
], function(Backbone) {
  "use strict";

  var AppVersion = Backbone.Model.extend({
    idAttribute: "version",

    initialize: function(options) {
      this.options = options;
    },

    getVersion: function() {
      return Date.parse(this.get("version"));
    },

    url: function() {
      return "/v2/apps/" + this.options.appId + "/versions/" + this.get("version");
    }
  });

  // creates an AppVersion Model of an App
  AppVersion.fromApp = function fromApp(app) {
    var appVersion = new AppVersion();
    appVersion.set(app.attributes);
    // make suer date is a string
    appVersion.set({
      "version": appVersion.get("version").toISOString()
    });
    // transfer app id
    appVersion.options = {
      appId: app.get("id")
    };

    return appVersion;
  };

  return AppVersion;
});
