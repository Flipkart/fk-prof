//using es5 syntax to make it usable in node server.js,
//refer https://stackoverflow.com/questions/42464014/how-to-import-reactjs-es6-code-into-express-app

module.exports = {
  LRUBasedCache: function (size) {
    this.recentKeys = [];
    this.cacheSize = size;
    this.valueMap = {};
    //Both put and get on a key refreshes it to be the most recently used key
    this.get = function (key) {
      if (key in this.valueMap) {
        if (this.recentKeys[this.recentKeys.length - 1] !== key) {
          this.recentKeys.splice(this.recentKeys.findIndex(x => x === key), 1); //remove the key from its index and
          this.recentKeys.push(key);                                                 //move to the front
        }
        return this.valueMap[key];
      }
      return null;
    };
    this.put = function (key, value) {
      if (! (key in this.valueMap)) {
        if (this.recentKeys.length >= this.cacheSize) {
          delete this.valueMap[this.recentKeys.shift()];   //evict oldest from recentKeys and valueMap
        }
        this.recentKeys.push(key);
      }
      this.valueMap[key] = value;
      return this.get(key);
    };
  }
};
