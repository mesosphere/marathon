/** @jsx React.DOM */

var React = require("react/addons");
var Info = require("../models/Info");
var BackboneMixin = require("../mixins/BackboneMixin");
var PageComponent = require("../components/PageComponent");
var ObjectDlComponent = require("../components/ObjectDlComponent");

/* jshint trailing:false, quotmark:false, newcap:false */
/* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
var UNSPECIFIED_NODE = React.createClass({
  render: function () {
    return <span className="text-muted">Unspecified</span>;
  }
});

var AboutPageComponent = React.createClass({
  mixins: [BackboneMixin],

  getInitialState: function () {
    return {
      info: new Info()
    };
  },

  componentDidMount: function () {
    this.state.info.fetch();
  },

  getResource: function () {
    return this.state.info;
  },

  render: function () {
    var marathonConfig = this.state.info.get("marathon_config");
    var zookeeperConfig = this.state.info.get("zookeeper_config");

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <PageComponent>
        <h2>
          <small className="text-muted">
            Version {this.state.info.get("version")}
          </small>
        </h2>
        <dl className="dl-horizontal dl-horizontal-lg">
          <dt title="framework_id">Framework Id</dt>
          <dd>
            {this.state.info.get("framework_id") || <UNSPECIFIED_NODE />}
          </dd>
          <dt title="leader">Leader</dt>
          <dd>
            {this.state.info.get("leader") || <UNSPECIFIED_NODE />}
          </dd>
          <dt title="name">Name</dt>
          <dd>
            {this.state.info.get("name") || <UNSPECIFIED_NODE />}
          </dd>
        </dl>
        <h5 title="marathon_config">Marathon Config</h5>
        <ObjectDlComponent object={marathonConfig} />
        <h5 title="zookeeper_config">ZooKeeper Config</h5>
        <ObjectDlComponent object={zookeeperConfig} />
      </PageComponent>
    );
  }
});

module.exports = AboutPageComponent;
