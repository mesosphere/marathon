define([
  "models/SortableCollection",
  "models/App"
], function(SortableCollection, App) {
  return SortableCollection.extend({
    model: App,
    initialize: function(models, options) {
      this.options = options;
      this.setComparator("id");
      this.sort();
    },
    parse: function(response) {
      return response.apps;
    },
    url: "/v2/apps"
  });
});
