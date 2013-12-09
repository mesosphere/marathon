define([
  "Backbone",
  "models/App"
], function(Backbone, App) {
  return Backbone.Collection.extend({
    comparator: "id",
    model: App,
    url: "v1/apps"
  });
});
