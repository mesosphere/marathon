/** @jsx React.DOM */

define([
  "React",
  "jsx!components/AppComponent",
  "mixins/BackboneMixin"
], function(React, AppComponent, BackboneMixin) {
  return React.createClass({
    mixins: [BackboneMixin],
    render: function() {
      var comparator = this.props.collection.comparator;
      var appNodes = this.props.collection.map(function(model) {
        return <AppComponent key={model.cid} model={model} />;
      });

      return (
        <table className="table table-hover item-table">
          <thead>
            <tr>
              <th onClick={this.sortCollectionBy.bind(this, "id")}>
                ID {(comparator === "id") ? "▾" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "cmd")}>
                CMD {(comparator === "cmd") ? "▾" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "mem")}>
                Memory {(comparator === "mem") ? "▾" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "cpus")}>
                CPUs {(comparator === "cpus") ? "▾" : null}
              </th>
              <th onClick={this.sortCollectionBy.bind(this, "instances")}>
                Instances {(comparator === "instances") ? "▾" : null}
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
