define([
], function() {
  return {
    componentDidMount: function() {
      this._boundForceUpdate = this.forceUpdate.bind(this, null);
      this.props.collection.on("all", this._boundForceUpdate, this);
    },
    componentWillUnmount: function() {
      this.props.collection.off("all", this._boundForceUpdate);
    }
  };
});
