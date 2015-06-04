/** @jsx React.DOM */

var React = require("react/addons");

var FilterComponent = React.createClass({
  displayName: "FilterComponent",
  propTypes: {
    onChange: React.PropTypes.func
  },

  getInitialState: function() {
    return  {filter:null};
  },

  getDefaultProps: function () {
    return { onChange: function () {} };
  },

  onInputChange: function (event) {
    var value = this.refs.filterInput.getDOMNode().value;
    this.updateFilter(value);
  },

  updateFilter:function(value){

    this.setState({filter:value});
    // dispatch value changes
    this.props.onChange(value);
  },

  clearFilter: function(){
    this.updateFilter(null);
  },


  render: function () {

    var id = this.props.id;
    var label = this.props.label;
    var placeholder = this.props.placeholder;

    return (
      <div class="form-group"  >
        <label >{label}
        <input type="text"
                 ref="filterInput"
                className="form-control"
                onChange={this.onInputChange}
                placeholder={placeholder}
                value={this.state.filter} />
                </label>
        <button type="button"
                className="btn btn-default"
                onClick={this.clearFilter}>Clear</button>
      </div>

    );
  }
});

module.exports = FilterComponent;
