var SortableCollection = require("../models/SortableCollection");
var Task = require("../models/Task");

var TaskCollection = SortableCollection.extend({
  model: Task,

  initialize: function (models, options) {
    this.options = options;
    this.setComparator("updatedAt");
    this.sort();
  },

  parse: function (response) {
    return response.app.tasks;
  },

  url: function () {
    return "v2/apps/" + this.options.appId;
  }
});

module.exports = TaskCollection;
