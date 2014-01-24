define([
  "Backbone",
  "jquery"
], function(Backbone, $) {
  return Backbone.Model.extend({
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
