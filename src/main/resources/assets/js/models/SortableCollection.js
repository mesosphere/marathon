define([
  "Backbone",
  "Underscore"
], function(Backbone, _) {
  var DEFAULT_ATTR = "id";
  var SortableCollection = Backbone.Collection.extend({
    setComparator: function(attribute) {
      attribute = attribute || DEFAULT_ATTR;
      this.sortReverse = attribute.substr(0, 1) === "-";
      this.sortKey = this.sortReverse ? attribute.substr(1) : attribute;
      this.comparator = function(a, b) {
        // Assuming that the sortKey values
        // can be compared with '>' and '<'
        a = _.isFunction(a[this.sortKey]) ? a[this.sortKey]() : a.get(this.sortKey);
        b = _.isFunction(b[this.sortKey]) ? b[this.sortKey]() : b.get(this.sortKey);
        return this.sortReverse
                ? b < a ? 1 : b > a ? -1 : 0 // reversed
                : a < b ? 1 : a > b ? -1 : 0; // regular
      };
    }
  });

  return SortableCollection;
});
