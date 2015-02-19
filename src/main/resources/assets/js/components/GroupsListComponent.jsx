/** @jsx React.DOM */

var React = require("react/addons");
var States = require("../constants/States");
var BackboneMixin = require("../mixins/BackboneMixin");
var AppListComponent = require("../components/AppListComponent");
var AppCollection = require("../models/AppCollection");

var GroupsListComponent = React.createClass({
  displayName: "GroupsListComponent",

  mixins: [BackboneMixin],

  propTypes: {
    collection: React.PropTypes.object.isRequired,
    onSelectApp: React.PropTypes.func.isRequired
  },

  getInitialState: function () {
    return {openGroupIDs: []};
  },

  getResource: function () {
    return this.props.collection;
  },

  getGroups: function () {
    var _this = this;
    var groups = [];

    this.getResource().forEach(function (model) {
      var attributes = model.toJSON();
      var dirname = _this.dirname(attributes.id) || "/"; // Default to root: '/'

      // Seek for previously created dirname hash
      var found = false;
      for (var i = 0; i < groups.length; i++) {
        if (groups[i].id === dirname) {
          groups[i].collection.push(attributes);
          found = true;
          break;
        }
      }

      if (!found) {
        groups.push({
          id: dirname,
          collection: [attributes]
        });
      }
    });

    // Sort alphabetically
    groups.sort(function (a, b) {
      return a.id > b.id;
    });

    return groups;
  },

  dirname: function (path) {
    return path.replace(/\\/g, "/")
      .replace(/\/[^\/]*\/?$/, "");
  },

  isOpen: function (groupID) {
    return this.state.openGroupIDs[groupID];
  },

  handleGroupToggle: function (groupID) {
    var openGroupIDs = this.state.openGroupIDs;
    if (this.isOpen(groupID)) {
      openGroupIDs[groupID] = false;
    } else {
      openGroupIDs[groupID] = true;
    }
    this.setState({openGroupIDs: openGroupIDs});
  },

  getGroupList: function (group) {
    if (!this.state.openGroupIDs[group.id]) {
      return;
    }

    var collection = new AppCollection(group.collection);
    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    return (
      <AppListComponent
        collection={collection}
        onSelectApp={this.props.onSelectApp}
        fetchState={this.props.fetchState} />
      );
    /* jshint trailing:true, quotmark:true, newcap:true */
    /* jscs:enable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
  },

  render: function () {
    var apps;

    var emptyClassSet = React.addons.classSet({
      "hidden": this.getResource().length > 0
    });

    apps = <div>Loading apps...</div>;

    /* jshint trailing:false, quotmark:false, newcap:false */
    /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks, maximumLineLength */
    if (this.props.fetchState !== States.STATE_LOADING) {
      var groups = this.getGroups();
      apps = groups.map(function (group) {
        var folderClass = "icon ";
        if (this.isOpen(group.id)) {
          folderClass += "icon-folder-open";
        } else {
          folderClass += "icon-folder-closed";
        }

        return (
          <div key={group.id} className="app-group">
            <span className="app-group-title" onClick={this.handleGroupToggle.bind(null, group.id)}>
              <i className={folderClass}></i> {group.id}
            </span>
            {this.getGroupList(group)}
          </div>
        );
      }, this);
    }

    return (
      <div>
        <div className={emptyClassSet}>No running apps.</div>
        <div>{apps}</div>
      </div>
    );
  }
});

module.exports = GroupsListComponent;
