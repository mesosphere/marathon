/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "jsx!components/AppVersionListItemComponent",
], function(React, App, AppVersionListItemComponent) {
  "use strict";

  var ITEMS_PER_PAGE = 3;
  return React.createClass({
    displayName: "AppVersionListComponent",
    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersions: React.PropTypes.object.isRequired,
      fetchAppVersions: React.PropTypes.func.isRequired,
      onRollback: React.PropTypes.func
    },

    getInitialState: function() {
      return {
        currentPage: 0
      };
    },

    render: function() {
      var appVersions = this.props.appVersions.models;
      // Should I use this here?
      // new Date(this.props.app.get("version")).getTime() === new Date(v.get("version")).getTime()
      // this also adds the need to refresh the app configuration

      return (
        <div>
          <p>
            <button className="btn btn-sm btn-info" onClick={this.props.fetchAppVersions}>
              ↻ Refresh
            </button>
          </p>
          <div className="panel-group">
            <div className="panel panel-header panel-inverse">
              <div className="panel-heading row">
                <span className="col-md-7">
                  Version
                </span>
                <span className="text-center col-md-2">
                  Diff from
                </span>
                <span className="text-center col-md-2">
                  Diff to
                </span>
                <span className="clickable text-center col-md-1">
                </span>
              </div>
            </div>
              {
                this.props.fetchState === this.props.STATES.STATE_LOADING ?
                  <div className="text-muted text-center">Loading versions...</div> :
                  this.props.fetchState === this.props.STATES.STATE_SUCCESS ?
                    appVersions.map(function(v, i) {
                      if (i < ITEMS_PER_PAGE) {
                        return (
                            <AppVersionListItemComponent
                              app={this.props.app}
                              appVersion={v}
                              currentVersion={i === 0}
                              key={v.get("version")}
                              onRollback={this.props.onRollback} />
                        );
                      }
                    }, this) :
                    <div className="text-danger text-center">Error fetching app versions</div>
              }
          </div>
        </div>
      );
    }
  });
});
