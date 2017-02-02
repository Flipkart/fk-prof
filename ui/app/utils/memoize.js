/*
* memoize.js
* by @philogb and @addyosmani
* with further optimizations by @mathias
* and @DmitryBaranovsk
* perf tests: http://bit.ly/q3zpG3
* Released under an MIT license.
*/
import hashCode from 'utils/hash';

export default function memoize (fn, getUniqueId = JSON.stringify, isArray) {
  return function () {
    const args = Array.prototype.slice.call(arguments);
    let hash = '';
    let i = args.length;
    let currentArg = null;
    fn.memoize || (fn.memoize = {});
    while (i--) {
      currentArg = args[i];
      hash += (Object.prototype.toString.call(currentArg) !== '[object String]')
      ? getUniqueId(currentArg) : currentArg;
    }
    hash = hashCode(hash);
    return ((hash in fn.memoize)
    ? fn.memoize[hash] : fn.memoize[hash] = fn.apply(this, isArray ? [args] : args));
  };
}