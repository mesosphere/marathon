/** @jsx React.DOM */

"use strict";

var assert = require("assert");
var Backbone = require("backbone");
var cheerio = require("cheerio");
var React = require("react");
var sinon = require("sinon");
var Backbone = require("backbone");

var AppListComponent = require("../../js/components/AppListComponent");

var AppCollection = require("../../js/models/AppCollection");

var AppCollectionMockData = require("../mock/AppCollection.mockData");

var States = require("../../js/constants/States");

var $ = cheerio.load("<html><body></body></html>");

function noop(){}

module.exports = {

  "AppList": {

    beforeEach: function () {
      this.sinon = sinon.sandbox.create();
      this.ajaxStub = this.sinon.stub(Backbone, "ajax").
        yieldsTo("success", AppCollectionMockData);

      this.collection = new AppCollection();
    },

    afterEach: function () {
      $("body").empty();
      this.sinon.restore();
      this.ajaxStub.restore();
    },

    "should render the component populated with data": function () {
      this.collection.fetch();

      /* jscs:disable disallowTrailingWhitespace, validateQuoteMarks */
      /* jshint trailing:false, quotmark:false, newcap:false */
      var appList = React.renderToString(
        <AppListComponent
                  collection={this.collection}
                  router={new Backbone.Router()} />
      );

      $("body").append(appList);

      assert.equal( $(".table tr td").eq(9).text(), "/docker-app" );
    }
  }
};
