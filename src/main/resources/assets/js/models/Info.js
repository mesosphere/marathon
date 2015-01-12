
var Backbone = require("backbone");

module.exports = Backbone.Model.extend({
    url: function() {
      return "/v2/info";
    }
  });
