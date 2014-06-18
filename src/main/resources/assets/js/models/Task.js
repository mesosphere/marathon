define([
  "Backbone",
  "jquery"
], function(Backbone, $) {
  "use strict";

  var STATUS_STAGED = "Staged";
  var STATUS_STARTED = "Started";
  var HEALTH = {
    HEALTHY: 0,
    UNHEALTHY: 1,
    UNKNOWN: 2
  };

  // Model attributes that are parseable as dates.
  var DATE_ATTRIBUTES = ["stagedAt", "startedAt", "version"];

  return Backbone.Model.extend({
    isStarted: function() {
      return this.get("status") === STATUS_STARTED;
    },

    isStaged: function() {
      return this.get("status") === STATUS_STAGED;
    },

    getHealth: function() {
      var nullResult = true;
      var health = false;
      var healthCheckResults = this.get("healthCheckResults");
      if (healthCheckResults != null) {
        health = healthCheckResults.every(function (hcr) {
          if (hcr) {
            nullResult = false;
            return hcr.alive;
          } else { // might be null
            return false;
          }
        });
      }
      if (!health && nullResult) { // health check has not returned yet
        return HEALTH.UNKNOWN;
      } else {
        return health ? HEALTH.HEALTHY : HEALTH.UNHEALTHY;
      }
    },

    parse: function(response) {
      // Parse all known date attributes as real Date objects.
      DATE_ATTRIBUTES.forEach(function(attr) {
        var parsedAttr = Date.parse(response[attr]);
        if (!isNaN(parsedAttr)) { response[attr] = new Date(parsedAttr); }
      });

      if (response.startedAt != null) {
        response.status = STATUS_STARTED;
        response.updatedAt = response.startedAt;
      } else if (response.stagedAt != null) {
        response.status = STATUS_STAGED;
        response.updatedAt = response.stagedAt;
      }
      return response;
    },

    sync: function(method, model, options) {
      var _options = options || {};
      var upperCaseMethod = method.toUpperCase();

      if (upperCaseMethod === "DELETE") {
        // The "/kill" endpoint expects certain POST values to be query
        // parameters. Construct the param string and append it to the normal
        // URL.
        _options.url = model.url() + "?" + $.param({
          scale: _options.scale
        });
      }

      return Backbone.sync.call(this, method, model, _options);
    }
  }, {HEALTH: HEALTH});
});
