define([
], function() {
  return {
    componentDidMount: function() {
      this._boundForceUpdate = this.forceUpdate.bind(this, null);
      this.getBackboneObject().on("all", this._boundForceUpdate, this);
    },
    componentWillUnmount: function() {
      this.getBackboneObject().off("all", this._boundForceUpdate);
    },
    getBackboneObject: function() {
      return this.props.collection || this.props.model;
    }
  };
});
