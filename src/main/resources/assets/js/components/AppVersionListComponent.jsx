/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "jsx!components/AppVersionListItemComponent",
], function(React, App, AppVersionListItemComponent) {
  return React.createClass({
    displayName: "AppVersionListComponent",

    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersions: React.PropTypes.array,
      onRollback: React.PropTypes.func
    },

    render: function() {
      var appVersions = this.props.appVersions;
      // Should I use this here?
      // new Date(this.props.app.get("version")).getTime() == new Date(v.get("version")).getTime()
      return (
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
              appVersions == null ?
                <div className="text-muted text-center">Loading versions...</div> :
                appVersions.length > 0 ?
                  appVersions.map(function(v, i) {
                    return (
                        <AppVersionListItemComponent
                          app={this.props.app}
                          appVersion={v}
                          currentVersion={i === 0}
                          key={v.get("version")}
                          onRollback={this.props.onRollback} />
                    );
                  }, this) :
                  <div className="text-danger text-center">Error fetching app versions</div>
            }
        </div>
      );
    }
  });
});
