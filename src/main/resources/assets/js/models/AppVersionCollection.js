define([
  "Backbone",
  "models/AppVersion"
], function(Backbone, AppVersion) {
  return Backbone.Collection.extend({
    comparator: function(a, b) {
      return Date.parse(b.get("version")) - Date.parse(a.get("version"));
    },

    model: AppVersion,

    initialize: function(options) {
      this.options = options;
    },

    parse: function(response) {
      // API response is a list of strings. Use the strings as "versions" and
      // return objects to be turned into models by Backbone.
      return response.versions.map(function(v) {
        return {version: v, appId: this.options.appId};
      }, this);
    },

    url: function() {
      return "/v2/apps/" + this.options.appId + "/versions";
    }
  });
});
