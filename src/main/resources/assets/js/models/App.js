define([
  "Backbone",
  "Underscore",
  "models/TaskCollection"
], function(Backbone, _, TaskCollection) {
  return Backbone.Model.extend({
    defaults: function() {
      return {
        id: _.uniqueId("app_"),
        cmd: null,
        mem: 10.0,
        cpus: 0.1,
        instances: 1,
        uris: []
      };
    },
    initialize: function() {
      this.set("tasks", new TaskCollection(null, {appId: this.id}));
    },
    scale: function(instances) {
      this.set("instances", instances);
      this.save();
    },
    sync: function(method, model, options) {
      var localOptions = options || {};
      var upperCaseMethod = method.toUpperCase();

      if (upperCaseMethod in model.urls) {
        options.contentType = "application/json";
        options.data = JSON.stringify(model.toJSON());
        options.method = "POST";
        options.url = model.urls[upperCaseMethod];
      }

      Backbone.sync.apply(this, [method, model, localOptions]);
    },
    urls: {
      "DELETE": "v1/apps/stop",
      "UPDATE": "v1/apps/scale"
    }
  });
});
