var Backbone = require("backbone");

var Info = Backbone.Model.extend({
  url: function () {
    return "v2/info";
  }
});

module.exports = Info;
