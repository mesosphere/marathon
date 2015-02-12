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

  render: function () {
    // take out current version, to be displayed seperately
    var appVersions = this.props.app.versions.models.slice(1);

    var itemsPerPage = this.state.itemsPerPage;
    var currentPage = this.state.currentPage;

    var loadingClassSet = React.addons.classSet({
      "text-muted text-center": true,
      "hidden": this.props.fetchState !== States.STATE_LOADING
    });

    var errorClassSet = React.addons.classSet({
      "text-danger text-center": true,
      "hidden": this.props.fetchState === States.STATE_LOADING ||
        this.props.fetchState === States.STATE_SUCCESS
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    // at least two pages
    var pagedNav = appVersions.length > itemsPerPage ?
      <PagedNavComponent
        className="pull-right"
        currentPage={currentPage}
        onPageChange={this.handlePageChange}
        itemsPerPage={itemsPerPage}
        noItems={appVersions.length}
        useArrows={true} /> :
      null;

    // at least one older version
    var versionTable = appVersions.length > 0 ?
      <div className="panel-group">
        <div className="panel panel-header panel-inverse">
          <div className="panel-heading">
            Older versions
            {pagedNav}
          </div>
        </div>
        <PagedContentComponent
            currentPage={currentPage}
            itemsPerPage={itemsPerPage}>
          <p className={loadingClassSet}>Loading versions...</p>
          <p className={errorClassSet}>Error fetching app versions</p>
          {this.getAppVersionList(appVersions)}
        </PagedContentComponent>
      </div> :
      null;

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
          {versionTable}
      </div>
    );
  }
});

module.exports = AppVersionListComponent;
