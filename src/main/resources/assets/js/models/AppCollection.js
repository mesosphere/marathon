var App = require("../models/App");
var SortableCollection = require("../models/SortableCollection");

var AppCollection = SortableCollection.extend({
  model: App,

  initialize: function (models, options) {
    this.options = options;
    this.setComparator("-id");
    this.sort();
  },

  parse: function (response) {
    return response.apps;
  },

  url: "v2/apps"
});

module.exports = AppCollection;
