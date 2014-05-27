define([
  "models/SortableCollection",
  "models/Task"
], function(SortableCollection, Task) {
  var TaskCollection = SortableCollection.extend({
    initialize: function(models, options) {
      this.options = options;
      this.setComparator("-updatedAt");
      this.sort();
    },
    model: Task,
    parse: function(response) {
      return response.tasks;
    },
    url: function() {
      return "/v2/apps/" + this.options.appId + "/tasks";
    }
  });

  return TaskCollection;
});
