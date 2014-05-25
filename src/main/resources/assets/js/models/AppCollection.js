define([
  "models/SortableCollection",
  "models/App"
], function(SortableCollection, App) {
  return SortableCollection.extend({
    comparator: "id",
    model: App,
    initialize: function(models, options) {
      this.options = options;
      this.sortByAttr("id");
    },
    parse: function(response) {
      return response.apps;
    },
    url: "/v2/apps"
  });
});
