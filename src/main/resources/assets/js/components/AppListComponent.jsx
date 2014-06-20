/** @jsx React.DOM */

define([
  "React",
  "jsx!components/AppComponent",
  "mixins/BackboneMixin"
], function(React, AppComponent, BackboneMixin) {
  var STATE_LOADING = 0;
  var STATE_ERROR = 1;
  var STATE_SUCCESS = 2;

  var UPDATE_INTERVAL = 5000;

  return React.createClass({
    displayName: "AppListComponent",
    mixins: [BackboneMixin],

    propTypes: {
      collection: React.PropTypes.object.isRequired,
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
      var _this = this;

      this.props.collection.fetch({
        error: function() {
          _this.setState({fetchState: STATE_ERROR});
        },
        reset: true,
        success: function() {
          _this.setState({fetchState: STATE_SUCCESS});
        }
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
      var tableClassName = "table table-fixed";

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
        appNodes = this.props.collection.map(function(model) {
          return <AppComponent key={model.id} model={model} onClick={this.onClickApp} />;
        }, this);

        // Give rows the selectable look when there are apps to click.
        tableClassName += " table-hover table-selectable";
      }

      return (
        <table className={tableClassName}>
          <colgroup>
            <col style={{width: "25%"}} />
            <col style={{width: "35%"}} />
            <col style={{width: "14%"}} />
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
                  {sortKey === "instances" ? <span className="caret"></span> : null} Instances
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
