define([
  "Backbone",
  "models/Deployment"
], function(Backbone, Deployment) {
  "use strict";

  return Backbone.Collection.extend({
    model: Deployment,

    initialize: function(models, options) {
      this.options = options;
    },

    url: function() {
      return "/v2/deployments";
    }
  });
});
