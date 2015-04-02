/** jsx React.DOM */

var React = require("react/addons");

var AppSearchComponent = React.createClass({
  displayName: "AppSearchComponent",

  propTypes: {
    onSearch: React.PropTypes.func.isRequired
  },

  getInitialState: function () {
    return {
      searchValue: null
    };
  },

  handleChange: function (event) {
    this.setState({searchValue: event.target.value});
  },

  search: function (event) {
    event.preventDefault();
    this.props.onSearch(this.state.searchValue);
  },

  resetSearch: function () {
    this.setState({searchValue: null});
    this.props.onSearch();
  },

  render: function () {
    var inputClass = React.addons.classSet({
      "input-success": this.state.searchValue,
      "form-control": true
    });

    return (
      <form onSubmit={this.search}>
        <input className={inputClass} type="text" value={this.state.searchValue}
          onChange={this.handleChange} placeholder="Filter List" />
        <span onClick={this.resetSearch} className="close-input-btn">reset</span>
      </form>
    );
  }
});

module.exports = AppSearchComponent;
