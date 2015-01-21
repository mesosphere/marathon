var Backbone = require("backbone");

module.exports = Backbone.Collection.extend({
  fetching: false,

  // Extending fetch with a fetching status
  fetch: function (options) {
    if (this.fetching) {
      return;
    }

    this.fetching = true;

    var xhr = Backbone.Collection.prototype.fetch.call(this, options);

    if (!xhr) {
      this.fetching = false;
      return;
    }

    xhr.done(function () {
      this.fetching = false;
    }.bind(this)).fail(function () {
      this.fetching = false;
    }.bind(this));
  }
});
