/** @jsx React.DOM */

var React = require("react/addons");
var States = require("../constants/States");
var App = require("../models/App");
var BackboneMixin = require("../mixins/BackboneMixin");
var AppVersionComponent = require("../components/AppVersionComponent");
var AppVersionListItemComponent =
  require("../components/AppVersionListItemComponent");
var PagedContentComponent = require("../components/PagedContentComponent");
var PagedNavComponent = require("../components/PagedNavComponent");

var AppVersionListComponent = React.createClass({
  displayName: "AppVersionListComponent",

  mixins: [BackboneMixin],

  propTypes: {
    app: React.PropTypes.instanceOf(App).isRequired,
    fetchAppVersions: React.PropTypes.func.isRequired,
    onRollback: React.PropTypes.func
  },

  componentWillMount: function () {
    this.props.fetchAppVersions();
  },

  getResource: function () {
    return this.props.app.versions;
  },

  getInitialState: function () {
    return {
      currentPage: 0,
      itemsPerPage: 8
    };
  },

  handleRefresh: function () {
    this.props.fetchAppVersions();
  },

  handlePageChange: function (pageNum) {
    this.setState({currentPage: pageNum});
  },

  getAppVersionList: function (appVersions) {
    return appVersions.map(function (v) {
      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <AppVersionListItemComponent
          app={this.props.app}
          appVersion={v}
          key={v.get("version")}
          onRollback={this.props.onRollback} />
      );
      /* jshint trailing:true, quotmark:true, newcap:true */
      /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    }, this);
  },

  getPagedNav: function (appVersions) {
    var itemsPerPage = this.state.itemsPerPage;

    // at least two pages
    if (appVersions.length > itemsPerPage) {
      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <PagedNavComponent
          className="pull-right"
          currentPage={this.state.currentPage}
          onPageChange={this.handlePageChange}
          itemsPerPage={itemsPerPage}
          noItems={appVersions.length}
          useArrows={true} />
      );
      /* jshint trailing:true, quotmark:true, newcap:true */
      /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    }

    return null;
  },

  getAppVersionTable: function () {
    // take out current version, to be displayed seperately
    var appVersions = this.props.app.versions.models.slice(1);

    var loadingClassSet = React.addons.classSet({
      "text-muted text-center": true,
      "hidden": this.props.fetchState !== States.STATE_LOADING
    });

    var errorClassSet = React.addons.classSet({
      "text-danger text-center": true,
      "hidden": this.props.fetchState === States.STATE_LOADING ||
        this.props.fetchState === States.STATE_SUCCESS
    });

    // at least one older version
    if (appVersions.length > 0) {
      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <div className="panel-group">
          <div className="panel panel-header panel-inverse">
            <div className="panel-heading">
              Older versions
              {this.getPagedNav(appVersions)}
            </div>
          </div>
          <PagedContentComponent
              currentPage={this.state.currentPage}
              itemsPerPage={this.state.itemsPerPage}>
            <p className={loadingClassSet}>Loading versions...</p>
            <p className={errorClassSet}>Error fetching app versions</p>
            {this.getAppVersionList(appVersions)}
          </PagedContentComponent>
        </div>
      );
      /* jshint trailing:true, quotmark:true, newcap:true */
      /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    }

    return null;
  },

  render: function () {
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div>
        <h5>
          Current Version
          <button className="btn btn-sm btn-info pull-right"
              onClick={this.handleRefresh}>
            ↻ Refresh
          </button>
        </h5>
        <AppVersionComponent
            app={this.props.app}
            appVersion={this.props.app.getCurrentVersion()}
            currentVersion={true} />
          {this.getAppVersionTable()}
      </div>
    );
  }
});

module.exports = AppVersionListComponent;
