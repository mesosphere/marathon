define([
  "Backbone",
  "jquery"
], function(Backbone, $, _) {
  var STATUS_STAGED = "Staged";
  var STATUS_STARTED = "Started";

  return Backbone.Model.extend({
    isStarted: function() {
      return this.get("status") === STATUS_STARTED;
    },
    isStaged: function() {
      return this.get("status") === STATUS_STAGED;
    },
    parse: function(response) {
      var parsedStartedAt = Date.parse(response.startedAt);
      var parsedStagedAt = Date.parse(response.stagedAt);

      if (!isNaN(parsedStartedAt)) response.startedAt = new Date(parsedStartedAt);
      if (!isNaN(parsedStagedAt)) response.stagedAt = new Date(parsedStagedAt);

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
  });
});
