/** @jsx React.DOM */

var React = require("react/addons");
var States = require("../constants/States");
var AppComponent = require("../components/AppComponent");
var BackboneMixin = require("../mixins/BackboneMixin");

var AppListComponent = React.createClass({
  displayName: "AppListComponent",

  mixins: [BackboneMixin],

  propTypes: {
    collection: React.PropTypes.object.isRequired,
    router: React.PropTypes.object.isRequired
  },

  getResource: function () {
    return this.props.collection;
  },

  sortCollectionBy: function (comparator) {
    var collection = this.props.collection;
    comparator =
      collection.sortKey === comparator && !collection.sortReverse ?
      "-" + comparator :
      comparator;
    collection.setComparator(comparator);
    collection.sort();
  },

  getAppNodes: function () {
    return (
      this.props.collection.map(function (model) {
        /* jshint trailing:false, quotmark:false, newcap:false */
        /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
        return (
          <AppComponent
            key={model.id}
            model={model}
            router={this.props.router} />
        );
        /* jshint trailing:true, quotmark:true, newcap:true */
        /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      }, this)
    );
  },

  render: function () {
    var sortKey = this.props.collection.sortKey;

    var loadingClassSet = React.addons.classSet({
      "hidden": this.props.fetchState !== States.STATE_LOADING
    });

    var noAppsClassSet = React.addons.classSet({
      "hidden": this.props.collection.length !== 0
    });

    var errorClassSet = React.addons.classSet({
      "hidden": this.props.fetchState !== States.STATE_ERROR
    });

    var headerClassSet = React.addons.classSet({
      "clickable": true,
      "dropup": this.props.collection.sortReverse
    });

    var tableClassSet = React.addons.classSet({
      "table table-fixed": true,
      "table-hover table-selectable":
        this.props.collection.length !== 0 &&
        this.props.fetchState !== States.STATE_LOADING
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <table className={tableClassSet}>
        <colgroup>
          <col style={{width: "28%"}} />
          <col style={{width: "14%"}} />
          <col style={{width: "14%"}} />
          <col style={{width: "14%"}} />
          <col style={{width: "14%"}} />
          <col style={{width: "16%"}} />
        </colgroup>
        <thead>
          <tr>
            <th>
              <span onClick={this.sortCollectionBy.bind(null, "id")} className={headerClassSet}>
                ID {sortKey === "id" ? <span className="caret"></span> : null}
              </span>
            </th>
            <th className="text-right">
              <span onClick={this.sortCollectionBy.bind(null, "mem")} className={headerClassSet}>
                {sortKey === "mem" ? <span className="caret"></span> : null} Memory (MB)
              </span>
            </th>
            <th className="text-right">
              <span onClick={this.sortCollectionBy.bind(null, "cpus")} className={headerClassSet}>
                {sortKey === "cpus" ? <span className="caret"></span> : null} CPUs
              </span>
            </th>
            <th className="text-right">
              <span onClick={this.sortCollectionBy.bind(null, "instances")} className={headerClassSet}>
                {sortKey === "instances" ? <span className="caret"></span> : null} Tasks / Instances
              </span>
            </th>
            <th className="text-right">
              <span className={headerClassSet}>
                Health
              </span>
            </th>
            <th className="text-right">
              <span onClick={this.sortCollectionBy.bind(null, "isDeploying")} className={headerClassSet}>
                {sortKey === "isDeploying" ? <span className="caret"></span> : null} Status
              </span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr className={loadingClassSet}>
            <td className="text-center text-muted" colSpan="6">
              Loading apps...
            </td>
          </tr>
          <tr className={noAppsClassSet}>
            <td className="text-center" colSpan="6">No running apps.</td>
          </tr>
          <tr className={errorClassSet}>
            <td className="text-center text-danger" colSpan="6">
              Error fetching apps. Refresh to try again.
            </td>
          </tr>
          {this.getAppNodes()}
        </tbody>
      </table>
    );
  }
});

module.exports = AppListComponent;
