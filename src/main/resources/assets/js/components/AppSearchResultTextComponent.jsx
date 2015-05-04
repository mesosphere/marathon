/** jsx React.DOM */

var React = require("react/addons");

var AppSearchComponent = React.createClass({
  displayName: "AppSearchResultTextComponent",

  propTypes: {
    filteredCount: React.PropTypes.number.isRequired,
    overallCount: React.PropTypes.number.isRequired
  },

  render: function () {
    return (
      <p>{this.props.filteredCount} / {this.props.overallCount} apps matched your query</p>
    );
  }
});

module.exports = AppSearchComponent;
