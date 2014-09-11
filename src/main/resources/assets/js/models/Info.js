define([
  "Backbone"
], function(Backbone) {
  "use strict";

  return Backbone.Model.extend({
    url: function() {
      return "/v2/info";
    }
  });
});
