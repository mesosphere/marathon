define([
  "models/SortableCollection",
  "models/Task"
], function(SortableCollection, Task) {
  "use strict";

  return SortableCollection.extend({
    model: Task,

    initialize: function(models, options) {
      this.options = options;
      this.setComparator("updatedAt");
      this.sort();
    },

    parse: function(response) {
      return response.app.tasks;
    },

    url: function() {
      return "/v2/apps/" + this.options.appId;
    }
  });
});
