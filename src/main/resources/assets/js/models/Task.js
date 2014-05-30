define([
  "Backbone",
  "jquery"
], function(Backbone, $, _) {
  var STATUS_STAGED = "Staged";
  var STATUS_STARTED = "Started";

  // Model attributes that are parseable as dates.
  var DATE_ATTRIBUTES = ["stagedAt", "startedAt", "version"];

  return Backbone.Model.extend({
    isStarted: function() {
      return this.get("status") === STATUS_STARTED;
    },

    isStaged: function() {
      return this.get("status") === STATUS_STAGED;
    },

    parse: function(response) {
      var _this = this;
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

      var isHealthy = true;
      var healthCheckResults = response["healthCheckResults"];
      var msg = "";
      healthCheckResults.forEach(function (hc, index) {
        if (hc) {
          isHealthy = hc.alive;
          if (!isHealthy) {
            var failedCheck = _this.collection.options.healthChecks[index];
            msg = "Warning: '" + failedCheck.protocol +
              ": -p " + response["host"] + failedCheck.path + "'." +
              "\nHealth check returned with status: " +
              "'" + hc.lastFailureCause + "'";
            return;
          }
        }
      });
      this.set("healthMsg", msg);
      this.set("health", isHealthy);

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
  });
});
