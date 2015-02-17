/** @jsx React.DOM */

var React = require("react/addons");
var States = require("../constants/States");
var App = require("../models/App");
var AppVersion = require("../models/AppVersion");
var BackboneMixin = require("../mixins/BackboneMixin");
var AppVersionComponent = require("../components/AppVersionComponent");

var AppVersionListItemComponent = React.createClass({
  displayName: "AppVersionListItemComponent",

  mixins: [BackboneMixin],

  propTypes: {
    app: React.PropTypes.instanceOf(App).isRequired,
    appVersion: React.PropTypes.instanceOf(AppVersion).isRequired,
    onRollback: React.PropTypes.func
  },

  componentDidMount: function () {
    if (this.state.open) {
      this.getVersionDetails();
    }
  },

  getResource: function () {
    return this.props.appVersion;
  },

  getInitialState: function () {
    return {
      open: false,
      fetchState: States.STATE_LOADING
    };
  },

  getVersionDetails: function () {
    this.props.appVersion.fetch({
      error: function () {
        this.setState({fetchState: States.STATE_ERROR});
      }.bind(this),
      success: function () {
        this.setState({fetchState: States.STATE_SUCCESS});
      }.bind(this)
    });
  },

  handleDetailsClick: function (event) {
    if (event.target.type === "radio") {
      // handled by other functions
      return;
    }
    event.preventDefault();
    if (this.state.fetchState !== States.STATE_SUCCESS) {
      this.getVersionDetails();
    }
    this.setState({open: !this.state.open});
  },

  getAppVersionComponent: function () {
    if (this.state.fetchState !== States.STATE_LOADING &&
        this.state.fetchState !== States.STATE_ERROR) {
      return (
        /* jshint trailing:false, quotmark:false, newcap:false */
        /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
        <AppVersionComponent
          className="dl-unstyled"
          app={this.props.app}
          appVersion={this.props.appVersion}
          onRollback={this.props.onRollback} />
      );
      /* jshint trailing:true, quotmark:true, newcap:true */
      /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    }

    return null;
  },

  getAppVersion: function () {
    var loadingClassSet = React.addons.classSet({
      "text-center text-muted": true,
      "hidden": this.state.fetchState !== States.STATE_LOADING
    });

    var errorClassSet = React.addons.classSet({
      "text-center text-danger": true,
      "hidden": this.state.fetchState !== States.STATE_ERROR
    });

    if (this.state.open) {
      /* jshint trailing:false, quotmark:false, newcap:false */
      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
      return (
        <div className="panel-body">
          <p className={loadingClassSet}>
            Loading version details...
          </p>
          <p className={errorClassSet}>
            Error fetching version details. Refresh the list to try again.
          </p>
          {this.getAppVersionComponent()}
        </div>
      );
      /* jshint trailing:true, quotmark:true, newcap:true */
      /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    }

    return null;
  },

  render: function () {
    var versionDate = new Date(this.props.appVersion.get("version"));
    var versionDateISOString = versionDate.toISOString();

    var caretClassSet = React.addons.classSet({
      "clickable text-right col-xs-2": true,
      "dropup": this.state.open
    });

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <div className="panel panel-inverse">
        <div className="panel-heading clickable"
            onClick={this.handleDetailsClick}>
          <div className="row">
            <div className="col-xs-10">
              <time dateTime={versionDateISOString}
                  title={versionDateISOString}>
                {versionDate.toLocaleString()}
              </time>
            </div>
            <div className={caretClassSet}>
              <span className="caret"></span>
            </div>
          </div>
        </div>
        {this.getAppVersion()}
      </div>
    );
  }
});

module.exports = AppVersionListItemComponent;
