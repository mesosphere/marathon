/** @jsx React.DOM */

define([
  "React",
  "Underscore",
  "jsx!components/AppComponent",
  "mixins/BackboneMixin"
], function(React, _, AppComponent, BackboneMixin) {
  "use strict";

  var STATE_LOADING = 0;
  var STATE_ERROR = 1;
  var STATE_SUCCESS = 2;

  var UPDATE_INTERVAL = 5000;

  return React.createClass({
    displayName: "AppListComponent",
    mixins: [BackboneMixin],

    propTypes: {
      collection: React.PropTypes.object.isRequired,
      deployments: React.PropTypes.object.isRequired,
      onSelectApp: React.PropTypes.func.isRequired
    },

    componentDidMount: function() {
      this.startPolling();
    },

    componentWillUnmount: function() {
      this.stopPolling();
    },

    getInitialState: function() {
      return {
        fetchState: STATE_LOADING
      };
    },

    getResource: function() {
      return this.props.collection;
    },

    fetchResource: function() {
      this.props.collection.fetch({
        error: function() {
          this.setState({fetchState: STATE_ERROR});
        }.bind(this),
        reset: true,
        success: function() {
          this.setState({fetchState: STATE_SUCCESS});
          this.props.deployments.fetch({
            error: function() {
              this.setState({fetchState: STATE_ERROR});
            }.bind(this),
            reset: true,
            success: function(response, deployments) {
              // create deployments map
              var deploymentsMap = {};
              deployments.forEach(function (deployment) {
                _.forEach(deployment.affectedApplications, function (id) {
                  if (deploymentsMap[id] == null) {
                    deploymentsMap[id] = [];
                  }
                  deploymentsMap[id].push(deployment.id);
                });
              });

              // apply map to apps
              _.map(deploymentsMap, function(val, id) {
                var app = this.props.collection.get(id);
                app.set("deployments", _.uniq(val));
              }, this);
            }.bind(this)
          });
        }.bind(this)
      });
    },

    onClickApp: function(app) {
      this.stopPolling();
      this.props.onSelectApp(app);
    },

    sortCollectionBy: function(comparator) {
      var collection = this.props.collection;
      comparator =
        collection.sortKey === comparator && !collection.sortReverse ?
        "-" + comparator :
        comparator;
      collection.setComparator(comparator);
      collection.sort();
    },

    startPolling: function() {
      if (this._interval == null) {
        this.fetchResource();
        this._interval = setInterval(this.fetchResource, UPDATE_INTERVAL);
      }
    },

    stopPolling: function() {
      if (this._interval != null) {
        clearInterval(this._interval);
        this._interval = null;
      }
    },

    render: function() {
      var sortKey = this.props.collection.sortKey;

      var appNodes;
      var tableClassName = "table table-fixed table-badged";

      var headerClassSet = React.addons.classSet({
        "clickable": true,
        "dropup": this.props.collection.sortReverse
      });

      if (this.state.fetchState === STATE_LOADING) {
        appNodes =
          <tr>
            <td className="text-center text-muted" colSpan="5">
              Loading apps...
            </td>
          </tr>;
      } else if (this.state.fetchState === STATE_ERROR) {
        appNodes =
          <tr>
            <td className="text-center text-danger" colSpan="5">
              Error fetching apps. Refresh to try again.
            </td>
          </tr>;
      } else if (this.props.collection.length === 0) {
        appNodes =
          <tr>
            <td className="text-center" colSpan="5">No running apps.</td>
          </tr>;
      } else {

        /* jshint trailing:false, quotmark:false, newcap:false */
        appNodes = this.props.collection.map(function(model) {
          return <AppComponent key={model.id} model={model} onClick={this.onClickApp} />;
        }, this);

        // Give rows the selectable look when there are apps to click.
        tableClassName += " table-hover table-selectable";
      }

      /* jshint trailing:false, quotmark:false, newcap:false */
      return (
        <table className={tableClassName}>
          <colgroup>
            <col style={{width: "25%"}} />
            <col style={{width: "28%"}} />
            <col style={{width: "10%"}} />
            <col style={{width: "10%"}} />
            <col style={{width: "13%"}} />
            <col style={{width: "13%"}} />
          </colgroup>
          <thead>
            <tr>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "id")} className={headerClassSet}>
                  ID {sortKey === "id" ? <span className="caret"></span> : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "cmd")} className={headerClassSet}>
                  Command {sortKey === "cmd" ? <span className="caret"></span> : null}
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
                <span onClick={this.sortCollectionBy.bind(null, "isDeploying")} className={headerClassSet}>
                  {sortKey === "isDeploying" ? <span className="caret"></span> : null} Status
                </span>
              </th>
            </tr>
          </thead>
          <tbody>
            {appNodes}
          </tbody>
        </table>
      );
    }
  });
});
