/** @jsx React.DOM */

var React = require("react/addons");

var AppListFilterComponent = React.createClass({
  displayName: "AppListFilterComponent",

  propTypes: {
    onChange: React.PropTypes.func.isRequired
  },

  getDefaultProps: function () {
    return {
      value: "",
      size: 0,
      numFiltered: null
    };
  },

  handleChange: function (event) {
    this.props.onChange(event.target.value);
  },

  handleClear: function () {
    this.props.value = "";
    this.props.onChange("");
  },

  render: function () {
    var filterClassSet = React.addons.classSet({
      "filter-filled": !!this.props.value,
      "form-control": true
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <form className="form-inline list-filter">
        <div className="form-group">
          <input
            type="text"
            value={this.props.value}
            onChange={this.handleChange}
            className={filterClassSet}
            placeholder="Filter list" />

            { this.props.value ? <span className="input-clear" onClick={this.handleClear}>x</span> : null }
        </div>
      </form>
    );
  }
});

module.exports = AppListFilterComponent;
