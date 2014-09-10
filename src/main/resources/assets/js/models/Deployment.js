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
      var actionsString = actions.map(function(action) {
        return action.action;
      });
      return actionsString;
    },

    affectedAppsString: function() {
      var actions = this.get("currentActions");
      var apps = actions.map(function(action) {
        return action.apps;
      });
      return apps;
    },

    initialize: function(options) {
      this.options = options;
    }
  });
});

