define([
  "Backbone",
  "jquery"
], function(Backbone, $) {
  return Backbone.Model.extend({
    sync: function(method, model, options) {
      var _options = options || {};
      var upperCaseMethod = method.toUpperCase();

      if (upperCaseMethod in model.urls) {
        _options.contentType = "application/json";
        _options.method = "POST";

        // The "/kill" endpoint expects POST values to be query parameters. Use
        // jQuery to construct the param string and append it to the normal URL.
        _options.url = model.urls[upperCaseMethod] + "?" + $.param({
          appId: this.collection.options.appId,
          id: this.id,
          scale: _options.scale
        });
      }

      Backbone.sync.apply(this, [method, model, _options]);
    },
    urls: {
      "DELETE": "v1/tasks/kill"
    }
  });
});
