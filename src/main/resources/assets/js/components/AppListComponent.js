/** @jsx React.DOM */

define([
  "React",
  "jsx!components/AppComponent",
  "jsx!components/AppModalComponent",
  "mixins/BackboneMixin"
], function(React, AppComponent, AppModalComponent, BackboneMixin) {
  var STATE_LOADING = 0;
  var STATE_ERROR = 1;
  var STATE_SUCCESS = 2;

  var UPDATE_INTERVAL = 5000;

  return React.createClass({
    mixins: [BackboneMixin],
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
    onAppClick: function(model) {
      React.renderComponent(
        <AppModalComponent model={model} onDestroy={this.startPolling} />,
        document.getElementById("lightbox")
      );

      this.stopPolling();
    },
    render: function() {
      var _this = this;
      var sortKey = this.props.collection.sortKey;
      var sortReverse = this.props.collection.sortReverse;

      var appNodes;
      var tableClassName = "table table-fixed";
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
          return <AppComponent key={model.cid} model={model} onClick={_this.onAppClick} />;
        });

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
                <span onClick={this.sortCollectionBy.bind(this, "id")} className="clickable">
                  ID {sortKey === "id" ? (sortReverse ? "▲" : "▼") : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(this, "cmd")} className="clickable">
                  Command {sortKey === "cmd" ? (sortReverse ? "▲" : "▼") : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(this, "mem")} className="text-right clickable">
                  {sortKey === "mem" ? (sortReverse ? "▲" : "▼") : null} Memory (MB)
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(this, "cpus")} className="text-right clickable">
                  {sortKey === "cpus" ? (sortReverse ? "▲" : "▼") : null} CPUs
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(this, "instances")} className="text-right clickable">
                  {sortKey === "instances" ? (sortReverse ? "▲" : "▼") : null} Instances
                </span>
              </th>
            </tr>
          </thead>
          <tbody>
            {appNodes}
          </tbody>
        </table>
      );
    },
    sortCollectionBy: function(attribute) {
      var collection = this.props.collection;
      collection.sortReverse = !collection.sortReverse;
      if (collection.sortKey !== attribute) { // reset on sortKey change
        collection.sortReverse = false;
      }
      collection.sortKey = attribute;
      collection.comparator = function(a, b) {
        // Assuming that the sortKey values can be compared with '>' and '<'
        a = a.attributes[attribute];
        b = b.attributes[attribute];
        return collection.sortReverse
                ? b < a ?  1 : b > a ? -1 : 0 // reversed
                : a < b ?  1 : a > b ? -1 : 0; // regular
      };
      collection.sort();
    },
    startPolling: function() {
      this.fetchResource();

      if (this._interval == null) {
        this._interval = setInterval(this.fetchResource, UPDATE_INTERVAL);
      }
    },
    stopPolling: function() {
      clearInterval(this._interval);
      this._interval = null;
    }
  });
});
