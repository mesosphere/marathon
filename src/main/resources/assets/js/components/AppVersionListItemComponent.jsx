/** @jsx React.DOM */

define([
  "React",
  "models/App",
  "models/AppVersion",
  "jsx!components/AppVersionComponent"
], function(React, App, AppVersion, AppVersionComponent) {
  return React.createClass({
    displayName: "AppVersionListItemComponent",
    propTypes: {
      app: React.PropTypes.instanceOf(App).isRequired,
      appVersion: React.PropTypes.instanceOf(AppVersion).isRequired,
      onRollback: React.PropTypes.func
    },

    getInitialState: function() {
      return {
        open: !!this.props.currentVersion
      };
    },

    handleDetailsClick: function(event) {
      event.preventDefault();
      this.setState({open: !this.state.open});
      // this.props.onShowDetails(this.props.app, event);
    },

    render: function() {
      var caretClassSet = React.addons.classSet({
          "caret-right": this.state.open,
          "caret": !this.state.open
        });
      var versionDate = new Date(this.props.appVersion.get("version"));
      return (
        <li className="list-group-item list-group-item-inverse">
          <div className="row clickable" onClick={this.handleDetailsClick}>
            <span className="col-md-9">
              <time dateTime={versionDate.toISOString()} title={versionDate.toISOString()}>
                {versionDate.toLocaleString()}</time>
            </span>
            <span className="text-center col-md-1">
              <input type="radio" />
            </span>
            <span className="text-center col-md-1">
              <input type="radio" />
            </span>
            <span className="clickable text-center col-md-1">
              <span className={caretClassSet}></span>
            </span>
          </div>
          {
            this.state.open ?
              <div className="well well-sm">
                <AppVersionComponent
                  app={this.props.app}
                  onRollback={this.props.onRollback}
                  currentVersion={this.props.currentVersion} />
              </div> :
              null
          }
        </li>
      );
    }
  });
});
