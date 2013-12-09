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
    }
  });
});
