var _ = require("underscore");

var BackboneMixin = {
  resolveModels: function () {
    var models = [];

    var name = this.constructor.displayName || "ANNOYMUS";

    if (_.isFunction (this.getBackboneModels)) {
      var manualModels = this.getBackboneModels();
      if (!_.isArray(manualModels)) {
        console.warn(
          "Component " + name + " needs to return an Array, not an " +
          typeof manualModels + ", for BackboneMixin to work."
        );
      } else {
        // filter out undefined/null's
        models = _.filter(manualModels,
          function (model) {
            if (model != null) {
              return model;
            }
          }
        );
      }
    } else {
      console.warn(
        "Please implement 'getBackboneModels' in component " + name +
        ", for BackboneMixin to work."
      );
    }

    return models;
  },

  // Binds a listener to a component's resource so the component can be updated
  // when the resource changes.
  //
  // An object that uses this mixin must implement `getBackboneModels` and
  // return an array of objects that extends `Backbone.Events`.
  // Common use cases are `Backbone.Model` and `Backbone.Collection`.
  componentDidMount: function () {
    this._boundForceUpdate = this.forceUpdate.bind(this, null);
    var models = this.resolveModels();
    models.forEach(function (model) {
      // There are more events that we can listen on. For most cases, we're
      // fetching pages of data, listening to add events causes superfluous
      // calls to render.
      model.on(
        "batch reset sync invalid change",
        this._boundForceUpdate,
        this
      );
      model.fetch({ reset: true });
    }, this);
  },

  componentWillUnmount: function () {
    var models = this.resolveModels();
    models.forEach(function (model) {
      model.off(
        "batch reset sync invalid change",
        this._boundForceUpdate,
        this
      );
    }, this);
  }
};

module.exports = BackboneMixin;
