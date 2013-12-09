/** @jsx React.DOM */

define([
  "React",
  "jsx!components/AppComponent",
  "jsx!components/AppModalComponent",
  "mixins/BackboneMixin"
], function(React, AppComponent, AppModalComponent, BackboneMixin) {
  return React.createClass({
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
          <thead>
            <tr>
              <th onClick={this.sortCollectionBy.bind(this, "id")}>
                ID {(comparator === "id") ? "▼" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "cmd")}>
                CMD {(comparator === "cmd") ? "▼" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "mem")}>
                Memory {(comparator === "mem") ? "▼" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "cpus")}>
                CPUs {(comparator === "cpus") ? "▼" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "instances")}>
                Instances {(comparator === "instances") ? "▼" : null}
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
