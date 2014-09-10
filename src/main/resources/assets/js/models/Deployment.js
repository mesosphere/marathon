define([
  "Backbone",
  "Underscore"
], function(Backbone, _) {
  "use strict";

  return Backbone.Model.extend({
    defaults: function() {
      return {
        currentStep: 0,
        currentActions: [],
        affectedApps: [],
        totalSteps: 0,
        steps: []
      };
    },

    currentActionsString: function() {
      return this.get("currentActions").toString();
    },

    affectedAppsString: function() {
      return this.get("currentActions").toString();
    },

    initialize: function(options) {
      this.options = options;
    }
  });
});

