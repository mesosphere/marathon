/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "models/AppVersion",
  "mixins/BackboneMixin",
  "jsx!components/AppVersionComponent"
], function(React, App, AppVersion, BackboneMixin, AppVersionComponent) {
  "use strict";

  var STATES = {
    STATE_LOADING: 0,
    STATE_ERROR: 1,
    STATE_SUCCESS: 2
  };

  return React.createClass({
    displayName: "AppVersionListItemComponent",
    mixins: [BackboneMixin],
    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersion: React.PropTypes.instanceOf(AppVersion).isRequired,
      onRollback: React.PropTypes.func
    },

    componentDidMount: function() {
      if (this.state.open) {
        this.getVersionDetails();
      }
    },

    getResource: function () {
      return this.props.appVersion;
    },

    getInitialState: function() {
      return {
        open: !!this.props.currentVersion,
        fetchState: STATES.STATE_LOADING
      };
    },

    getVersionDetails: function() {
      this.props.appVersion.fetch({
        error: function() {
          this.setState({fetchState: STATES.STATE_ERROR});
        },
        success: function() {
          this.setState({fetchState: STATES.STATE_SUCCESS});
        }.bind(this)
      });
    },

    handleDetailsClick: function(event) {
      if (event.target.type === "radio") {
        // handled by other functions
        return;
      }
      event.preventDefault();
      if (this.state.fetchState !== STATES.STATE_SUCCESS) {
        this.getVersionDetails();
      }
      this.setState({open: !this.state.open});
    },

    render: function() {
      var caretClassSet = React.addons.classSet({
        "caret caret-left": this.state.open,
        "caret": !this.state.open
      });
      var versionDate = new Date(this.props.appVersion.get("version"));
      var versionNode;
      if (this.state.fetchState === STATES.STATE_LOADING) {
        versionNode =
          <div className="panel-body">
            <p className="text-center text-muted">
              Loading version details...
            </p>
          </div>;
      } else if (this.state.fetchState === STATES.STATE_ERROR) {
        versionNode =
          <div className="panel-body">
            <p className="text-center text-danger">
              Error fetching version details. Refresh the list to try again.
            </p>
          </div>;
      } else {
        versionNode =
        <div className="panel-body">
          <AppVersionComponent
            app={this.props.app}
            appVersion={this.props.appVersion}
            currentVersion={this.props.currentVersion}
            onRollback={this.props.onRollback} />
        </div>;
      }
      return (
        <div className="panel panel-inverse">
          <div className="panel-heading row clickable" onClick={this.handleDetailsClick}>
            <span className="col-md-9">
              <time dateTime={versionDate.toISOString()} title={versionDate.toISOString()}>
                {versionDate.toLocaleString()}</time>
            </span>
            {
              this.props.currentVersion ?
                <span className="text-muted col-md-2">Current version</span> :
                <span className="text-muted col-md-2"></span>
            }
            <span className="clickable text-center col-md-1">
              <span className={caretClassSet}></span>
            </span>
          </div>
            {
              this.state.open ?
                  versionNode :
                  null
            }
        </div>
      );
    }
  });
});
