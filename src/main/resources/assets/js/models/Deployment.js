var Backbone = require("backbone");

var Deployment = Backbone.Model.extend({
  defaults: function () {
    return {
      currentStep: 0,
      currentActions: [],
      affectedApps: [],
      totalSteps: 0,
      steps: []
    };
  },

  currentActionsString: function () {
    var actions = this.get("currentActions");
    var actionNames = actions.map(function (action) {
      return action.action;
    });
    return actionNames.join(", ");
  },

  affectedAppsString: function () {
    var actions = this.get("currentActions");
    var apps = actions.map(function (action) {
      return action.app;
    });
    return apps.join(", ");
  },

  destroy: function (options) {
    /* jshint -W030 */
    options || (options = {});
    /* jshint +W030 */

    if (!options.url && options.forceStop) {
      options.url = this.url() + "?force=true";
    }

    return Backbone.Model.prototype.destroy.call(this, options);
  },

  initialize: function (options) {
    this.options = options;
  }
});

module.exports = Deployment;
