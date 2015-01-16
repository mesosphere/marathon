var BackboneMixin = {

  // Binds a listener to a component's resource so the component can be updated
  // when the resource changes.
  //
  // An object that uses this mixin must implement `getResource` and return an
  // object that extends `Backbone.Events`. Common use cases are
  // `Backbone.Model` and `Backbone.Collection`.
    componentDidMount: function () {
      this._boundForceUpdate = this.forceUpdate.bind(this, null);
      this.getResource().on("all", this._boundForceUpdate, this);
    },
    componentWillUnmount: function () {
      this.getResource().off("all", this._boundForceUpdate);
    }
  };

module.exports = BackboneMixin;
