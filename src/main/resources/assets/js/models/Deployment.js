define([
  "Backbone",
  "Underscore"
], function(Backbone, _) {
  "use strict";

  return Backbone.Model.extend({
    defaults: function() {
      return {
        // id: "09184764-3c73-430f-8127-fdfbb61dc94d",
        // version: "2014-08-28T17:17:53.786Z",
        affectedApplications: [],
        steps: []
      };
    },

    initialize: function(options) {
      this.options = options;
    }
  });
});

