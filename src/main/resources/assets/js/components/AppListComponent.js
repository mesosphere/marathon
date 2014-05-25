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
      var sortOrder =
        (this.props.collection.sortReverse) ?
        "▲" :
        "▼";

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
                <span onClick={this.sortCollectionBy.bind(null, "id")} className="clickable">
                  ID {sortKey === "id" ? sortOrder : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "cmd")} className="clickable">
                  Command {sortKey === "cmd" ? sortOrder : null}
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "mem")} className="text-right clickable">
                  {sortKey === "mem" ? sortOrder : null} Memory (MB)
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "cpus")} className="text-right clickable">
                  {sortKey === "cpus" ? sortOrder : null} CPUs
                </span>
              </th>
              <th>
                <span onClick={this.sortCollectionBy.bind(null, "instances")} className="text-right clickable">
                  {sortKey === "instances" ? sortOrder : null} Instances
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
