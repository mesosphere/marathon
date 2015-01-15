
var AppVersion = require("../models/AppVersion");
var SortableCollection = require("../models/SortableCollection");

module.exports = SortableCollection.extend({
    model: AppVersion,

    initialize: function(models, options) {
      this.options = options;
      this.setComparator("getVersion");
      this.sort();
    },

    parse: function(response) {
      // API response is a list of strings. Use the strings as "versions" and
      // return objects to be turned into models by Backbone.
      return response.versions.map(function(v) {
        return {version: v, appId: this.options.appId};
      }, this);
    },

    url: function() {
      return "/v2/apps/" + this.options.appId + "/versions";
    }
  });
