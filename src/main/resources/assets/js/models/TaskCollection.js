define([
  "Backbone",
  "models/Task"
], function(Backbone, Task) {
  return Backbone.Collection.extend({
    initialize: function(models, options) {
      this.options = options;
    },
    model: Task,
    parse: function(response) {
      return response.tasks;
    },
    url: function() {
      return "/v2/apps/" + this.options.appId + "/tasks";
    }
  });
});
