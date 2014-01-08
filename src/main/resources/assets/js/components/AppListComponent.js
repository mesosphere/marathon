/** @jsx React.DOM */

define([
  "React",
  "jsx!components/AppComponent",
  "jsx!components/AppModalComponent",
  "mixins/BackboneMixin"
], function(React, AppComponent, AppModalComponent, BackboneMixin) {
  return React.createClass({
    getResource: function() {
      return this.props.collection;
    },
    onAppClick: function(model) {
      React.renderComponent(
        <AppModalComponent model={model} />,
        document.getElementById("lightbox")
      );
    },
    mixins: [BackboneMixin],
    render: function() {
      var _this = this;
      var comparator = this.props.collection.comparator;

      var appNodes = this.props.collection.map(function(model) {
        return <AppComponent key={model.cid} model={model} onClick={_this.onAppClick} />;
      });

      return (
        <table className="table table-hover item-table">
          <colgroup>
            <col style={{width: "30%"}} />
            <col style={{width: "30%"}} />
            <col style={{width: "14%"}} />
            <col style={{width: "13%"}} />
            <col style={{width: "13%"}} />
          </colgroup>
          <thead>
            <tr>
              <th onClick={this.sortCollectionBy.bind(this, "id")}>
                ID {(comparator === "id") ? "▼" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "cmd")}>
                CMD {(comparator === "cmd") ? "▼" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "mem")} className="text-right">
                {(comparator === "mem") ? "▼" : null} Memory (MB)
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "cpus")} className="text-right">
                {(comparator === "cpus") ? "▼" : null} CPUs
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "instances")} className="text-right">
                {(comparator === "instances") ? "▼" : null} Instances
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
      this.props.collection.comparator = attribute;
      this.props.collection.sort();
    }
  });
});
