define([
  "Backbone"
], function(Backbone) {
  "use strict";

  return Backbone.Model.extend({
    defaults: function() {
      return {
        affectedApplications: [],
        steps: []
      };
    },

    initialize: function(options) {
      this.options = options;
    }
  });
});

