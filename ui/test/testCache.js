const cache = require('../app/utils/cache');
var assert = require('assert');
describe('Verifying cache operations in sequence: ', function () {

  const lruCache = new cache.LRUBasedCache(3);

  describe('#get(1)', function () {
    it('should return null when get is called for not present key 1', function () {
      assert.strictEqual(null, lruCache.get(1));
      assert.deepStrictEqual({}, lruCache.valueMap);
      assert.deepStrictEqual([], lruCache.recentKeys);
    });
  });

  describe('#put(1,\'1\')', function () {
    it('should return value for 1', function () {
      assert.strictEqual('1', lruCache.put(1, '1'));
      assert.deepStrictEqual({1: '1'}, lruCache.valueMap);
      assert.deepStrictEqual([1], lruCache.recentKeys);
    });
  });
  describe('#put(2,\'2\')', function () {
    it('should return value for 2', function () {
      assert.strictEqual('2', lruCache.put(2, '2'));
      assert.deepStrictEqual({1: '1', 2: '2'}, lruCache.valueMap);
      assert.deepStrictEqual([1, 2], lruCache.recentKeys);
    });
  });
  describe('#get(1)', function () {
    it('should return \'1\' when get is called for present key 1', function () {
      assert.strictEqual('1', lruCache.get(1));
      assert.deepStrictEqual({1: '1', 2: '2'}, lruCache.valueMap);
      assert.deepStrictEqual([2, 1], lruCache.recentKeys);
    });
  });

  describe('#put(3,\'3\')', function () {
    it('should return value for 3', function () {
      assert.strictEqual('3', lruCache.put(3, '3'));
      assert.deepStrictEqual({1: '1', 2: '2', 3: '3'}, lruCache.valueMap);
      assert.deepStrictEqual([2, 1, 3], lruCache.recentKeys);
    });
  });
  describe('#put(4,\'4\')', function () {
    it('should return value for 4', function () {
      assert.strictEqual('4', lruCache.put(4, '4'));
      assert.deepStrictEqual({1: '1',  3: '3', 4: '4'}, lruCache.valueMap);
      assert.deepStrictEqual([1, 3, 4], lruCache.recentKeys);
    });
  });
  describe('#get(2)', function () {
    it('should return null when get is called for not present key 2', function () {
      assert.strictEqual(null, lruCache.get(2));
      assert.deepStrictEqual({1: '1',  3: '3', 4: '4'}, lruCache.valueMap);
      assert.deepStrictEqual([1, 3, 4], lruCache.recentKeys);
    });
  });

  const lruCache2 = new cache.LRUBasedCache(2);
  describe('#new LRUBasedCache', function () {
    it('should be a new instance of LRUBasedCache', function () {
      assert.deepStrictEqual({}, lruCache2.valueMap);
      assert.deepStrictEqual([], lruCache2.recentKeys);
    });
  });
});
