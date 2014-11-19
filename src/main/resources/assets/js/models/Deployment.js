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
      var actions = this.get("currentActions");
      var actionNames = actions.map(function(action) {
        return action.action;
      });
      return actionNames.join(", ");
    },

    affectedAppsString: function() {
      var actions = this.get("currentActions");
      var apps = actions.map(function(action) {
        return action.app;
      });
      return apps.join(", ");
    },

    initialize: function(options) {
      this.options = options;
    }
  });
});

