define([
  "Backbone",
  "models/App"
], function(Backbone, App) {
  return Backbone.Collection.extend({
    firstLoadFinished: false,
    comparator: "id",
    model: App,
    parse: function(response) {
      return response.apps;
    },
    url: "/v2/apps"
  });
});
