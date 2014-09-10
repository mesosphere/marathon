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

    formatCurrentActions: function() {
      var currentActions = this.get("currentActions");

      if (_.isEmpty(currentActions)) {
        return "-";
      }

      var string = "";
      var length = currentActions.length;

      currentActions.forEach(function(action, index) {
        string += action.action;
        if (index < length - 1) {
          string += "\n";
        }
      });

      return string;
    },

    formatAffectedApps: function() {
      var currentActions = this.get("currentActions");

      if (_.isEmpty(currentActions)) {
        return "-";
      }

      var string = "";
      var length = currentActions.length;

      currentActions.forEach(function(action, index) {
        string += action.apps;
        if (index < length - 1) {
          string += "\n";
        }
      });

      return string;
    },

    initialize: function(options) {
      this.options = options;
    }
  });
});

