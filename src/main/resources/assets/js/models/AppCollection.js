define([
  "Backbone",
  "models/App"
], function(Backbone, App) {
  return Backbone.Collection.extend({
    comparator: "id",
    model: App,
    url: function() {
      return "v1/apps";
    }
  });
});
